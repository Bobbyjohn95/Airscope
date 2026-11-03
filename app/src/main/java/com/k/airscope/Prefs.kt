package com.k.airscope

import android.content.Context
import java.security.SecureRandom

/** Persistent settings. Tokens stay on the phone (allowBackup=false); nothing is synced anywhere. */
object Prefs {
    private const val FILE = "airscope"
    private var loaded = false

    fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = p(ctx)
        Engine.store.trustedKeys += p.getStringSet("trusted", emptySet()) ?: emptySet()
        Engine.store.watchRules = (p.getString("watch_rules", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        for (e in p.getStringSet("priorities", emptySet()) ?: emptySet()) {
            val k = e.substringBeforeLast('='); val v = e.substringAfterLast('=').toIntOrNull() ?: continue
            Engine.store.priorities[k] = v
        }
        // "env_mode" replaces the old "env" setting: everyone starts on AUTO once.
        val mode = p.getString("env_mode", "AUTO")!!
        Engine.envAuto = mode == "AUTO"
        if (!Engine.envAuto) Engine.env = runCatching { Env.valueOf(mode) }.getOrDefault(Env.INDOOR)
        Engine.wifiOn = p.getBoolean("wifi", true)
        Engine.bleOn = p.getBoolean("ble", true)
        Engine.alprOn = p.getBoolean("alpr", true)
        Engine.lanOn = p.getBoolean("lan_names", true)
        Engine.radiusM = p.getInt("radius_m", 0)
        Heuristics.alertLevel = p.getInt("alert_level", 1)
        val saved: String? = p.getString("hud_token", null)
        Engine.hudToken = if (!saved.isNullOrEmpty()) saved else {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("").also {
                p.edit().putString("hud_token", it).apply()
            }
        }
    }

    fun save(ctx: Context) {
        p(ctx).edit()
            .putString("env_mode", if (Engine.envAuto) "AUTO" else Engine.env.name)
            .putBoolean("wifi", Engine.wifiOn)
            .putBoolean("ble", Engine.bleOn)
            .putBoolean("alpr", Engine.alprOn)
            .putBoolean("lan_names", Engine.lanOn)
            .putInt("radius_m", Engine.radiusM)
            .putInt("alert_level", Heuristics.alertLevel)
            .apply()
    }

    fun saveWatchRules(ctx: Context, rules: List<String>) {
        p(ctx).edit().putString("watch_rules", rules.joinToString("\n")).apply()
    }

    fun savePriorities(ctx: Context, m: Map<String, Int>) {
        p(ctx).edit().putStringSet("priorities", m.map { "${it.key}=${it.value}" }.toHashSet()).apply()
    }

    fun saveTrusted(ctx: Context, keys: Set<String>) {
        p(ctx).edit().putStringSet("trusted", HashSet(keys)).apply()
    }

    fun getString(ctx: Context, key: String, def: String = ""): String = p(ctx).getString(key, def) ?: def
    fun putString(ctx: Context, key: String, v: String) = p(ctx).edit().putString(key, v).apply()
    fun getBool(ctx: Context, key: String, def: Boolean) = p(ctx).getBoolean(key, def)
    fun putBool(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()
    fun getLong(ctx: Context, key: String) = p(ctx).getLong(key, 0L)
    fun putLong(ctx: Context, key: String, v: Long) = p(ctx).edit().putLong(key, v).apply()
}
