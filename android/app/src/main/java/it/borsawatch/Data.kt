package it.borsawatch

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ formati (italiano)
object Fmt {
    fun price(v: Double?): String {
        if (v == null) return "–"
        val d = if (kotlin.math.abs(v) < 10) 4 else 2
        return String.format(Locale.ITALY, "%,." + d + "f", v)
    }

    fun pct(v: Double?): String {
        if (v == null) return "–"
        return String.format(Locale.ITALY, "%+.2f%%", v)
    }

    fun time(tsSec: Long?): String {
        if (tsSec == null || tsSec == 0L) return "–"
        return SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(tsSec * 1000))
    }

    fun dateTime(tsSec: Long): String =
        SimpleDateFormat("d MMM, HH:mm", Locale.ITALY).format(Date(tsSec * 1000))

    fun ago(tsSec: Long, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val d = nowSec - tsSec
        return when {
            d < 60 -> "adesso"
            d < 3600 -> "${d / 60} min fa"
            d < 86400 -> "${d / 3600} h fa"
            else -> dateTime(tsSec)
        }
    }

    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        val host = u.substringAfter("://")
        if (!host.contains(":") && u.startsWith("http://")) u += ":8765"
        return u
    }
}

// ------------------------------------------------------------------ modelli
data class Tick(
    val sym: String, val name: String, val last: Double?, val chg: Double?, val open: Double?,
    val high: Double?, val low: Double?, val vol: Double?, val cur: String?, val ts: Long?,
    val error: String?, val alerts: List<Int>
)

data class AlertRule(
    val id: Int, val sym: String, val op: String, val value: Double, val repeatMin: Int, val fired: Boolean
) {
    fun text(): String {
        val v = String.format(Locale.ITALY, "%s", if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString().replace('.', ','))
        return when (op) {
            "<" -> "$sym scende sotto $v"
            ">" -> "$sym sale sopra $v"
            "±" -> "$sym si muove di oltre il $v% nella giornata"
            "1h-" -> "$sym cala di almeno il $v% in un'ora"
            "1h+" -> "$sym sale di almeno il $v% in un'ora"
            else -> "$sym $op $v"
        }
    }
}

data class Ev(
    val id: Long, val ts: Long, val kind: String, val sym: String, val title: String,
    val body: String, val price: Double?
)

data class Proposal(val id: String, val tool: String, val summary: String, val state: String = "pending")

data class ChatMsg(
    val fromUser: Boolean, val text: String, val proposals: List<Proposal> = emptyList(),
    val verified: Boolean = true
)

data class Snapshot(
    val tickers: List<Tick>, val alerts: List<AlertRule>, val marketOpen: Boolean, val aiReady: Boolean,
    val pushReady: Boolean, val serverTime: Long, val lastEvent: Long, val marketHoursOnly: Boolean,
    val intervalAlert: Int, val intervalAll: Int, val version: String, val source: String
)

private fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
private fun JSONObject.lng(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k)
private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)

object Parse {
    fun snapshot(j: JSONObject): Snapshot {
        val ts = j.optJSONArray("tickers") ?: JSONArray()
        val ticks = (0 until ts.length()).map { i ->
            val o = ts.getJSONObject(i)
            val al = o.optJSONArray("alerts") ?: JSONArray()
            Tick(
                o.getString("sym"), o.optString("name", o.getString("sym")), o.dbl("last"), o.dbl("chg"), o.dbl("open"),
                o.dbl("high"), o.dbl("low"), o.dbl("vol"), o.str("cur"), o.lng("ts"), o.str("error"),
                (0 until al.length()).map { al.getInt(it) }
            )
        }
        val aa = j.optJSONArray("alerts") ?: JSONArray()
        val alerts = (0 until aa.length()).map { i ->
            val o = aa.getJSONObject(i)
            AlertRule(o.getInt("id"), o.getString("sym"), o.getString("op"), o.getDouble("value"), o.optInt("repeat_min"), o.optInt("fired") == 1)
        }
        val s = j.optJSONObject("settings") ?: JSONObject()
        return Snapshot(
            ticks, alerts, j.optBoolean("market_open"), s.optBoolean("ai_ready"), s.optBoolean("push_ready"),
            j.optLong("server_time"), j.optLong("last_event"), s.optBoolean("market_hours", true),
            s.optInt("interval_alert", 20), s.optInt("interval_all", 120), j.optString("version"), j.optString("source")
        )
    }

