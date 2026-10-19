package com.copycat.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/**
 * Android 10+ hides the clipboard from background apps, but the system still logs a
 * "Denying clipboard access to <pkg>" line each time it changes. With READ_LOGS granted over adb
 * we watch for that line and briefly open [ClipActivity] to read it (same trick as KDE Connect).
 */
class WatchService : Service() {
    private var logcat: Process? = null
    private var reader: Thread? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { }
    private var lastTrigger = 0L
    private val screenshots by lazy { Screenshots(this) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val retryTick = object : Runnable {
        override fun run() {
            if (Sender.hasPending(this@WatchService)) Sender.connectAndRetry(this@WatchService)
            Thread { Updater.check(this@WatchService)?.let(::notifyUpdate) }.start()
            handler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent?.action == ACTION_RESTART_READER) stopReader()
        val prefs = Prefs(this)
        if (!prefs.needsService) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (prefs.autoSend && canAutoCapture(this) && reader?.isAlive != true) startReader()
        if (!prefs.autoSend) stopReader()
        if (prefs.autoScreenshots) screenshots.start() else screenshots.stop()
        handler.removeCallbacks(retryTick)
        handler.post(retryTick)
        return START_STICKY
    }

    override fun onDestroy() {
        stopReader()
        screenshots.stop()
        handler.removeCallbacks(retryTick)
        super.onDestroy()
    }

    private fun startReader() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        val needle = "Denying clipboard access to $packageName"
        reader = Thread {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "ClipboardService:E", "*:S"))
                logcat = proc
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.contains(needle)) onClipChanged()
                }
            } catch (_: Exception) {
                // Reader dies with the process; restarted on next service start.
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopReader() {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        }
        logcat?.destroy()
        logcat = null
        reader = null
    }

    private fun onClipChanged() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < DEBOUNCE_MS) return
        lastTrigger = now
        if (Prefs(this).autoSend) startActivity(ClipActivity.intent(this, auto = true))
    }

    private fun notifyUpdate(release: Release) {
        val sp = getSharedPreferences("updater", MODE_PRIVATE)
        if (sp.getString("notified", null) == release.version) return
        sp.edit().putString("notified", release.version).apply()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(UPDATE_CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            UPDATE_NOTIFICATION_ID,
            Notification.Builder(this, UPDATE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("CopyCat v${release.version} is available")
                .setContentText("Tap to update")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "CopyCat background", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        )
        val send = PendingIntent.getActivity(
            this, 1, ClipActivity.intent(this, auto = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val prefs = Prefs(this)
        val status = listOfNotNull(
            "copies".takeIf { prefs.autoSend && canAutoCapture(this) },
            "screenshots".takeIf { prefs.autoScreenshots && Screenshots.hasPermission(this) },
        ).let { if (it.isEmpty()) "Tap Send clipboard to send what you copied" else "Auto-sending ${it.joinToString(" and ")} to your laptop" }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("CopyCat")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Send clipboard", send).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "background"
        private const val NOTIFICATION_ID = 1
        private const val DEBOUNCE_MS = 400L
        private const val RETRY_INTERVAL_MS = 30_000L
        private const val UPDATE_CHANNEL = "updates"
        private const val UPDATE_NOTIFICATION_ID = 2
        const val ACTION_RESTART_READER = "restart_reader"

        fun hasReadLogs(context: Context) =
            context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

        fun canAutoCapture(context: Context) = hasReadLogs(context) && Settings.canDrawOverlays(context)

        fun start(context: Context, restartReader: Boolean = false) {
            val intent = Intent(context, WatchService::class.java)
            if (restartReader) intent.action = ACTION_RESTART_READER
            context.startForegroundService(intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, WatchService::class.java))
    }
}
