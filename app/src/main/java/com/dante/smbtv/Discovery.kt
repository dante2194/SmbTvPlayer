package com.dante.smbtv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Finds SMB servers by probing TCP/445 on every local IPv4 subnet. */
object Discovery {

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun toIp(i: Int): String =
        "${(i ushr 24) and 0xFF}.${(i ushr 16) and 0xFF}.${(i ushr 8) and 0xFF}.${i and 0xFF}"

    private fun candidates(): List<String> {
        val own = HashSet<String>()
        val out = LinkedHashSet<String>()
        val nis = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
        } catch (e: Exception) {
            null
        } ?: emptyList()

        for (ni in nis) {
            try {
                if (!ni.isUp || ni.isLoopback || ni.isPointToPoint) continue
            } catch (e: Exception) {
                continue
            }
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                if (!a.isSiteLocalAddress) continue
                val ip = toInt(a.address)
                own.add(toIp(ip))
                var prefix = ia.networkPrefixLength.toInt()
                if (prefix < 22) prefix = 24
                if (prefix > 30) continue
                val size = 1 shl (32 - prefix)
                val net = ip and (-1 shl (32 - prefix))
                for (h in 1 until size - 1) out.add(toIp(net + h))
            }
        }
        out.removeAll(own)
        return out.toList()
    }

    private fun probe(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, 445), 700); true }
    } catch (e: Exception) {
        false
    }

    suspend fun scan(onFound: (String) -> Unit, onProgress: (Int, Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val ips = candidates()
            withContext(Dispatchers.Main) { onProgress(0, ips.size) }
            val done = AtomicInteger(0)
            val gate = Semaphore(64)
            coroutineScope {
                for (ip in ips) {
                    launch {
                        gate.withPermit {
                            if (probe(ip)) withContext(Dispatchers.Main) { onFound(ip) }
                        }
                        val d = done.incrementAndGet()
                        if (d % 16 == 0 || d == ips.size) {
                            withContext(Dispatchers.Main) { onProgress(d, ips.size) }
                        }
                    }
                }
            }
        }

    /** Reverse-DNS a friendly name (router hostnames); falls back to the IP. */
    suspend fun resolveName(ip: String): String {
        val job = CoroutineScope(Dispatchers.IO).async {
            try { InetAddress.getByName(ip).canonicalHostName } catch (e: Exception) { ip }
        }
        val r = withTimeoutOrNull(1500) { job.await() } ?: ip
        if (r == ip || r.matches(Regex("[0-9.]+"))) return ip
        return r.substringBefore('.')
    }
}
