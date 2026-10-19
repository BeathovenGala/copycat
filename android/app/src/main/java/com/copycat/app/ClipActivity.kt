package com.copycat.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Invisible activity: Android only lets the focused app read the clipboard. */
class ClipActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val isAuto = intent.getBooleanExtra(EXTRA_AUTO, false)
        val prefs = Prefs(this)
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri

        if (uri != null) {
            val mime = contentResolver.getType(uri) ?: clip.description.getMimeType(0)
            Thread {
                Sender.copyToHistory(this, uri, mime)?.let { (file, name) -> Sender.sendFile(this, file, name, mime) }
                runOnUiThread { done() }
            }.start()
            return
        }

        val text = item?.coerceToText(this)?.toString()
        when {
            text.isNullOrEmpty() -> if (!isAuto) Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            isAuto && text == prefs.lastClipText -> Unit
            else -> {
                prefs.lastClipText = text
                Sender.sendText(this, text, quiet = isAuto)
            }
        }
        done()
    }

    private fun done() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_AUTO = "auto"

        fun intent(context: Context, auto: Boolean) = Intent(context, ClipActivity::class.java)
            .putExtra(EXTRA_AUTO, auto)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
    }
}
