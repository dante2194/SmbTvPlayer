package com.dante.smbtv

import android.content.Context
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.IOException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Shared SMB plumbing: per-host credentials (saved → guest → anonymous). */
object Smb {
    private var app: Context? = null
    private val working = ConcurrentHashMap<String, CIFSContext>()

    fun init(c: Context) { app = c.applicationContext }

    private val base: CIFSContext by lazy {
        val p = Properties()
        p.setProperty("jcifs.smb.client.responseTimeout", "20000")
        p.setProperty("jcifs.smb.client.connTimeout", "10000")
        p.setProperty("jcifs.smb.client.soTimeout", "35000")
        p.setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
        BaseContext(PropertyConfiguration(p))
    }

    private fun candidates(host: String): List<CIFSContext> {
        val saved = app?.let { Store.creds(it, host) }
        return if (saved != null) {
            listOf(
                base.withCredentials(
                    NtlmPasswordAuthenticator(saved.domain.ifBlank { null }, saved.user, saved.pass)
                )
            )
        } else {
            listOf(base.withCredentials(NtlmPasswordAuthenticator(null, "guest", "")), base.withAnonymousCredentials())
        }
    }

    fun invalidate(host: String) { working.remove(host) }

    /** Lists a directory (or the shares of a host). Throws an auth error if login is needed. */
    fun list(url: String): List<SmbFile> {
        val host = hostOf(url)
        val cands = working[host]?.let { listOf(it) } ?: candidates(host)
        var last: Exception? = null
        for (ctx in cands) {
            try {
                val files = SmbFile(url, ctx).listFiles()
                working[host] = ctx
                return files.toList()
            } catch (e: Exception) {
                last = e
                if (isAuthError(e)) working.remove(host) else throw e
            }
        }
        throw last ?: IOException("Unable to list $url")
    }

    fun file(url: String): SmbFile {
        val host = hostOf(url)
        val ctx = working[host] ?: candidates(host).first()
        return SmbFile(url, ctx)
    }
}
