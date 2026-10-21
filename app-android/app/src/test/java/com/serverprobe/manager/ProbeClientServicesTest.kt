package com.serverprobe.manager

import com.serverprobe.manager.data.probe.ProbeClient
import com.serverprobe.manager.data.probe.ProbeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProbeClientServicesTest {

    @Test
    fun parsesServicesListFromProbeResponse() = runBlocking {
        val body = """
            {"services":[
              {"unit":"nginx.service","load":"loaded","active":"active","sub":"running","description":"A high performance web server"},
              {"unit":"mysql.service","load":"loaded","active":"failed","sub":"failed","description":"MySQL Community Server"}
            ]}
        """.trimIndent()
        var authHeader = ""
        val (server, serverThread) = serveOnce("200 OK", body) { headers ->
            authHeader = headers["Authorization"].orEmpty()
        }
        try {
            val client = ProbeClient(
                host = "127.0.0.1",
                port = server.localPort,
                token = "test-token",
                useTls = false,
            )
            val list = client.services()
            serverThread.join(2000)
            assertEquals("Bearer test-token", authHeader)
            assertEquals(2, list.size)
            assertEquals("nginx.service", list[0].unit)
            assertEquals("active", list[0].active)
            assertEquals("MySQL Community Server", list[1].description)
            assertTrue(list[1].active == "failed")
        } finally {
            server.close()
        }
    }

    @Test
    fun surfacesProbeErrorBodyOnHttpFailure() = runBlocking {
        val body = """{"error":"systemctl list-units failed: System has not been booted with systemd"}"""
        val (server, _) = serveOnce("500 Internal Server Error", body)
        try {
            val client = ProbeClient(
                host = "127.0.0.1",
                port = server.localPort,
                token = "test-token",
                useTls = false,
            )
            try {
                client.services()
                fail("expected ProbeException.Http")
            } catch (e: ProbeException.Http) {
                assertEquals(500, e.code)
                val msg = e.message.orEmpty()
                assertTrue("message should carry probe detail, was: $msg", msg.contains("systemd"))
            }
        } finally {
            server.close()
        }
    }

    /** 起一个只处理一次请求的极简 HTTP 服务，返回 (server, thread)。 */
    private fun serveOnce(
        statusLine: String,
        body: String,
        onHeaders: (Map<String, String>) -> Unit = {},
    ): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            server.accept().use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
                    }
                    line = reader.readLine()
                }
                onHeaders(headers)
                val bytes = body.toByteArray()
                val out = sock.getOutputStream()
                out.write(
                    (
                        "HTTP/1.1 $statusLine\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                out.write(bytes)
                out.flush()
            }
        }
        return server to worker
    }
}
