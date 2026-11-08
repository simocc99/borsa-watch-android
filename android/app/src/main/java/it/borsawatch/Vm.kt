package it.borsawatch

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SearchHit(val sym: String, val name: String, val exchange: String)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs get() = BorsaApp.prefs

    var snap by mutableStateOf<Snapshot?>(null)
        private set
    var events by mutableStateOf<List<Ev>>(emptyList())
        private set
    var online by mutableStateOf<Boolean?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var configured by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var unread by mutableIntStateOf(0)
        private set
    var chatBusy by mutableStateOf(false)
        private set
    var pendingTab by mutableStateOf<Int?>(null)
    var pendingSym by mutableStateOf<String?>(null)
    val chat = mutableStateListOf<ChatMsg>()

    init {
        configured = prefs.configured
        events = Inbox.load()
        unread = Inbox.unread()
        val cached = prefs.cachedState
        if (cached.isNotBlank()) {
            snap = try { Parse.snapshot(JSONObject(cached)) } catch (e: Exception) { null }
        }
    }

    private fun api() = Api(prefs.serverUrl, prefs.token)

    // ---------- aggiornamento ----------
    fun refresh() {
        if (!configured) return
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        try {
            val st = api().get("/api/state")
            prefs.cachedState = st.toString()
            snap = Parse.snapshot(st)
            val after = events.maxOfOrNull { it.id } ?: 0
            val fresh = Parse.events(api().get("/api/events?after=$after"))
            events = Inbox.ingest(app, fresh, notify = true)
            unread = Inbox.unread()
            online = true
            lastError = null
            BorsaWidget.refreshAll(app)
        } catch (e: ApiError) {
            online = false
            lastError = e.message
        } catch (e: Exception) {
            online = false
            lastError = "Errore di collegamento"
        }
    }

    fun refreshServerNow() {
        run("Prezzi aggiornati") { it.post("/api/refresh", JSONObject(), 120000) }
    }

    fun markInboxSeen() {
        Inbox.markAllSeen()
        unread = 0
    }

    // ---------- collegamento ----------
    fun connect(urlRaw: String, code: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            busy = true
            val url = Fmt.normalizeUrl(urlRaw)
            try {
                if (url.isEmpty() || code.isBlank()) throw ApiError("Scrivi l'indirizzo del server e il codice.")
                val ping = Api(url, code.trim()).get("/api/ping")
                if (ping.optString("app") != "Borsa Watch") throw ApiError("Questo indirizzo non è un server Borsa Watch.")
                if (!ping.optBoolean("auth")) throw ApiError("Il codice di abbinamento non è corretto.", 401)
                prefs.serverUrl = url
                prefs.token = code.trim()
                prefs.firstSyncDone = false
                prefs.lastNotified = 0
                configured = true
                message = "Collegato al server"
                refreshNow()
                if (prefs.fcmToken.isNotBlank()) PushSetup.send(prefs.fcmToken) else PushSetup.register(app)
                onDone(true)
            } catch (e: ApiError) {
                message = e.message
                onDone(false)
            } catch (e: Exception) {
                message = "Non riesco a collegarmi"
                onDone(false)
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        prefs.serverUrl = ""
        prefs.token = ""
        prefs.cachedState = ""
        configured = false
        snap = null
        online = null
        message = "Server scollegato"
    }

    // ---------- azioni ----------
    private fun run(okMsg: String?, block: suspend (Api) -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                block(api())
                if (okMsg != null) message = okMsg
                refreshNow()
            } catch (e: ApiError) {
                message = e.message
            } catch (e: Exception) {
                message = "Qualcosa non ha funzionato"
            } finally {
                busy = false
            }
        }
    }

    fun addTicker(sym: String, name: String) =
        run("Aggiunto: $name") { it.post("/api/tickers", JSONObject().put("sym", sym).put("name", name)) }

    fun removeTicker(sym: String) = run("Titolo tolto") { it.delete("/api/tickers/" + Uri.encode(sym)) }

    fun moveTicker(sym: String, where: String) =
        run(null) { it.post("/api/tickers/move", JSONObject().put("sym", sym).put("where", where)) }

    fun createAlert(sym: String, op: String, value: Double, repeatMin: Int) =
        run("Avviso creato") {
            it.post("/api/alerts", JSONObject().put("sym", sym).put("op", op).put("value", value).put("repeat_min", repeatMin))
        }

    fun removeAlert(id: Int) = run("Avviso tolto") { it.delete("/api/alerts/$id") }

    fun importText(text: String) = run("Lista importata") { it.post("/api/import", JSONObject().put("text", text), 120000) }

    fun saveServerSettings(vararg pairs: Pair<String, Any>) =
        run("Impostazioni salvate") { api ->
            val o = JSONObject()
            pairs.forEach { (k, v) -> o.put(k, v) }
            api.post("/api/settings", o)
        }

    // ---------- letture a richiesta ----------
    suspend fun search(q: String): List<SearchHit> = try {
        val a = api().get("/api/search?q=" + Uri.encode(q)).optJSONArray("risultati") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).let { o -> SearchHit(o.getString("sym"), o.optString("name"), o.optString("exchange")) } }
    } catch (e: ApiError) {
        message = e.message
        emptyList()
    } catch (e: Exception) {
        message = "Ricerca non riuscita"
        emptyList()
    }

    suspend fun chart(sym: String, days: Int): List<Pair<Long, Double>> = try {
        Parse.points(api().get("/api/chart?sym=" + Uri.encode(sym) + "&days=$days"))
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun news(sym: String): List<String> = try {
        val a = api().get("/api/news?sym=" + Uri.encode(sym)).optJSONArray("notizie") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).optString("title") }
    } catch (e: Exception) {
        message = "Notizie non disponibili"
        emptyList()
    }

    suspend fun exportCsv(): String = try {
        api().get("/api/export").optString("csv")
    } catch (e: Exception) {
        message = "Esportazione non riuscita"
        ""
    }

    // ---------- assistente ----------
    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty() || chatBusy) return
        val history = JSONArray()
        chat.takeLast(8).forEach { history.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
        chat.add(ChatMsg(true, t))
        viewModelScope.launch {
            chatBusy = true
            try {
                val r = api().post("/api/ai/chat", JSONObject().put("text", t).put("history", history), 150000)
                chat.add(ChatMsg(false, r.optString("reply"), Parse.proposals(r), r.optBoolean("verified", true)))
            } catch (e: ApiError) {
                chat.add(ChatMsg(false, e.message ?: "L'assistente non risponde"))
            } catch (e: Exception) {
                chat.add(ChatMsg(false, "L'assistente non risponde. Riprova tra poco."))
            } finally {
                chatBusy = false
            }
        }
    }

    fun answerProposal(msgIndex: Int, pid: String, accept: Boolean) {
        viewModelScope.launch {
            try {
                api().post("/api/ai/confirm", JSONObject().put("id", pid).put("accept", accept))
                setProposalState(msgIndex, pid, if (accept) "done" else "rejected")
                if (accept) refreshNow()
            } catch (e: ApiError) {
                message = e.message
                setProposalState(msgIndex, pid, "error")
            } catch (e: Exception) {
                message = "Non sono riuscito a completare"
            }
        }
    }

    private fun setProposalState(i: Int, pid: String, state: String) {
        if (i !in chat.indices) return
        val m = chat[i]
        chat[i] = m.copy(proposals = m.proposals.map { if (it.id == pid) it.copy(state = state) else it })
    }
}
