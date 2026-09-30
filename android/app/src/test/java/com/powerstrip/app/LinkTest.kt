package com.powerstrip.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Checks the in-app strip linker (Link tab): input validation happens before any
 * network use, and the two-command setup flow works wire-for-wire against a fake
 * strip setup service. Run by `gradlew testDebugUnitTest`.
 */
class LinkTest {

    @Test
    fun ipv4Validation() {
        assertTrue(Api.isIpv4("192.168.1.14"))
        assertTrue(Api.isIpv4("0.0.0.0"))
        assertTrue(Api.isIpv4("255.255.255.255"))
        assertFalse(Api.isIpv4(""))
        assertFalse(Api.isIpv4("nope"))
        assertFalse(Api.isIpv4("1.2.3"))
        assertFalse(Api.isIpv4("1.2.3.256"))
        assertFalse(Api.isIpv4("1.2.3.4.5"))
        assertFalse(Api.isIpv4("::1"))
    }

    @Test
    fun badInputNeverTouchesTheNetwork() = runBlocking {
        assertEquals("ssid_required", Api.provisionLink("127.0.0.1", 1, "192.168.1.14", "", "p").error)
        assertEquals("bad_ip", Api.provisionLink("127.0.0.1", 1, "not-an-ip", "s", "p").error)
        assertEquals("bad_ip", Api.provisionLink("127.0.0.1", 1, "  ", "s", "p").error)
        assertEquals("bad_chars", Api.provisionLink("127.0.0.1", 1, "192.168.1.14", "a:b", "p").error)
        assertEquals("bad_chars", Api.provisionLink("127.0.0.1", 1, "192.168.1.14", "s", "p:x").error)
    }

    @Test
    fun unreachableServiceGivesUnreachableNotACrash() = runBlocking {
        // Port 1 is (practically) never open: connect must refuse fast.
        val r = Api.provisionLink("127.0.0.1", 1, "192.168.1.14", "s", "p")
        assertFalse(r.ok)
        assertEquals("unreachable", r.error)
        assertFalse(Api.probeSetup("127.0.0.1", 1))
    }

    private fun fakeSetup(answers: Map<String, String>): Pair<ServerSocket, Int> {
        val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            repeat(10) {
                try {
                    srv.accept().use { s ->
                        s.soTimeout = 5000
                        val line = s.getInputStream().bufferedReader().readLine()?.trim().orEmpty()
                        val answer = answers.entries.firstOrNull { line.startsWith(it.key) }?.value
                            ?: "up:unknown"
                        s.getOutputStream().write("$answer\r\n".toByteArray(Charsets.UTF_8))
                        s.getOutputStream().flush()
                    }
                } catch (e: Exception) {
                    return@repeat
                }
            }
        }
        return srv to srv.localPort
    }

    @Test
    fun fullLinkRoundTrip() = runBlocking {
        val (srv, port) = fakeSetup(mapOf("up:ip:" to "up:ip:ip_ok", "up:connect:" to "up:connect:connect_ok"))
        try {
            // Explicit factory must be honored exactly like the default path.
            val factory = javax.net.SocketFactory.getDefault()
            assertTrue(Api.probeSetup("127.0.0.1", port, factory))
            val r = Api.provisionLink("127.0.0.1", port, "192.168.1.14", "HomeWiFi", "secret123", factory)
            assertTrue("result: $r", r.ok)
            assertEquals(listOf("up:ip:ip_ok", "up:connect:connect_ok"), r.steps)
        } finally {
            srv.close()
        }
    }

    @Test
    fun wrongAnswerIsReported() = runBlocking {
        val (srv, port) = fakeSetup(emptyMap())
        try {
            val r = Api.provisionLink("127.0.0.1", port, "192.168.1.14", "s", "p")
            assertFalse(r.ok)
            assertEquals("bad_answer", r.error)
        } finally {
            srv.close()
        }
    }

    @Test
    fun straySpacesAroundSsidAreTrimmedBeforeSending() = runBlocking {
        val received = mutableListOf<String>()
        val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            repeat(4) {
                try {
                    srv.accept().use { s ->
                        s.soTimeout = 5000
                        val line = s.getInputStream().bufferedReader().readLine()?.trim().orEmpty()
                        synchronized(received) { received.add(line) }
                        val answer = if (line.startsWith("up:ip:")) "up:ip:ip_ok"
                        else if (line.startsWith("up:connect:")) "up:connect:connect_ok"
                        else "up:unknown"
                        s.getOutputStream().write("$answer\r\n".toByteArray(Charsets.UTF_8))
                        s.getOutputStream().flush()
                    }
                } catch (e: Exception) {
                    return@repeat
                }
            }
        }
        try {
            val r = Api.provisionLink("127.0.0.1", srv.localPort, "192.168.1.14", "  HomeWiFi  ", "secret123")
            assertTrue("result: $r", r.ok)
            assertTrue("sent: $received", received.contains("up:connect:HomeWiFi:secret123"))
        } finally {
            srv.close()
        }
    }
}
