package com.k.airscope

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater: checks the GitHub repo's latest release, downloads AirScope.apk, verifies it is
 * the same app signed with the same key, then installs it with Android's PackageInstaller.
 * A private repo needs a read-only GitHub token, stored only on this phone.
 */
object Updater {
    private const val JOB_ID = 4242
    const val PREF_TOKEN = "gh_token"
    const val PREF_RESULT = "update_result"
    const val PREF_LAST = "update_last_check"

    fun installedBuild(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** "stable" (key from the AIRSCOPE_SIGNING_SEED secret), "onetime", or "unknown" (builds before 6). */
    fun signingKind(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("airscope.signing") ?: "unknown"
    } catch (_: Exception) { "unknown" }

    /** GitHub's token page, pre-filled: name, read-only Contents permission, 1-year expiry, your account. */
    val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new?name=AirScope+updater" +
        "&description=Lets+the+AirScope+app+on+my+phone+download+its+own+updates&target_name=${UpdateCheck.OWNER}" +
        "&expires_in=366&contents=read"
    val SECRET_URL = "https://github.com/${UpdateCheck.OWNER}/${UpdateCheck.REPO}/settings/secrets/actions/new"
    val RELEASES_URL = "https://github.com/${UpdateCheck.OWNER}/${UpdateCheck.REPO}/releases"

    /** Pasted tokens often carry spaces or line breaks. */
    fun cleanToken(s: String): String = s.filterNot { it.isWhitespace() }

    fun looksLikeToken(t: String): Boolean =
        (t.startsWith("github_pat_") && t.length >= 40) || (t.startsWith("ghp_") && t.length >= 36)

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        try { js.schedule(job) } catch (_: Exception) { }
    }

    fun checkAsync(ctx: Context, manual: Boolean, done: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val msg = try { check(app, manual) } catch (e: Exception) { "Update check failed: ${e.message}" }
            Prefs.putString(app, PREF_RESULT, msg)
            done?.invoke(msg)
        }.apply { isDaemon = true }.start()
    }

    /** Blocking. Returns a human-readable result. */
    fun check(ctx: Context, manual: Boolean): String {
        Prefs.putLong(ctx, PREF_LAST, System.currentTimeMillis())
        val token = cleanToken(Prefs.getString(ctx, PREF_TOKEN))
        if (token.isEmpty()) return "No token yet: tap CREATE TOKEN, then paste it here"
        val c = open(UpdateCheck.LATEST_URL, token, "application/vnd.github+json")
        val code = c.responseCode
        if (code == 401) return "GitHub doesn't accept this token (expired, deleted, or only partly copied). Make a new one with CREATE TOKEN."
        if (code == 403) return "GitHub blocked the request (HTTP 403). Try again in an hour; if it repeats, make a new token."
        if (code == 404) {
            // Tell apart "token can't see the repo" from "repo has no stable release yet"
            val repoCode = open(UpdateCheck.REPO_URL, token, "application/vnd.github+json").responseCode
            return if (repoCode == 200) {
                "Token works ✓. No auto-installable build yet: add the AIRSCOPE_SIGNING_SEED secret on GitHub and run the build once."
            } else {
                "This token can't see ${UpdateCheck.REPO}. Make a new one and set Repository access → Only select repositories → ${UpdateCheck.REPO}."
            }
        }
        if (code != 200) return "GitHub returned HTTP $code"
        val rel = UpdateCheck.parse(c.inputStream.bufferedReader().use { it.readText() }) ?: return "Couldn't read the latest release"
        val installed = installedBuild(ctx)
        if (rel.build <= installed) return "Up to date (build $installed)"

        val file = download(ctx, rel, token) ?: return "Build ${rel.build} found but download failed"
        val problem = verify(ctx, file, rel.build)
        if (problem != null) {
            Notifier.update(ctx, "AirScope update can't install", problem)
            return problem
        }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val pi = PendingIntent.getActivity(
                ctx, 77,
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            Notifier.update(ctx, "AirScope build ${rel.build} is ready", "Tap to allow AirScope to install its own updates, then check again.", pi)
            return "Build ${rel.build} downloaded: allow \"Install unknown apps\" for AirScope"
        }
        if (Engine.running && !manual) {
            // Don't interrupt an active scan (installing restarts the app): ask first.
            val pi = PendingIntent.getBroadcast(
                ctx, 78,
                Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_INSTALL)
                    .putExtra("path", file.absolutePath).putExtra("build", rel.build),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            Notifier.update(ctx, "AirScope build ${rel.build} is ready", "Tap to install now (scanning restarts after the update).", pi)
            return "Build ${rel.build} ready: tap the notification to install"
        }
        install(ctx, file, rel.build)
        return "Installing build ${rel.build}…"
    }

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "AirScope-updater")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun download(ctx: Context, rel: UpdateCheck.Release, token: String): File? {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "AirScope-${rel.build}.apk")
        var url = if (token.isNotEmpty()) rel.apiAssetUrl else rel.browserUrl
        url ?: return null
        var c = open(url, token, "application/octet-stream")
        var hops = 0
        while (c.responseCode in 301..308 && hops++ < 5) {
            url = c.getHeaderField("Location") ?: return null
            // The redirect goes to signed storage: never forward the GitHub token there.
            c = open(url, "", "application/octet-stream")
        }
        if (c.responseCode != 200) return null
        c.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
        return out
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): List<String> {
        val si = info?.signingInfo ?: return emptyList()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs?.map { it.toCharsString() } ?: emptyList()
    }

    /** Same package, expected build, same signing key. Returns a problem description or null. */
    private fun verify(ctx: Context, file: File, build: Int): String? {
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "Downloaded file isn't a valid APK"
        if (archive.packageName != ctx.packageName) return "Downloaded APK is a different app (${archive.packageName})"
        if (archive.longVersionCode < build) return "Release build number doesn't match the APK"
        val mine = signers(pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
        val theirs = signers(archive)
        if (theirs.isEmpty() || mine.isEmpty() || theirs.last() != mine.last()) {
            return "Token works ✓. Build $build is signed with a different key than the AirScope on this phone, so Android " +
                "won't update it in place. One time only: uninstall AirScope, then install build $build from GitHub Releases " +
                "(RELEASES button). Updates are automatic after that."
        }
        return null
    }

    fun install(ctx: Context, file: File, build: Int) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS).putExtra("build", build)
            val pi = PendingIntent.getBroadcast(ctx, id, status, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
    }
}