    fun event(o: JSONObject) = Ev(
        o.getLong("id"), o.optLong("ts"), o.optString("kind", "alert"), o.optString("sym"),
        o.optString("title"), o.optString("body"), o.dbl("price")
    )

    fun events(j: JSONObject): List<Ev> {
        val a = j.optJSONArray("events") ?: JSONArray()
        return (0 until a.length()).map { event(a.getJSONObject(it)) }
    }

    fun points(j: JSONObject): List<Pair<Long, Double>> {
        val a = j.optJSONArray("points") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getLong("ts") to o.getDouble("price") } }
    }

    fun proposals(j: JSONObject): List<Proposal> {
        val a = j.optJSONArray("proposals") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Proposal(o.getString("id"), o.getString("tool"), o.getString("summary")) } }
    }

    fun eventsToJson(list: List<Ev>): String {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("ts", it.ts).put("kind", it.kind).put("sym", it.sym)
                .put("title", it.title).put("body", it.body).put("price", it.price ?: JSONObject.NULL))
        }
        return a.toString()
    }

    fun eventsFromJson(s: String?): List<Ev> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { event(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }
}

// ------------------------------------------------------------------ preferenze
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("borsawatch", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v).apply()
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()
    var fcmToken: String
        get() = sp.getString("fcm", "") ?: ""
        set(v) = sp.edit().putString("fcm", v).apply()
    var lastNotified: Long
        get() = sp.getLong("last_notified", 0)
        set(v) = sp.edit().putLong("last_notified", v).apply()
    var lastSeenInbox: Long
        get() = sp.getLong("last_seen_inbox", 0)
        set(v) = sp.edit().putLong("last_seen_inbox", v).apply()
    var firstSyncDone: Boolean
        get() = sp.getBoolean("first_sync", false)
        set(v) = sp.edit().putBoolean("first_sync", v).apply()
    var cachedState: String
        get() = sp.getString("state", "") ?: ""
        set(v) = sp.edit().putString("state", v).apply()
    var inboxJson: String
        get() = sp.getString("inbox", "[]") ?: "[]"
        set(v) = sp.edit().putString("inbox", v).apply()

    val configured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

// ------------------------------------------------------------------ collegamento al server
class ApiError(message: String, val code: Int = 0) : Exception(message)

class Api(base: String, private val token: String) {
    private val base = base.trimEnd('/')

    suspend fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 15000): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = try {
                (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 6000
                    readTimeout = timeoutMs
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    if (body != null) {
                        doOutput = true
                        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
            } catch (e: Exception) {
                throw ApiError("Indirizzo del server non valido")
            }
            try {
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    throw ApiError("Non riesco a raggiungere il server. Controlla che il PC sia acceso e sulla stessa rete Wi-Fi.")
                }
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val json = try {
                    JSONObject(text.ifBlank { "{}" })
                } catch (e: Exception) {
                    JSONObject()
                }
                if (code == 401) throw ApiError("Il codice di abbinamento non è corretto.", 401)
                if (code >= 400) throw ApiError(json.optString("error", "Errore del server ($code)"), code)
                json
            } finally {
                conn.disconnect()
            }
        }

    suspend fun get(path: String) = call("GET", path)
    suspend fun post(path: String, body: JSONObject = JSONObject(), timeoutMs: Int = 15000) = call("POST", path, body, timeoutMs)
    suspend fun delete(path: String) = call("DELETE", path)
}
