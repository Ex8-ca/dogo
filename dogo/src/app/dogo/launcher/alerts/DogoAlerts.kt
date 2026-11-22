/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.dogo.launcher.ui.options.OptionsStore
import com.android.launcher3.notification.NotificationListener
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Phone-side pipeline. Android callbacks copy bounded fields, never perform inference or network IO. */
object DogoAlerts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _history = MutableStateFlow<List<AlertEntry>>(emptyList())
    val history: StateFlow<List<AlertEntry>> = _history.asStateFlow()
    private val _historyCount = MutableStateFlow(0)
    val historyCount: StateFlow<Int> = _historyCount.asStateFlow()
    internal val config = MutableStateFlow(AlertStore.Config())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private lateinit var app: Context
    private lateinit var store: AlertStore
    private val queue = Channel<Work>(16)
    private val saves = Channel<Pair<AlertStore.Config, List<AlertEntry>>>(Channel.CONFLATED)
    private val live = LinkedHashMap<String, Handle>()
    private val versions = LinkedHashMap<String, Any>()
    private var generation = 0L
    private val requests = ArrayDeque<Long>()
    private var scorerFailed = false
    private var currentWork: Work? = null
    private data class Work(val candidate: AlertCandidate, val version: Any, val generation: Long)
    private data class Handle(val open: PendingIntent?)
    private lateinit var engine: AlertEngine

    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = AlertStore(app)
        config.value = store.loadConfig()
        engine = AlertEngine(app.packageName) { candidate ->
            val work = currentWork
            val options = withContext(Dispatchers.Main.immediate) {
                if (work == null || !valid(work)) return@withContext null
                val selected = OptionsStore(app).load()
                if (AlertScorer.destination(selected) != config.value.destination) {
                    _message.value = "Selected agent changed. Review the destination and enable alerts again."
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                while (requests.firstOrNull()?.let { now - it >= 60 * 60 * 1000L } == true) requests.removeFirst()
                if (requests.size >= 20) return@withContext null
                requests.addLast(now)
                selected
            }
            if (options == null) {
                scorerFailed = true
                null
            } else {
                try {
                    val result = withTimeoutOrNull(45_000) {
                        AlertScorer.classify(app, options, candidate) { work != null && valid(work) }
                    }
                    if (result == null) scorerFailed = true
                    result
                } catch (_: Exception) {
                    scorerFailed = true
                    null
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for ((settings, rows) in saves) {
                try {
                    store.save(settings, rows)
                } catch (_: Exception) {
                    _message.value = "Could not save alert settings/history. Check storage space."
                }
            }
        }
        scope.launch {
            val settings = config.value
            val epoch = generation
            if (settings.saveHistory) {
                val saved = withContext(Dispatchers.IO) { store.loadHistory(System.currentTimeMillis()) }
                if (generation == epoch && config.value.saveHistory) {
                    publish((_history.value + saved).distinctBy { it.candidate.key })
                }
            } else {
                persist()
            }
            while (true) {
                delay(60_000)
                publish(_history.value)
            }
        }
        scope.launch {
            for (work in queue) {
                if (!valid(work)) continue
                currentWork = work
                scorerFailed = false
                val calendar = Calendar.getInstance()
                val rules = withContext(Dispatchers.Main.immediate) { config.value.rules }
                val decision = engine.evaluate(work.candidate, rules, calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE), System.currentTimeMillis())
                val failed = scorerFailed
                withContext(Dispatchers.Main.immediate) {
                    if (!valid(work)) return@withContext
                    if (decision == null) {
                        update(work.candidate.key) { it.copy(status = "Unchanged update · kept quietly") }
                    } else {
                        update(work.candidate.key) {
                            it.copy(score = decision.score, status = if (failed) "Analysis unavailable or rate-limited · kept in inbox" else decision.reasons.joinToString { reason -> reason.describe() })
                        }
                        if (decision.score.level < AlertScore.NOTIFY.level || failed) AlertNotifier.cancel(app, work.candidate.key)
                        if (decision.score.level >= AlertScore.NOTIFY.level && !failed) {
                            runCatching { AlertNotifier.post(app, work.candidate, decision.score) }
                                .onFailure { _message.value = "Could not show Dogo alert. The notification remains in the inbox." }
                        }
                    }
                }
                currentWork = null
            }
        }
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        init(context)
        return config.value.enabled
    }

    @JvmStatic
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        init(context)
        if (!config.value.enabled || sbn.packageName !in config.value.allowedApps || sbn.packageName == app.packageName) return
        runCatching {
            val n = sbn.notification
            if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0 || n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(256)
            val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(4000)
            if (title.isBlank() && text.isBlank()) return
            val candidate = AlertCandidate(
                sbn.key.take(512), sbn.packageName, sbn.packageName, title, text, title,
                n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.take(256),
                n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION), n.category, System.currentTimeMillis(),
            )
            val handle = Handle(n.contentIntent)
            // Callback must not retain Notification/extras/RemoteViews. Cap retained PendingIntents as well.
            scope.launch {
                if (!config.value.enabled || candidate.packageName !in config.value.allowedApps) return@launch
                live[candidate.key] = handle
                val old = _history.value.firstOrNull { it.candidate.key == candidate.key }
                if (old?.candidate?.let { it.title == candidate.title && it.text == candidate.text } == true) {
                    update(candidate.key) { it.copy(active = true) }
                    return@launch
                }
                val version = Any()
                versions[candidate.key] = version
                publish(listOf(AlertEntry(candidate)) + _history.value.filterNot { it.candidate.key == candidate.key })
                if (queue.trySend(Work(candidate, version, generation)).isFailure) {
                    update(candidate.key) { it.copy(status = "Analysis queue full · kept in inbox") }
                }
            }
        }
    }

    @JvmStatic
    fun onRemoved(context: Context, sbn: StatusBarNotification) {
        init(context)
        val key = sbn.key.take(512)
        scope.launch {
            live.remove(key)
            // Keep text but expire capabilities, including an in-flight classification's publication.
            versions.remove(key)
            update(key) { it.copy(active = false, status = "Removed from status bar · actions expired") }
            AlertNotifier.cancel(app, key)
        }
    }

    @JvmStatic
    fun onDisconnected(context: Context) {
        init(context)
        scope.launch {
            generation++
            discardQueued()
            live.clear()
            versions.clear()
            publish(_history.value.map { it.copy(active = false, status = "Listener disconnected · actions expired") })
        }
    }

    internal fun configure(settings: AlertStore.Config) {
        generation++
        discardQueued()
        engine.clear()
        config.value = settings
        if (!settings.enabled) live.clear()
        val retained = _history.value.filter { it.candidate.packageName in settings.allowedApps }
        publish(
            retained.map {
                when {
                    !settings.enabled -> it.copy(active = false, status = "Assistant disabled · actions expired")
                    it.score == null -> it.copy(status = "Settings changed · analysis skipped")
                    else -> it
                }
            },
        )
        AlertNotifier.cancelAll(app)
        if (settings.enabled) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(app, NotificationListener::class.java))
            }
        }
    }

    fun clear() {
        generation++
        discardQueued()
        engine.clear()
        live.clear()
        versions.clear()
        publish(emptyList())
        AlertNotifier.cancelAll(app)
    }

    fun delete(key: String) {
        live.remove(key)
        versions.remove(key)
        publish(_history.value.filterNot { it.candidate.key == key })
        AlertNotifier.cancel(app, key)
    }

    /** Uses only the live originating content intent; expired history can still open the app. */
    fun openApp(context: Context, key: String): Boolean {
        val row = _history.value.firstOrNull { it.candidate.key == key } ?: return false
        val intent = live[key]?.open
        if (intent != null && runCatching { intent.send() }.isSuccess) return true
        return runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(row.candidate.packageName) ?: return false
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun valid(work: Work): Boolean = config.value.enabled && work.generation == generation &&
        work.candidate.packageName in config.value.allowedApps && versions[work.candidate.key] === work.version

    private fun update(key: String, change: (AlertEntry) -> AlertEntry) = publish(_history.value.map { if (it.candidate.key == key) change(it) else it })

    private fun publish(rows: List<AlertEntry>) {
        val now = System.currentTimeMillis()
        _history.value = rows.filter { now - it.candidate.postedAt in 0..AlertHistoryCodec.RETENTION_MS }
            .sortedByDescending { it.candidate.postedAt }.take(AlertHistoryCodec.MAX_ROWS)
        _historyCount.value = _history.value.size
        val keys = _history.value.map { it.candidate.key }.toSet()
        live.keys.retainAll(keys)
        versions.keys.retainAll(keys)
        persist()
    }

    private fun persist() {
        saves.trySend(config.value to if (config.value.saveHistory) _history.value.toList() else emptyList())
    }

    private fun discardQueued() {
        while (queue.tryReceive().isSuccess) { /* Release stale notification snapshots. */ }
    }
}