/** Install results, "install now" taps, and the post-update notice. */
class UpdateReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STATUS = "com.k.airscope.UPDATE_STATUS"
        const val ACTION_INSTALL = "com.k.airscope.UPDATE_INSTALL"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Notifier.ensureChannels(ctx)
        when (intent.action) {
            ACTION_INSTALL -> {
                val path = intent.getStringExtra("path") ?: return
                val build = intent.getIntExtra("build", 0)
                val pending = goAsync()
                Thread {
                    try { Updater.install(ctx, File(path), build) } catch (e: Exception) {
                        Notifier.update(ctx, "AirScope update failed", e.message ?: "unknown error")
                    }
                    pending.finish()
                }.start()
            }
            ACTION_STATUS -> {
                val build = intent.getIntExtra("build", 0)
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            val pi = PendingIntent.getActivity(ctx, 79, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            Notifier.update(ctx, "Finish updating AirScope", "Tap to confirm installing build $build.", pi)
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit // MY_PACKAGE_REPLACED posts the notice
                    else -> Notifier.update(
                        ctx, "AirScope update didn't install",
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown installer error",
                    )
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val b = try { Updater.installedBuild(ctx) } catch (_: Exception) { 0L }
                val resume = Prefs.getBool(ctx, "was_running", false)
                Notifier.update(
                    ctx, "AirScope updated to build $b",
                    if (resume) "Tap to reopen and resume scanning." else "Tap to open.",
                )
                Updater.schedule(ctx)
            }
            Intent.ACTION_BOOT_COMPLETED -> Updater.schedule(ctx)
        }
    }
}

/** Periodic background update check (every ~6 h when online). */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { Prefs.putString(this, Updater.PREF_RESULT, Updater.check(applicationContext, false)) } catch (_: Exception) { }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
