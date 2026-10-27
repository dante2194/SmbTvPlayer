package com.dante.smbtv

import android.content.Context

data class Creds(val domain: String, val user: String, val pass: String)

/** Tiny SharedPreferences wrapper: saved servers, logins, last server, resume positions. */
object Store {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("smbtv", Context.MODE_PRIVATE)

    fun savedServers(c: Context): Set<String> =
        sp(c).getStringSet("servers", emptySet())?.toSet() ?: emptySet()

    fun addServer(c: Context, host: String) {
        sp(c).edit().putStringSet("servers", savedServers(c) + host).apply()
    }

    fun removeServer(c: Context, host: String) {
        sp(c).edit().putStringSet("servers", savedServers(c) - host).remove("cred_$host").apply()
    }

    fun lastServer(c: Context): String? = sp(c).getString("last", null)
    fun setLastServer(c: Context, host: String) { sp(c).edit().putString("last", host).apply() }

    fun creds(c: Context, host: String): Creds? {
        val parts = sp(c).getString("cred_$host", null)?.split('\u0001') ?: return null
        return if (parts.size == 3) Creds(parts[0], parts[1], parts[2]) else null
    }

    fun saveCreds(c: Context, host: String, cr: Creds) {
        sp(c).edit().putString("cred_$host", listOf(cr.domain, cr.user, cr.pass).joinToString("\u0001")).apply()
    }

    fun clearCreds(c: Context, host: String) { sp(c).edit().remove("cred_$host").apply() }

    fun resume(c: Context, url: String): Pair<Long, Long>? {
        val s = sp(c).getString("r:$url", null) ?: return null
        val p = s.split('|')
        if (p.size != 2) return null
        val pos = p[0].toLongOrNull() ?: return null
        val dur = p[1].toLongOrNull() ?: return null
        return pos to dur
    }

    fun saveResume(c: Context, url: String, pos: Long, dur: Long) {
        sp(c).edit().putString("r:$url", "$pos|$dur").apply()
    }

    fun clearResume(c: Context, url: String) { sp(c).edit().remove("r:$url").apply() }
}
