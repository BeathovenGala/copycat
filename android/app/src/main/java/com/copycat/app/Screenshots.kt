package com.copycat.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.Executors

class Screenshots(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val check = Runnable { worker.execute(::sendNew) }
    private var registered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // MediaStore fires several changes per screenshot; wait for the file to settle.
            handler.removeCallbacks(check)
            handler.postDelayed(check, SETTLE_MS)
        }
    }

    fun start() {
        if (registered || !hasPermission(context)) return
        context.contentResolver.registerContentObserver(COLLECTION, true, observer)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(check)
        registered = false
    }

    private fun sendNew() {
        val prefs = Prefs(context)
        if (!prefs.autoScreenshots) return
        val fresh = query(prefs.lastScreenshotId, System.currentTimeMillis() / 1000 - MAX_AGE_S)
        fresh.forEach { (id, mime) ->
            val uri = ContentUris.withAppendedId(COLLECTION, id)
            Sender.copyToHistory(context, uri, mime)?.let { (file, name) -> Sender.sendFile(context, file, name, mime) }
        }
        fresh.maxOfOrNull { it.first }?.let { prefs.lastScreenshotId = it }
    }

    private fun query(afterId: Long, sinceEpochS: Long): List<Pair<Long, String>> {
        val cols = mutableListOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DISPLAY_NAME)
        var selection = "${MediaStore.Images.Media._ID} > ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
        if (Build.VERSION.SDK_INT >= 29) {
            cols += MediaStore.Images.Media.RELATIVE_PATH
            selection += " AND ${MediaStore.Images.Media.IS_PENDING} = 0"
        }
        val out = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(
            COLLECTION, cols.toTypedArray(), selection, arrayOf(afterId.toString(), sinceEpochS.toString()),
            "${MediaStore.Images.Media._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2).orEmpty()
                val path = if (Build.VERSION.SDK_INT >= 29) c.getString(3).orEmpty() else ""
                if (name.contains("screenshot", true) || path.contains("screenshot", true)) {
                    out += c.getLong(0) to (c.getString(1) ?: "image/png")
                }
            }
        }
        return out
    }

    companion object {
        private val COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        private const val SETTLE_MS = 800L
        private const val MAX_AGE_S = 60L

        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasPermission(context: Context) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        fun latestId(context: Context): Long = runCatching {
            context.contentResolver.query(
                COLLECTION, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media._ID} DESC",
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }
}
