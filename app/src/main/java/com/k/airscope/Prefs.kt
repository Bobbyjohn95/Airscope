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
        // "env_mode" replaces the old "env" setting: everyone starts on AUTO once.
        val mode = p.getString("env_mode", "AUTO")!!
        Engine.envAuto = mode == "AUTO"
        if (!Engine.envAuto) Engine.env = runCatching { Env.valueOf(mode) }.getOrDefault(Env.INDOOR)
        Engine.wifiOn = p.getBoolean("wifi", true)
        Engine.bleOn = p.getBoolean("ble", true)
        Engine.alprOn = p.getBoolean("alpr", true)
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
            .apply()
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
