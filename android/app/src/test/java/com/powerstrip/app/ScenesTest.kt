package com.powerstrip.app

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Checks the scenes flow: list parsing, create/update bodies, run and delete.
 * Run by `gradlew testDebugUnitTest`.
 */
class ScenesTest {

    private object Captured {
        var method = ""
        var path = ""
        var body = ""
    }

    private fun sceneJson(id: String = "abc123") = JSONObject()
        .put("id", id).put("name", "Heater guard").put("enabled", true)
        .put("trigger", JSONObject().put("type", "threshold").put("mac", "88D039943EA8")
            .put("outlet", 2).put("direction", "above").put("watts", 1000.0).put("for_s", 20.0))
        .put("actions", org.json.JSONArray().put(
            JSONObject().put("mac", "88D039943EA8").put("outlet", 2).put("on", false)))
        .put("last_fired", JSONObject.NULL)
        .toString()

    private fun serve(): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            Captured.method = exchange.requestMethod
            Captured.path = exchange.requestURI.path
            Captured.body = if (exchange.requestMethod == "GET") "" else
                exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = when {
                Captured.path == "/api/scenes" && Captured.method == "GET" ->
                    JSONObject().put("scenes", org.json.JSONArray().put(JSONObject(sceneJson()))).toString()
                Captured.path == "/api/scenes" ->
                    JSONObject().put("ok", true).put("scene", JSONObject(sceneJson("new1"))).toString()
                Captured.path.endsWith("/run") ->
                    JSONObject().put("ok", true).put("fired", true).toString()
                Captured.method == "DELETE" ->
                    JSONObject().put("ok", true).toString()
                else ->
                    JSONObject().put("ok", true).put("scene", JSONObject(sceneJson("abc123"))).toString()
            }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun listParsesScenes() = runBlocking {
        val (server, base) = serve()
        try {
            val scenes = Api.scenesList(base, "")
            assertEquals(1, scenes.size)
            assertEquals("Heater guard", scenes[0].name)
            assertEquals("threshold", scenes[0].trigger.type)
            assertEquals(1000.0, scenes[0].trigger.watts, 0.0)
            assertEquals(1, scenes[0].actions.size)
            assertTrue(scenes[0].enabled)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun createPostsNormalizedBody() = runBlocking {
        val (server, base) = serve()
        try {
            val draft = Scene(
                id = "", name = "Night off", enabled = true,
                trigger = SceneTrigger(type = "schedule", time = "23:30", days = listOf(0, 1, 2, 3, 4)),
                actions = listOf(SceneAction("88D039943EA8", 0, false)),
                lastFired = null,
            )
            val created = Api.saveScene(base, "", draft)
            assertEquals("POST", Captured.method)
            assertEquals("/api/scenes", Captured.path)
            val sent = JSONObject(Captured.body)
            assertEquals("Night off", sent.getString("name"))
            assertEquals("23:30", sent.getJSONObject("trigger").getString("time"))
            assertEquals("new1", created.id)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun updateUsesPutAndRunPosts() = runBlocking {
        val (server, base) = serve()
        try {
            val existing = Scene(
                id = "abc123", name = "Heater guard", enabled = false,
                trigger = SceneTrigger(type = "manual"),
                actions = listOf(SceneAction("88D039943EA8", 1, true)),
                lastFired = null,
            )
            Api.saveScene(base, "", existing)
            assertEquals("PUT", Captured.method)
            assertEquals("/api/scenes/abc123", Captured.path)
            Api.runScene(base, "", "abc123")
            assertEquals("POST", Captured.method)
            assertEquals("/api/scenes/abc123/run", Captured.path)
            Api.deleteScene(base, "", "abc123")
            assertEquals("DELETE", Captured.method)
        } finally {
            server.stop(0)
        }
    }
}
