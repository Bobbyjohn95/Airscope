package com.k.airscope

/** Pure parts of the self-updater: where to look and how to read GitHub's answer. */
object UpdateCheck {
    const val OWNER = "Bobbyjohn95"
    const val REPO = "Airscope"
    const val ASSET = "AirScope.apk"
    const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    class Release(val tag: String, val build: Int, val apiAssetUrl: String?, val browserUrl: String?, val htmlUrl: String?)

    /** Tags look like "build-12" (the GitHub Actions run number = the app's versionCode). */
    fun buildNumber(tag: String): Int? = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    fun parse(json: String): Release? {
        val m = MiniJson.parse(json) as? Map<*, *> ?: return null
        val tag = m["tag_name"] as? String ?: return null
        val build = buildNumber(tag) ?: return null
        val asset = (m["assets"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull { it["name"] == ASSET }
        return Release(tag, build, asset?.get("url") as? String, asset?.get("browser_download_url") as? String, m["html_url"] as? String)
    }
}
