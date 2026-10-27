package com.dante.smbtv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** ExoPlayer DataSource that streams (and seeks) a file straight from an SMB share. */
@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val file = Smb.file(dataSpec.uri.toString())
        val total = file.length()
        val pos = dataSpec.position
        if (pos > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        val raw: InputStream = file.openInputStream()
        try {
            skipFully(raw, pos)
        } catch (e: IOException) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
        stream = BufferedInputStream(raw, 512 * 1024)

        val avail = total - pos
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) avail else minOf(dataSpec.length, avail)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(remaining, length.toLong()).toInt()
        val n = stream!!.read(buffer, offset, toRead)
        if (n == -1) throw EOFException()
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            stream?.close()
        } catch (_: Exception) {
        } finally {
            stream = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) {
                if (s.read() == -1) throw EOFException()
                left--
            } else {
                left -= k
            }
        }
    }
}
