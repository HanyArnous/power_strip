package com.powerstrip.app

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Checks the reports flow: parsing /api/report totals + peaks and
 * /api/history power points. Run by `gradlew testDebugUnitTest`.
 */
class HistoryTest {

    private fun serve(mapping: Map<String, String>): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, responseBody) in mapping) {
            server.createContext(path) { exchange ->
                val bytes = responseBody.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    private fun reportJson() = JSONObject()
        .put("mac", "88D039943EA8").put("period", "today").put("bucket", "raw")
        .put("from", 1700000000).put("to", 1700086400)
        .put("total_kwh", 0.05)
        .put("outlets", JSONObject()
            .put("1", JSONObject().put("kwh", 0.04).put("max_w", 50.0)
                .put("max_t", 1700000240).put("min_w", 10.0)
                .put("avg_w", 30.0).put("samples", 5))
            .put("2", JSONObject().put("kwh", 0.01).put("max_w", 5.0)
                .put("max_t", JSONObject.NULL).put("min_w", 0.0)
                .put("avg_w", 1.0).put("samples", 5)))
        .put("peak", JSONObject().put("outlet", 1).put("power_w", 50.0).put("ts", 1700000240))
        .toString()

    @Test
    fun reportParsesTotalsAndPeaks() = runBlocking {
        val (server, base) = serve(mapOf("/api/report" to reportJson()))
        try {
            val r = Api.report(base, "", "88D039943EA8", "today")
            assertEquals(0.05, r.totalKwh, 0.0)
            assertEquals(1, r.peakOutlet)
            assertEquals(50.0, r.peakW, 0.0)
            assertEquals(1700000240L, r.peakT)
            assertEquals(2, r.outlets.size)
            assertEquals(0.04, r.outlets[0].kwh, 0.0)
            assertEquals(10, r.totalSamples)
            assertEquals(0.0, r.minW, 0.0)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun historyParsesPowerPoints() = runBlocking {
        val body = JSONObject()
            .put("mac", "88D039943EA8").put("outlet", 1).put("bucket", "raw")
            .put("points", org.json.JSONArray()
                .put(JSONObject().put("t", 1700000000).put("power_w", 10.0))
                .put(JSONObject().put("t", 1700000060).put("power_w", 20.0)))
            .toString()
        val (server, base) = serve(mapOf("/api/history" to body))
        try {
            val pts = Api.history(base, "", "88D039943EA8", 1, "today")
            assertEquals(2, pts.size)
            assertEquals(1700000000L, pts[0].t)
            assertEquals(20.0, pts[1].powerW, 0.0)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun emptyReportStaysConsistent() = runBlocking {
        val body = JSONObject()
            .put("mac", "88D039943EA8").put("period", "today")
            .put("total_kwh", 0.0).put("outlets", JSONObject())
            .put("peak", JSONObject().put("outlet", 0).put("power_w", 0.0))
            .toString()
        val (server, base) = serve(mapOf("/api/report" to body))
        try {
            val r = Api.report(base, "", "88D039943EA8", "today")
            assertTrue(r.outlets.isEmpty())
            assertEquals(0, r.totalSamples)
        } finally {
            server.stop(0)
        }
    }
}
