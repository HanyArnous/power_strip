package com.powerstrip.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.SocketFactory

data class Outlet(
    val n: Int,
    val on: Boolean,
    val powerW: Double,
    val energyKwh: Double,
    val tempC: Int,
    val name: String = "",
)

data class Strip(
    val mac: String,
    val name: String,
    val model: String,
    val fw: String,
    val online: Boolean,
    val on: Boolean,
    val powerW: Double,
    val energyKwh: Double,
    val voltage: Double?,
    val currentA: Double?,
    val rssi: Int?,
    val outlets: List<Outlet>,
)

data class Snapshot(val serverIp: String, val strips: List<Strip>)

/** Outlet by its number (1..4) - never by list position. */
fun Strip.outletOn(n: Int): Boolean = outlets.firstOrNull { it.n == n }?.on ?: false

fun Strip.outlet(n: Int): Outlet? = outlets.firstOrNull { it.n == n }

class ServerException(message: String) : Exception(message)

/** Outcome of talking to the strip's setup service. `error` is a stable code
 *  (ssid_required, bad_ip, bad_chars, unreachable, bad_answer) the UI translates. */
data class ProvisionResult(
    val ok: Boolean,
    val steps: List<String>,
    val error: String?,
)

data class ReportOutlet(
    val n: Int,
    val name: String,
    val kwh: Double,
    val maxW: Double,
    val maxT: Long?,
    val minW: Double,
    val avgW: Double,
    val samples: Int,
)

data class PowerReport(
    val mac: String,
    val period: String,
    val totalKwh: Double,
    val peakOutlet: Int,
    val peakW: Double,
    val peakT: Long?,
    val minW: Double,
    val avgW: Double,
    val outlets: List<ReportOutlet>,
    val totalSamples: Int,
)

data class HistoryPoint(val t: Long, val powerW: Double)

data class SceneTrigger(
    val type: String,
    val mac: String = "",
    val outlet: Int = 1,
    val direction: String = "above",
    val watts: Double = 0.0,
    val forS: Double = 10.0,
    val time: String = "07:00",
    val days: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6),
)

data class SceneAction(val mac: String, val outlet: Int, val on: Boolean)

data class Scene(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val trigger: SceneTrigger,
    val actions: List<SceneAction>,
    val lastFired: Long?,
)

/** Talks to the power-strip.py JSON API: GET /api/state, POST /api/onoff. */
object Api {

    suspend fun snapshot(base: String, token: String): Snapshot = withContext(Dispatchers.IO) {
        val root = JSONObject(request("$base/api/state", null, token))
        val array = root.optJSONArray("devices")
        val strips = (0 until (array?.length() ?: 0)).map { parseStrip(array!!.getJSONObject(it)) }
        Snapshot(root.optString("local_ip"), strips)
    }

