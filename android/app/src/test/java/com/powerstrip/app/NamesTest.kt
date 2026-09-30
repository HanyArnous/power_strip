package com.powerstrip.app

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Checks the custom-names flow: the client sends mac/strip/outlets to
 * POST /api/names, and parses the `name` fields back from /api/state.
 */
class NamesTest {

    private fun serve(mapping: Map<String, String>): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, responseBody) in mapping) {
            server.createContext(path) { exchange ->
                if (exchange.requestMethod == "POST") {
                    CapturedHolder.lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                }
                val bytes = responseBody.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    private object CapturedHolder {
        var lastBody = ""
    }

    @Test
    fun setNamesSendsMacStripAndOutlets() = runBlocking {
        val (server, base) = serve(mapOf("/api/names" to """{"ok":true,"names":{"strip":"Salon","outlets":{"1":"TV"}}}"""))
        try {
            Api.setNames(base, "", "88D039943EA8", strip = "Salon", outlets = mapOf(1 to "TV"))
            val sent = JSONObject(CapturedHolder.lastBody)
            assertEquals("88D039943EA8", sent.getString("mac"))
            assertEquals("Salon", sent.getString("strip"))
            assertEquals("TV", sent.getJSONObject("outlets").getString("1"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun setNamesStripOnlyOmitsOutlets() = runBlocking {
        val (server, base) = serve(mapOf("/api/names" to """{"ok":true,"names":{"strip":"Salon","outlets":{}}}"""))
        try {
            Api.setNames(base, "", "88D039943EA8", strip = "Salon")
            val sent = JSONObject(CapturedHolder.lastBody)
            assertTrue(!sent.has("outlets"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun snapshotParsesCustomNames() = runBlocking {
        val state = JSONObject()
            .put("local_ip", "192.168.1.112")
            .put("devices", org.json.JSONArray().put(
                JSONObject()
                    .put("mac", "88D039943EA8")
                    .put("name", "Salon")
                    .put("model", "lgutap").put("fw", "1.0")
                    .put("online", true).put("on", true)
                    .put("power_w", 10.0).put("energy_kwh", 1.0)
                    .put("outlets", org.json.JSONArray().put(
                        JSONObject().put("n", 1).put("on", true)
                            .put("power_w", 10.0).put("energy_kwh", 1.0)
                            .put("temp_c", 30).put("name", "TV")
                    ))
            )).toString()
        val (server, base) = serve(mapOf("/api/state" to state))
        try {
            val snap = Api.snapshot(base, "")
            assertEquals("Salon", snap.strips[0].name)
            assertEquals("TV", snap.strips[0].outlet(1)?.name)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun serverErrorsBecomeServerExceptions() = runBlocking {
        val (server, base) = serve(mapOf("/api/names" to """{"ok":false,"error":"bad mac"}"""))
        try {
            var failed = ""
            try {
                Api.setNames(base, "", "??", strip = "x")
            } catch (e: ServerException) {
                failed = e.message ?: ""
            }
            assertEquals("bad mac", failed)
        } finally {
            server.stop(0)
        }
    }
}
