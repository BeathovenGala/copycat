package com.copycat.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

object Sender {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun sendText(context: Context, text: String, quiet: Boolean = false) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { Net.sendText(app, text) }
            Prefs(app).addHistory(HistoryItem("text", text, null, null, System.currentTimeMillis(), result.isSuccess))
            report(app, result, if (quiet) null else "Sent to laptop")
        }
    }

    // Copies the content now, while the caller still holds the URI read grant.
    fun copyToHistory(context: Context, uri: Uri, mimeHint: String?): Pair<File, String>? {
        val name = displayName(context, uri, mimeHint)
        val dest = Prefs(context).newHistoryFile(name)
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest to name
        }.getOrElse {
            dest.delete()
            null
        }
    }

    fun sendFile(context: Context, file: File, name: String, mime: String) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { Net.sendFile(app, file, name, mime) }
            val kind = if (mime.startsWith("image/")) "image" else "file"
            Prefs(app).addHistory(HistoryItem(kind, name, file.absolutePath, mime, System.currentTimeMillis(), result.isSuccess))
            report(app, result, "Sent $name")
        }
    }

    /** Connects if needed, then delivers anything from the last day that didn't go through. */
    fun connectAndRetry(context: Context, onDone: ((Throwable?) -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            val prefs = Prefs(app)
            val pending = prefs.history().filter { !it.sent && System.currentTimeMillis() - it.at < RETRY_WINDOW_MS }
            val connected = runCatching { Net.ensurePaired(app) }
            if (connected.isSuccess) {
                pending.reversed().forEach { item ->
                    val ok = runCatching {
                        if (item.kind == "text") Net.sendText(app, item.text)
                        else Net.sendFile(app, File(item.path!!), item.text, item.mime ?: "application/octet-stream")
                    }.isSuccess
                    if (ok) prefs.markSent(item.at)
                }
            }
            main.post {
                listeners.toList().forEach { it() }
                onDone?.invoke(connected.exceptionOrNull())
            }
        }
    }

    fun hasPending(context: Context) =
        Prefs(context).history().any { !it.sent && System.currentTimeMillis() - it.at < RETRY_WINDOW_MS }

    private const val RETRY_WINDOW_MS = 24 * 60 * 60 * 1000L

    fun resend(context: Context, item: HistoryItem) {
        if (item.kind == "text") return sendText(context, item.text)
        val file = item.path?.let(::File)
        if (file == null || !file.exists()) {
            Toast.makeText(context, "That file is no longer saved", Toast.LENGTH_SHORT).show()
            return
        }
        sendFile(context, file, item.text, item.mime ?: "application/octet-stream")
    }

    private fun report(context: Context, result: Result<Unit>, successMessage: String?) {
        if (result.isFailure) RetryJob.schedule(context)
        main.post {
            listeners.toList().forEach { it() }
            val message = result.fold(
                onSuccess = { successMessage },
                onFailure = { e ->
                    when (e) {
                        is MultipleLaptopsException -> "Open CopyCat and choose your laptop"
                        is NotPairedException -> "Laptop not found — will send when it's back"
                        else -> "Laptop not reachable — will send when it's back"
                    }
                },
            )
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun displayName(context: Context, uri: Uri, mimeHint: String?): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        if (!queried.isNullOrBlank()) return queried.replace('/', '_')
        val mime = mimeHint ?: context.contentResolver.getType(uri)
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
        return "shared-${System.currentTimeMillis()}.$ext"
    }
}
