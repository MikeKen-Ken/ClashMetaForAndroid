package com.github.kr328.clash.share

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.github.kr328.clash.common.net.safeNetworkInterfaces
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class RuntimeLanShareInfo(
    val urls: List<String>,
    val primaryUrl: String,
    val ttlSecs: Long,
)

object RuntimeLanShareServer {
    private const val ttlSecs: Long = 600
    private const val pathPrefix = "/share"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var active: ActiveShare? = null

    suspend fun stop() {
        val old = synchronized(this) {
            active.also { active = null }
        } ?: return

        old.job.cancel()
        old.server.close()
        old.job.cancelAndJoin()
    }

    suspend fun start(runtimeYaml: String): RuntimeLanShareInfo {
        stop()

        val token = UUID.randomUUID().toString().replace("-", "")
        val server = ServerSocket(0).apply {
            reuseAddress = true
            soTimeout = 1000
        }
        val port = server.localPort
        val expectedPath = "$pathPrefix/$token/runtime.yaml"
        val urls = collectLanUrls(port, token).toMutableList()
        val primary = pickPrimaryUrl(urls) ?: "http://127.0.0.1:$port$expectedPath"
        if (!urls.contains(primary)) urls += primary

        val share = ActiveShare(server)
        val job = scope.launch {
            val timeoutJob = launch {
                delay(ttlSecs * 1000)
                synchronized(this@RuntimeLanShareServer) {
                    if (active === share) {
                        active = null
                    }
                }
                server.close()
            }

            try {
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }

                    val consumed = handleRequest(socket, expectedPath, runtimeYaml)
                    if (consumed) {
                        synchronized(this@RuntimeLanShareServer) {
                            if (active === share) {
                                active = null
                            }
                        }
                        server.close()
                        break
                    }
                }
            } catch (_: CancellationException) {
            } finally {
                timeoutJob.cancel()
                runCatching { server.close() }
            }
        }

        share.job = job
        synchronized(this) {
            active = share
        }

        return RuntimeLanShareInfo(
            urls = urls.distinct(),
            primaryUrl = primary,
            ttlSecs = ttlSecs,
        )
    }

    private fun handleRequest(socket: Socket, expectedPath: String, yaml: String): Boolean {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val request = reader.readLine().orEmpty()
            val parts = request.split(' ')
            val path = parts.getOrNull(1).orEmpty()
            val ok = parts.firstOrNull() == "GET" && path == expectedPath
            val output = s.getOutputStream()

            if (!ok) {
                output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return false
            }

            val body = yaml.toByteArray(Charsets.UTF_8)
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/yaml; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.write(body)
            output.flush()
            return true
        }
    }

    private fun collectLanUrls(port: Int, token: String): List<String> {
        val addresses = mutableListOf<String>()
        val networks = safeNetworkInterfaces()
        while (networks.hasMoreElements()) {
            val network = networks.nextElement()
            if (!network.isUp || network.isLoopback || network.isVirtual) continue

            val inetAddresses = network.inetAddresses
            while (inetAddresses.hasMoreElements()) {
                val addr = inetAddresses.nextElement()
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                addresses += "http://${addr.hostAddress}:$port$pathPrefix/$token/runtime.yaml"
            }
        }
        return addresses.distinct().sortedWith { a, b ->
            val pa = priority(a)
            val pb = priority(b)
            if (pa != pb) pa.compareTo(pb) else a.compareTo(b)
        }
    }

    private fun priority(url: String): Int {
        val host = url.removePrefix("http://").substringBefore(':')
        return when {
            host.startsWith("192.168.") -> 0
            host.startsWith("10.") -> 1
            host.startsWith("172.") -> 2
            else -> 3
        }
    }

    private fun pickPrimaryUrl(urls: List<String>): String? {
        return urls.minByOrNull { priority(it) }
    }

    private class ActiveShare(
        val server: ServerSocket,
    ) {
        lateinit var job: Job
    }
}
