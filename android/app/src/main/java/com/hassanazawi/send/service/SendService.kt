package com.hassanazawi.send.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hassanazawi.send.MainActivity
import com.hassanazawi.send.R
import com.hassanazawi.send.net.Dir
import com.hassanazawi.send.net.Engine
import com.hassanazawi.send.net.TState
import com.hassanazawi.send.net.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object Notifier {
    private const val CH_READY = "ready"
    private const val CH_ALERT = "alert"
    const val ID_READY = 1
    private const val ID_PAIR = 2
    private const val ID_RECEIVED = 3
    private const val ID_TEXT = 4

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_READY, "Ready", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps Send reachable from your PC"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Pairing and received files", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, code: Int, act: String): PendingIntent = PendingIntent.getService(
        ctx, code, Intent(ctx, SendService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ready(ctx: Context, s: UiState): Notification {
        val active = s.transfers.firstOrNull { it.state == TState.ACTIVE }
        val title: String
        val text: String
        if (active != null) {
            val pct = if (active.total > 0) (active.done * 100 / active.total).toInt() else 0
            title = (if (active.dir == Dir.OUT) "Sending " else "Receiving ") + (if (active.count > 1) "${active.count} files" else active.title)
            text = "$pct% · ${active.peerName}"
        } else {
            title = if (s.connected.isNotEmpty()) "Connected to ${s.connected.first().name}" else "Ready to send"
            text = s.ip ?: "Not on Wi-Fi"
        }
        val b = NotificationCompat.Builder(ctx, CH_READY)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx))
            .addAction(0, "Stop", action(ctx, 10, SendService.ACT_STOP))
        if (active != null && active.total > 0) b.setProgress(1000, (active.done * 1000 / active.total).toInt(), false)
        return b.build()
    }

    fun pair(ctx: Context, name: String, code: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Pair with $name?")
            .setContentText("Code $code. Make sure it matches the one on your PC.")
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(0, "Allow", action(ctx, 11, SendService.ACT_ALLOW))
            .addAction(0, "Decline", action(ctx, 12, SendService.ACT_DENY))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_PAIR, n)
    }

    fun cancelPair(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_PAIR)

    fun received(ctx: Context, from: String, count: Int, first: String) {
        val view = PendingIntent.getActivity(
            ctx, 1, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (count > 1) "Received $count files" else "Received $first")
            .setContentText("From $from · saved in Downloads/Send")
            .setContentIntent(view)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_RECEIVED, n)
    }

    fun text(ctx: Context, from: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Text from $from")
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_TEXT, n)
    }
}

/** Keeps the process alive and the server reachable while Send is "ready". */
class SendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Engine.init(this)
        when (intent?.action) {
            ACT_STOP -> {
                Engine.stop()
                watcher?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACT_ALLOW -> Engine.answerPair(true)
            ACT_DENY -> Engine.answerPair(false)
        }
        ServiceCompat.startForeground(
            this, Notifier.ID_READY, Notifier.ready(this, Engine.ui.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        Engine.start()
        if (watcher == null || watcher?.isActive != true) {
            watcher = scope.launch {
                var last = ""
                Engine.ui.collectLatest { s ->
                    val n = Notifier.ready(this@SendService, s)
                    val key = "${n.extras.getCharSequence(Notification.EXTRA_TITLE)}|${n.extras.getCharSequence(Notification.EXTRA_TEXT)}|${n.extras.getInt(Notification.EXTRA_PROGRESS)}"
                    if (key != last) {
                        last = key
                        getSystemService(NotificationManager::class.java).notify(Notifier.ID_READY, n)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACT_STOP = "send.STOP"
        const val ACT_ALLOW = "send.PAIR_ALLOW"
        const val ACT_DENY = "send.PAIR_DENY"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SendService::class.java))
        }
    }
}
