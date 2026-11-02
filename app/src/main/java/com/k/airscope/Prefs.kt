package com.k.airscope

import android.content.Context
import java.security.SecureRandom

/** Persists "marked as mine" devices and the HUD access key. */
object Prefs {
    private const val FILE = "airscope"
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        ScanState.store.trustedKeys += p.getStringSet("trusted", emptySet()) ?: emptySet()
        val saved: String? = p.getString("hud_token", null)
        val token: String = if (!saved.isNullOrEmpty()) {
            saved
        } else {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            val fresh = (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            p.edit().putString("hud_token", fresh).apply()
            fresh
        }
        ScanState.hudToken = token
    }

    fun saveTrusted(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putStringSet("trusted", HashSet(ScanState.store.trustedKeys))
            .apply()
    }
}
