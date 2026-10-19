package com.copycat.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty()) {
            if (!text.isNullOrBlank()) Sender.sendText(this, text)
            finish()
            return
        }
        Toast.makeText(this, "Sending ${if (uris.size == 1) "1 item" else "${uris.size} items"}…", Toast.LENGTH_SHORT).show()
        // Stay alive until each file is copied: the URI grant ends when this activity does.
        Thread {
            uris.forEach { uri ->
                val mime = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
                Sender.copyToHistory(this, uri, mime)?.let { (file, name) -> Sender.sendFile(this, file, name, mime) }
            }
            runOnUiThread { finish() }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
        else -> emptyList()
    }
}
