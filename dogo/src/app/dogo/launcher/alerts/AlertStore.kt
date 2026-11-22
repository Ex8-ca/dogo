/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** All notification-assistant state is excluded from Android backup. Bodies are opt-in only. */
internal class AlertStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "notification_assistant")
    private val settings get() = AtomicFile(File(directory, "settings.json"))
    private val history get() = AtomicFile(File(directory, "history.json"))

    data class Config(
        val enabled: Boolean = false,
        val allowedApps: Set<String> = emptySet(),
        val saveHistory: Boolean = false,
        val destination: String = "",
        val rules: AlertRules = AlertRules(),
    )

    fun loadConfig(): Config = runCatching {
        val o = JSONObject(settings.openRead().bufferedReader().use { it.readText() })
        val apps = o.optJSONArray("apps") ?: JSONArray()
        Config(
            o.optBoolean("enabled", false),
            (0 until apps.length()).mapNotNull { apps.opt(it) as? String }.toSet(),
            o.optBoolean("history", false),
            o.optString("destination"),
            AlertRulesCodec.fromJson(o.optJSONObject("rules")),
        )
    }.getOrDefault(Config())

    fun loadHistory(now: Long): List<AlertEntry> = runCatching {
        AlertHistoryCodec.decode(history.openRead().bufferedReader().use { it.readText() }, now)
    }.getOrDefault(emptyList())

    fun save(config: Config, rows: List<AlertEntry>) {
        directory.mkdirs()
        write(
            settings,
            JSONObject().put("enabled", config.enabled).put("apps", JSONArray(config.allowedApps.toList()))
                .put("history", config.saveHistory).put("destination", config.destination)
                .put("rules", AlertRulesCodec.toJson(config.rules)).toString(),
        )
        if (config.saveHistory) write(history, AlertHistoryCodec.encode(rows).toString()) else history.delete()
    }

    private fun write(file: AtomicFile, text: String) {
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}
