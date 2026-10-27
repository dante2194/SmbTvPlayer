package com.dante.smbtv

import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import java.util.Locale

enum class Kind { SERVER, SHARE, FOLDER, VIDEO }

data class Entry(
    val name: String,
    val url: String,
    val kind: Kind,
    val size: Long = 0L,
    val subtitle: String = ""
)

data class Server(val host: String, val name: String, val saved: Boolean)
data class SubFile(val name: String, val url: String)
data class PItem(val name: String, val url: String)

/** Hand-off between the browser and the player (avoids huge Intent extras). */
object Playlist {
    @Volatile var items: List<PItem> = emptyList()
    @Volatile var index: Int = 0
    @Volatile var subs: List<SubFile> = emptyList()
}

val VIDEO_EXT = setOf(
    "mkv", "mp4", "m4v", "mov", "avi", "wmv", "flv", "webm", "ts", "m2ts",
    "mts", "mpg", "mpeg", "3gp", "ogv", "vob", "divx", "asf"
)
val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")

fun hostOf(url: String): String =
    url.removePrefix("smb://").substringBefore('/').substringAfter('@').substringBefore(':')

/** Parent folder URL, or null when [url] is a host root (→ server list). */
fun parentOf(url: String): String? {
    val s = url.trimEnd('/')
    val rest = s.removePrefix("smb://")
    if (!rest.contains('/')) return null
    return s.substringBeforeLast('/') + "/"
}

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    b >= (1L shl 20) -> String.format(Locale.US, "%.0f MB", b / 1048576.0)
    b >= (1L shl 10) -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var si = i
            while (si < a.length && a[si].isDigit()) si++
            var sj = j
            while (sj < b.length && b[sj].isDigit()) sj++
            val na = a.substring(i, si).trimStart('0')
            val nb = b.substring(j, sj).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = si
            j = sj
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

fun isAuthError(e: Throwable): Boolean {
    if (e is SmbAuthException) return true
    if (e is SmbException) {
        val m = e.message ?: ""
        return m.contains("denied", true) || m.contains("logon", true) ||
            m.contains("password", true) || m.contains("STATUS_ACCOUNT", true)
    }
    return false
}
