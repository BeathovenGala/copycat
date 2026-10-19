package com.copycat.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var auto: Switch
    private lateinit var shots: Switch
    private lateinit var autoHint: TextView
    private lateinit var empty: TextView
    private lateinit var historyAdapter: ArrayAdapter<HistoryItem>
    private val refresh = { renderHistory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        status = findViewById(R.id.status)
        auto = findViewById(R.id.auto)
        shots = findViewById(R.id.shots)
        autoHint = findViewById(R.id.autoHint)
        empty = findViewById(R.id.empty)

        findViewById<Button>(R.id.send).setOnClickListener { startActivity(ClipActivity.intent(this, auto = false)) }
        findViewById<Button>(R.id.pair).setOnClickListener { startPairing() }
        findViewById<TextView>(R.id.clear).setOnClickListener {
            prefs.clearHistory()
            renderHistory()
        }
        auto.setOnCheckedChangeListener { _, on -> setAuto(on) }
        shots.setOnCheckedChangeListener { _, on -> setScreenshots(on) }

        historyAdapter = object : ArrayAdapter<HistoryItem>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val item = getItem(position)!!
                val icon = when (item.kind) { "image" -> "🖼  "; "file" -> "📄  "; else -> "" }
                view.findViewById<TextView>(android.R.id.text1).apply {
                    text = icon + item.text.replace('\n', ' ')
                    maxLines = 2
                }
                view.findViewById<TextView>(android.R.id.text2).text =
                    DateUtils.getRelativeTimeSpanString(item.at).toString() + if (item.sent) "" else " · not delivered"
                return view
            }
        }
        findViewById<ListView>(R.id.history).apply {
            setAdapter(historyAdapter)
            setOnItemClickListener { _, _, pos, _ -> Sender.resend(this@MainActivity, historyAdapter.getItem(pos)!!) }
            setOnItemLongClickListener { _, _, pos, _ ->
                val item = historyAdapter.getItem(pos)!!
                if (item.kind == "text") {
                    prefs.lastClipText = item.text
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CopyCat", item.text))
                }
                true
            }
        }

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    override fun onResume() {
        super.onResume()
        Sender.addListener(refresh)
        renderStatus()
        renderHistory()
        Sender.connectAndRetry(this) { renderStatus() }
        checkForUpdate()
        // Android 13+ only allows log access to start while the app is in the foreground.
        if (prefs.needsService) WatchService.start(this, restartReader = prefs.autoSend)
    }

    override fun onPause() {
        Sender.removeListener(refresh)
        super.onPause()
    }

    private fun setAuto(on: Boolean) {
        if (on == prefs.autoSend) return
        prefs.autoSend = on
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow CopyCat to display over other apps", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        WatchService.start(this, restartReader = on)
        renderStatus()
    }

    private fun setScreenshots(on: Boolean) {
        if (on == prefs.autoScreenshots) return
        if (on && !Screenshots.hasPermission(this)) {
            requestPermissions(arrayOf(Screenshots.permission), REQUEST_MEDIA)
            shots.isChecked = false
            return
        }
        if (on) prefs.lastScreenshotId = Screenshots.latestId(this)
        prefs.autoScreenshots = on
        WatchService.start(this)
        renderStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA) return
        if (Screenshots.hasPermission(this)) setScreenshots(true)
        else Toast.makeText(this, "CopyCat needs access to all photos to find new screenshots", Toast.LENGTH_LONG).show()
    }

    private fun renderStatus() {
        status.text = "v${Updater.currentVersion(this)} · " + if (prefs.isPaired) "Connected to ${prefs.laptopName} (${prefs.host})" else "Looking for your laptop… make sure CopyCat is running on it and you're on the same Wi-Fi."
        auto.isChecked = prefs.autoSend
        shots.isChecked = prefs.autoScreenshots
        autoHint.text = when {
            !WatchService.hasReadLogs(this) ->
                "One-time setup: connect to your laptop with USB debugging and run\n" +
                    "adb shell pm grant $packageName android.permission.READ_LOGS\n" +
                    "Until then, use the notification, the Quick Settings tile, or the share menu."
            !Settings.canDrawOverlays(this) -> "Needs “Display over other apps” permission."
            prefs.autoSend -> "On. Android shows a “pasted from your clipboard” notice each time — that’s normal."
            else -> "Ready. Turn on to send everything you copy."
        }
    }

    private fun checkForUpdate() {
        val button = findViewById<Button>(R.id.update)
        Thread {
            val release = Updater.check(this) ?: return@Thread
            runOnUiThread {
                button.text = "Update to v${release.version}"
                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    button.isEnabled = false
                    button.text = "Downloading v${release.version}…"
                    Thread {
                        val result = runCatching { Updater.install(this, release) }
                        runOnUiThread {
                            button.isEnabled = true
                            button.text = if (result.isSuccess) "Update to v${release.version}" else "Download failed — tap to retry"
                        }
                    }.start()
                }
            }
        }.start()
    }

    private fun renderHistory() {
        val items = prefs.history()
        historyAdapter.clear()
        historyAdapter.addAll(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startPairing() {
        val searching = Toast.makeText(this, "Looking for your laptop…", Toast.LENGTH_SHORT).apply { show() }
        Thread {
            val result = runCatching { Net.ensurePaired(this) }
            runOnUiThread {
                searching.cancel()
                result.fold(
                    { Toast.makeText(this, "Connected to ${it.name}", Toast.LENGTH_SHORT).show() },
                    { askManualAddress() },
                )
                renderStatus()
            }
        }.start()
    }

    private fun askManualAddress() {
        val input = EditText(this).apply {
            hint = "192.168.1.20"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(prefs.host ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Laptop not found")
            .setMessage("Make sure CopyCat is running and both are on the same Wi-Fi. Or type the address shown in the laptop's tray menu.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val host = input.text.toString().trim()
                Thread {
                    val name = Net.ping(host, Prefs.DEFAULT_PORT)
                    runOnUiThread {
                        if (name == null) Toast.makeText(this, "No CopyCat at $host", Toast.LENGTH_LONG).show()
                        else askPin(Laptop(host, Prefs.DEFAULT_PORT, name))
                    }
                }.start()
            }
            .setNeutralButton("Search again") { _, _ -> startPairing() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askPin(laptop: Laptop) {
        val input = EditText(this).apply {
            hint = "6-digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with ${laptop.name}")
            .setMessage("Enter the PIN from the CopyCat tray menu on your laptop.")
            .setView(input)
            .setPositiveButton("Pair") { _, _ ->
                val pin = input.text.toString()
                Thread {
                    val result = runCatching { Net.pair(laptop, pin, prefs) }
                    runOnUiThread {
                        Toast.makeText(this, result.fold({ "Paired with ${laptop.name}" }, { it.message ?: "Pairing failed" }), Toast.LENGTH_LONG).show()
                        renderStatus()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val REQUEST_MEDIA = 1
    }
}