    suspend fun setOutlet(base: String, token: String, mac: String, outlet: Int, on: Boolean) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("mac", mac)
            .put("outlet", outlet)
            .put("on", on)
            .toString()
        val response = JSONObject(request("$base/api/onoff", body, token))
        if (!response.optBoolean("ok")) {
            throw ServerException(response.optString("error").ifBlank { "command failed" })
        }
    }

    suspend fun setNames(
        base: String,
        token: String,
        mac: String,
        strip: String? = null,
        outlets: Map<Int, String>? = null,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("mac", mac)
        if (strip != null) body.put("strip", strip)
        if (outlets != null) {
            val o = JSONObject()
            outlets.forEach { (n, name) -> o.put(n.toString(), name) }
            body.put("outlets", o)
        }
        val response = JSONObject(request("$base/api/names", body.toString(), token))
        if (!response.optBoolean("ok")) {
            throw ServerException(response.optString("error").ifBlank { "command failed" })
        }
    }

    suspend fun report(base: String, token: String, mac: String, period: String): PowerReport =
        withContext(Dispatchers.IO) {
            val root = JSONObject(request("$base/api/report?mac=$mac&period=$period", null, token))
            val outs = root.optJSONObject("outlets") ?: JSONObject()
            val list = outs.keys().asSequence().mapNotNull { k ->
                val n = k.toIntOrNull() ?: return@mapNotNull null
                val o = outs.getJSONObject(k)
                ReportOutlet(
                    n = n, name = "",
                    kwh = o.optDouble("kwh", 0.0),
                    maxW = o.optDouble("max_w", 0.0),
                    maxT = if (o.isNull("max_t")) null else o.optLong("max_t"),
                    minW = o.optDouble("min_w", 0.0),
                    avgW = o.optDouble("avg_w", 0.0),
                    samples = o.optInt("samples", 0),
                )
            }.sortedBy { it.n }.toList()
            val peak = root.optJSONObject("peak")
            PowerReport(
                mac = root.optString("mac"), period = root.optString("period"),
                totalKwh = root.optDouble("total_kwh", 0.0),
                peakOutlet = peak?.optInt("outlet", 0) ?: 0,
                peakW = peak?.optDouble("power_w", 0.0) ?: 0.0,
                peakT = if (peak == null || peak.isNull("ts")) null else peak.optLong("ts"),
                minW = list.minOfOrNull { it.minW } ?: 0.0,
                avgW = if (list.isEmpty()) 0.0 else list.sumOf { it.avgW } / list.size,
                outlets = list, totalSamples = list.sumOf { it.samples },
            )
        }

    suspend fun history(
        base: String, token: String, mac: String, outlet: Int, period: String,
    ): List<HistoryPoint> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val span = when (period) {
            "week" -> 7 * 86400L
            "month" -> 30 * 86400L
            else -> 86400L
        }
        val bucket = if (period == "today") "raw" else "hour"
        val root = JSONObject(
            request("$base/api/history?mac=$mac&outlet=$outlet&from=${now - span}&to=$now&bucket=$bucket", null, token)
        )
        val array = root.optJSONArray("points")
        (0 until (array?.length() ?: 0)).map { i ->
            val p = array!!.getJSONObject(i)
            HistoryPoint(t = p.optLong("t"), powerW = p.optDouble("power_w", 0.0))
        }
    }

    private fun sceneToJson(s: Scene): JSONObject {
        val t = JSONObject().put("type", s.trigger.type)
        if (s.trigger.type == "threshold") {
            t.put("mac", s.trigger.mac).put("outlet", s.trigger.outlet)
                .put("direction", s.trigger.direction).put("watts", s.trigger.watts)
                .put("for_s", s.trigger.forS)
        } else if (s.trigger.type == "schedule") {
            t.put("time", s.trigger.time)
                .put("days", org.json.JSONArray(s.trigger.days))
        }
        val acts = org.json.JSONArray()
        s.actions.forEach { a ->
            acts.put(JSONObject().put("mac", a.mac).put("outlet", a.outlet).put("on", a.on))
        }
        return JSONObject().put("name", s.name).put("enabled", s.enabled)
            .put("trigger", t).put("actions", acts)
    }

    private fun parseScene(o: JSONObject): Scene {
        val t = o.optJSONObject("trigger") ?: JSONObject()
        val acts = o.optJSONArray("actions")
        val days = t.optJSONArray("days")
        return Scene(
            id = o.optString("id"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            trigger = SceneTrigger(
                type = t.optString("type", "manual"),
                mac = t.optString("mac"),
                outlet = t.optInt("outlet", 1),
                direction = t.optString("direction", "above"),
                watts = t.optDouble("watts", 0.0),
                forS = t.optDouble("for_s", 10.0),
                time = t.optString("time", "07:00"),
                days = (0 until (days?.length() ?: 0)).map { days!!.getInt(it) },
            ),
            actions = (0 until (acts?.length() ?: 0)).map {
                val a = acts!!.getJSONObject(it)
                SceneAction(a.optString("mac"), a.optInt("outlet", 0), a.optBoolean("on"))
            },
            lastFired = if (o.isNull("last_fired")) null else o.optLong("last_fired"),
        )
    }

    suspend fun scenesList(base: String, token: String): List<Scene> = withContext(Dispatchers.IO) {
        val root = JSONObject(request("$base/api/scenes", null, token))
        val array = root.optJSONArray("scenes")
        (0 until (array?.length() ?: 0)).map { parseScene(array!!.getJSONObject(it)) }
    }

    suspend fun saveScene(base: String, token: String, scene: Scene): Scene = withContext(Dispatchers.IO) {
        val body = sceneToJson(scene).toString()
        val response = if (scene.id.isBlank()) {
            JSONObject(request("$base/api/scenes", body, token))
        } else {
            JSONObject(request("$base/api/scenes/${scene.id}", body, token, "PUT"))
        }
        if (!response.optBoolean("ok")) {
            throw ServerException(response.optString("error").ifBlank { "command failed" })
        }
        parseScene(response.getJSONObject("scene"))
    }

    suspend fun deleteScene(base: String, token: String, id: String) = withContext(Dispatchers.IO) {
        val response = JSONObject(request("$base/api/scenes/$id", null, token, "DELETE"))
        if (!response.optBoolean("ok")) {
            throw ServerException(response.optString("error").ifBlank { "command failed" })
        }
    }

    suspend fun runScene(base: String, token: String, id: String) = withContext(Dispatchers.IO) {
        val response = JSONObject(request("$base/api/scenes/$id/run", "{}", token))
        if (!response.optBoolean("ok")) {
            throw ServerException(response.optString("error").ifBlank { "command failed" })
        }
    }

    internal fun request(url: String, body: String?, token: String, method: String? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.requestMethod = method ?: if (body == null) "GET" else "POST"
            if (token.isNotBlank()) connection.setRequestProperty("X-Token", token)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ServerException(errorOf(text, code))
            return text
        } catch (e: ServerException) {
            throw e
        } catch (e: Exception) {
            throw ServerException(e.message ?: "connection failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun errorOf(body: String, code: Int): String =
        try {
            JSONObject(body).optString("error").ifBlank { "HTTP $code" }
        } catch (e: Exception) {
            "HTTP $code"
        }

    private fun parseStrip(o: JSONObject): Strip {
        val array = o.optJSONArray("outlets")
        val outlets = (0 until (array?.length() ?: 0)).map { i ->
            val x = array!!.getJSONObject(i)
            Outlet(
                n = x.optInt("n"),
                on = x.optBoolean("on"),
                powerW = x.optDouble("power_w", 0.0),
                energyKwh = x.optDouble("energy_kwh", 0.0),
                tempC = x.optInt("temp_c"),
                name = x.optString("name"),
            )
        }
        return Strip(
            mac = o.optString("mac"),
            name = o.optString("name"),
            model = o.optString("model"),
            fw = o.optString("fw"),
            online = o.optBoolean("online"),
            on = o.optBoolean("on"),
            powerW = o.optDouble("power_w", 0.0),
            energyKwh = o.optDouble("energy_kwh", 0.0),
            voltage = o.optDoubleOrNull("voltage"),
            currentA = o.optDoubleOrNull("current_a"),
            rssi = if (o.isNull("rssi")) null else o.optInt("rssi"),
            outlets = outlets,
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key)

    /** Strict IPv4 check — never pass an unchecked string into the strip's protocol. */
    internal fun isIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) &&
                (p.toIntOrNull() ?: 256) <= 255
        }
    }

    /**
     * Link a strip straight from the phone: each setup command goes over its own
     * TCP connection to the strip's setup service (default 192.168.1.1:30300).
     * The phone must be on the strip's TONLY_TAP_* network when this runs.
     * Pass a [wifiSocketFactory] (see [wifiSocketFactory]) so the sockets go out
     * over Wi-Fi even though the setup network has no internet.
     */
    suspend fun provisionLink(
        host: String,
        port: Int,
        serverIp: String,
        ssid: String,
        password: String,
        factory: SocketFactory? = null,
    ): ProvisionResult = withContext(Dispatchers.IO) {
        val ip = serverIp.trim()
        // Phone keyboards love appending a stray space: an SSID never
        // intentionally ends with one, and the strip would join nothing.
        // Passwords are sent byte-exact — never trimmed.
        val cleanSsid = ssid.trim()
        if (cleanSsid.isBlank()) return@withContext ProvisionResult(false, emptyList(), "ssid_required")
        if (!isIpv4(ip)) return@withContext ProvisionResult(false, emptyList(), "bad_ip")
        if (cleanSsid.contains(':') || cleanSsid.contains('\n') || cleanSsid.contains('\r') ||
            password.contains(':') || password.contains('\n') || password.contains('\r')
        ) {
            return@withContext ProvisionResult(false, emptyList(), "bad_chars")
        }
        val steps = mutableListOf<String>()
        try {
            steps += transact(factory, host, port, "up:ip:$ip", "up:ip:ip_ok")
            steps += transact(factory, host, port, "up:connect:$cleanSsid:$password", "up:connect:connect_ok")
        } catch (e: IOException) {
            return@withContext ProvisionResult(false, steps, "unreachable")
        } catch (e: IllegalStateException) {
            return@withContext ProvisionResult(false, steps, "bad_answer")
        }
        ProvisionResult(true, steps, null)
    }

    /** Just checks the setup service answers TCP — no credentials are sent. */
    suspend fun probeSetup(host: String, port: Int, factory: SocketFactory? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                openSocket(factory, host, port, 3000).close()
                true
            } catch (e: Exception) {
                false
            }
        }

    /**
     * SocketFactory pinned to a Wi-Fi network (or null when no Wi-Fi is up).
     * Plain sockets would leave over mobile data because the strip's setup
     * network has no internet — with this factory they go out over Wi-Fi.
     */
    suspend fun wifiSocketFactory(context: android.content.Context, timeoutMs: Long = 8000): SocketFactory? =
        withContext(Dispatchers.IO) {
            val cm = context.applicationContext
                .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            fun isWifi(n: android.net.Network): Boolean =
                cm.getNetworkCapabilities(n)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            cm.allNetworks.firstOrNull(::isWifi)?.let { return@withContext it.socketFactory }
            val found = CompletableDeferred<android.net.Network>()
            val req = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    found.complete(network)
                }
            }
            cm.requestNetwork(req, cb)
            try {
                val net = withTimeout(timeoutMs) { found.await() }
                if (isWifi(net)) net.socketFactory else null
            } catch (e: Exception) {
                null
            } finally {
                try {
                    cm.unregisterNetworkCallback(cb)
                } catch (e: Exception) {
                }
            }
        }

    private fun openSocket(factory: SocketFactory?, host: String, port: Int, timeoutMs: Int): Socket {
        val s = factory?.createSocket() ?: Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            try {
                s.close()
            } catch (ignored: Exception) {
            }
            throw e
        }
        return s
    }

    @Throws(IOException::class, IllegalStateException::class)
    private fun transact(factory: SocketFactory?, host: String, port: Int, line: String, expect: String): String {
        openSocket(factory, host, port, 4000).use { s ->
            s.soTimeout = 6000
            s.getOutputStream().write("$line\r\n".toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            val answer = s.getInputStream().bufferedReader().readLine()?.trim()
                ?: throw IOException("empty answer")
            if (!answer.contains(expect)) throw IllegalStateException("unexpected: $answer")
            return answer
        }
    }
}
