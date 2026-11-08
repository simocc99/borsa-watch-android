package it.borsawatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BorsaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        Notifier.ensureChannels(this)
        SyncWorker.schedule(this)
        PushSetup.register(this)
    }

    companion object {
        lateinit var instance: BorsaApp
        lateinit var prefs: Prefs
    }
}

// ------------------------------------------------------------------ notifiche di sistema
object Notifier {
    const val CH_ALERTS = "alerts"
    const val CH_MARKET = "market"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Avvisi sui titoli", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Quando un titolo supera la soglia che hai scelto"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MARKET, "Apertura e chiusura borsa", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avvisi di apertura e riepilogo di chiusura"
            }
        )
    }

    fun show(ctx: Context, ev: Ev) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", 1)
            putExtra("sym", ev.sym)
        }
        val pi = PendingIntent.getActivity(ctx, ev.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alert = ev.kind == "alert"
        val n = NotificationCompat.Builder(ctx, if (alert) CH_ALERTS else CH_MARKET)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(Color.parseColor("#0B5CD5"))
            .setContentTitle(ev.title)
            .setContentText(ev.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ev.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            nm.notify(ev.id.toInt(), n)
        } catch (e: SecurityException) {
            // permesso notifiche non concesso: la casella dell'app resta comunque aggiornata
        }
    }
}

// ------------------------------------------------------------------ casella avvisi
object Inbox {
    private val lock = Any()

    fun load(): List<Ev> = Parse.eventsFromJson(BorsaApp.prefs.inboxJson)

    /** Salva gli avvisi ricevuti e mostra la notifica solo per quelli nuovi, una volta sola. */
    fun ingest(ctx: Context, incoming: List<Ev>, notify: Boolean, fromPush: Boolean = false): List<Ev> =
        synchronized(lock) {
            val p = BorsaApp.prefs
            val map = Parse.eventsFromJson(p.inboxJson).associateBy { it.id }.toMutableMap()
            incoming.forEach { map[it.id] = it }
            val all = map.values.sortedBy { it.id }.takeLast(300)
            p.inboxJson = Parse.eventsToJson(all)
            val firstSync = !p.firstSyncDone && !fromPush
            val fresh = incoming.filter { it.id > p.lastNotified }.sortedBy { it.id }
            if (firstSync) {
                p.firstSyncDone = true   // al primo collegamento lo storico non genera notifiche
            } else if (notify) {
                fresh.forEach { Notifier.show(ctx, it) }
            }
            if (fresh.isNotEmpty()) p.lastNotified = fresh.last().id
            all
        }

    fun unread(): Int {
        val seen = BorsaApp.prefs.lastSeenInbox
        return load().count { it.id > seen }
    }

    fun markAllSeen() {
        BorsaApp.prefs.lastSeenInbox = load().maxOfOrNull { it.id } ?: 0
    }
}

// ------------------------------------------------------------------ push (Firebase), se configurato
object PushSetup {
    fun register(ctx: Context) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { tok ->
                BorsaApp.prefs.fcmToken = tok
                send(tok)
            }
        } catch (e: Exception) {
            // Firebase non configurato: si usa il controllo periodico del server
        }
    }

    fun send(tok: String) {
        val p = BorsaApp.prefs
        if (!p.configured || tok.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Api(p.serverUrl, p.token).post("/api/device", JSONObject().put("token", tok))
            } catch (e: Exception) {
                // si riproverà alla prossima apertura
            }
        }
    }
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        BorsaApp.prefs.fcmToken = token
        PushSetup.send(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val id = d["id"]?.toLongOrNull() ?: return
        val ev = Ev(
            id, d["ts"]?.toLongOrNull() ?: (System.currentTimeMillis() / 1000), d["kind"] ?: "alert",
            d["sym"] ?: "", d["title"] ?: "Borsa Watch", d["body"] ?: "", d["price"]?.toDoubleOrNull()
        )
        Inbox.ingest(applicationContext, listOf(ev), notify = true, fromPush = true)
        BorsaWidget.refreshAll(applicationContext)
    }
}

// ------------------------------------------------------------------ controllo periodico (ogni 15 minuti)
object Sync {
    suspend fun run(ctx: Context) {
        val p = BorsaApp.prefs
        if (!p.configured) return
        val api = Api(p.serverUrl, p.token)
        val state = api.get("/api/state")
        p.cachedState = state.toString()
        val after = Inbox.load().maxOfOrNull { it.id } ?: 0
        val events = Parse.events(api.get("/api/events?after=$after"))
        Inbox.ingest(ctx, events, notify = true)
        BorsaWidget.refreshAll(ctx)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            Sync.run(applicationContext)
        } catch (e: Exception) {
            // server non raggiungibile: riproverà tra 15 minuti
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

// ------------------------------------------------------------------ widget nella schermata Home
class BorsaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, build(context)) }
    }

    companion object {
        fun refreshAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, BorsaWidget::class.java))
                ids.forEach { mgr.updateAppWidget(it, build(ctx)) }
            } catch (e: Exception) {
                // il widget non è sulla Home
            }
        }

        private fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_borsa)
            val rows = listOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5)
            val raw = try { BorsaApp.prefs.cachedState } catch (e: Exception) { "" }
            val ticks = try {
                if (raw.isBlank()) emptyList() else Parse.snapshot(JSONObject(raw)).tickers.take(5)
            } catch (e: Exception) {
                emptyList()
            }
            rows.forEachIndexed { i, id ->
                val t = ticks.getOrNull(i)
                if (t == null) {
                    rv.setTextViewText(id, if (i == 0 && ticks.isEmpty()) "Apri l'app per collegare il server" else "")
                } else {
                    rv.setTextViewText(id, "${t.sym}   ${Fmt.price(t.last)}   ${Fmt.pct(t.chg)}")
                    val c = when {
                        (t.chg ?: 0.0) > 0 -> "#0A7A3D"
                        (t.chg ?: 0.0) < 0 -> "#C62828"
                        else -> "#12263F"
                    }
                    rv.setTextColor(id, Color.parseColor(c))
                }
            }
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.root, pi)
            return rv
        }
    }
}
