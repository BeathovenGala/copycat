package com.copycat.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val kind: String,
    val text: String,
    val path: String?,
    val mime: String?,
    val at: Long,
    val sent: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind).put("text", text).put("path", path).put("mime", mime)
        .put("at", at).put("sent", sent)

    companion object {
        fun fromJson(o: JSONObject) = HistoryItem(
            o.getString("kind"), o.getString("text"),
            o.optString("path").ifEmpty { null }.takeIf { it != "null" },
            o.optString("mime").ifEmpty { null }.takeIf { it != "null" },
            o.getLong("at"), o.optBoolean("sent", true),
        )
    }
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("copycat", Context.MODE_PRIVATE)
    private val historyDir = File(context.applicationContext.filesDir, "history").apply { mkdirs() }

    var host: String?
        get() = sp.getString("host", null)
        set(v) = sp.edit().putString("host", v).apply()
    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()
    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()
    var laptopName: String?
        get() = sp.getString("laptopName", null)
        set(v) = sp.edit().putString("laptopName", v).apply()
    var autoSend: Boolean
        get() = sp.getBoolean("autoSend", false)
        set(v) = sp.edit().putBoolean("autoSend", v).apply()
    var autoScreenshots: Boolean
        get() = sp.getBoolean("autoScreenshots", false)
        set(v) = sp.edit().putBoolean("autoScreenshots", v).apply()
    var lastScreenshotId: Long
        get() = sp.getLong("lastScreenshotId", 0)
        set(v) = sp.edit().putLong("lastScreenshotId", v).apply()
    val needsService get() = autoSend || autoScreenshots
    var lastClipText: String?
        get() = sp.getString("lastClipText", null)
        set(v) = sp.edit().putString("lastClipText", v).apply()

    val isPaired get() = host != null && token != null

    @Synchronized
    fun history(): List<HistoryItem> {
        val arr = JSONArray(sp.getString("history", "[]"))
        return (0 until arr.length()).map { HistoryItem.fromJson(arr.getJSONObject(it)) }
    }

    @Synchronized
    fun addHistory(item: HistoryItem) {
        val kept = (listOf(item) + history().filterNot { it.kind == item.kind && it.text == item.text && it.path == item.path })
            .take(MAX_HISTORY)
        sp.edit().putString("history", JSONArray(kept.map { it.toJson() }).toString()).apply()
        val keepPaths = kept.mapNotNull { it.path }.toSet()
        historyDir.listFiles()?.filter { it.absolutePath !in keepPaths }?.forEach { it.delete() }
    }

    @Synchronized
    fun markSent(at: Long) {
        val updated = history().map { if (it.at == at) it.copy(sent = true) else it }
        sp.edit().putString("history", JSONArray(updated.map { it.toJson() }).toString()).apply()
    }

    @Synchronized
    fun clearHistory() {
        sp.edit().putString("history", "[]").apply()
        historyDir.listFiles()?.forEach { it.delete() }
    }

    fun newHistoryFile(name: String): File = File(historyDir, "${System.currentTimeMillis()}-$name")

    companion object {
        const val DEFAULT_PORT = 47820
        const val MAX_HISTORY = 30
    }
}
