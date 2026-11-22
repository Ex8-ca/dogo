/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

import org.json.JSONArray
import org.json.JSONObject

/** How loudly an alert should reach the user. Higher is louder. */
enum class AlertScore(val level: Int) {
    IGNORE(0),
    DIGEST(1),
    NOTIFY(2),
    INTERRUPT(3),
    ;

    companion object {
        /** The score for [level], clamped into 0..3. */
        fun fromLevel(level: Int): AlertScore = when {
            level <= IGNORE.level -> IGNORE
            level == DIGEST.level -> DIGEST
            level == NOTIFY.level -> NOTIFY
            else -> INTERRUPT
        }
    }
}

/**
 * A daily window, in minutes since midnight. [endMinute] may be smaller than
 * [startMinute], which means the window runs past midnight (22:00 to 07:00).
 * The start is inside the window, the end is not. Equal values are an empty window.
 */
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay >= startMinute && minuteOfDay < endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

/** What the user has told Dogo about which notifications matter. */
data class AlertRules(
    /** Normalised with [SenderKey]. */
    val vips: Set<String> = emptySet(),
    val keywords: Set<String> = emptySet(),
    val ignoredApps: Set<String> = emptySet(),
    /** Normalised with [SenderKey]. */
    val mutedSenders: Set<String> = emptySet(),
    val appOverrides: Map<String, AlertScore> = emptyMap(),
    val quietHours: QuietHours? = null,
)

/** Why an alert got its score, in words the UI can show. Never carries message text. */
sealed class AlertReason {
    abstract fun describe(): String

    data class VipSender(val sender: String) : AlertReason() {
        override fun describe(): String = "VIP: $sender"
    }

    data class Keyword(val word: String) : AlertReason() {
        override fun describe(): String = "Keyword: $word"
    }

    object IgnoredApp : AlertReason() {
        override fun describe(): String = "App is ignored"
    }

    object MutedSender : AlertReason() {
        override fun describe(): String = "Sender is muted"
    }

    data class Noise(val why: String) : AlertReason() {
        override fun describe(): String = "Noise: $why"
    }

    object QuietHours : AlertReason() {
        override fun describe(): String = "Quiet hours"
    }

    object AppOverride : AlertReason() {
        override fun describe(): String = "Your setting for this app"
    }

    object Model : AlertReason() {
        override fun describe(): String = "Scored by the model"
    }

    object Default : AlertReason() {
        override fun describe(): String = "No rule matched"
    }
}

/** Who made the call. */
enum class Decider { RULE, MODEL, DEFAULT }

data class AlertDecision(
    val score: AlertScore,
    val reasons: List<AlertReason>,
    val decidedBy: Decider,
)

/** One way to write a sender's name, so "  Anna: " and "anna" are the same person. */
object SenderKey {
    private val WHITESPACE = Regex("\\s+")
    private val QUOTES = charArrayOf('"', '\'', '“', '”', '‘', '’')

    fun of(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim().lowercase().replace(WHITESPACE, " ")
        s = s.trimEnd(':').trim()
        s = s.trim(*QUOTES).trim()
        s = s.trimEnd(':').trim()
        return s.ifEmpty { null }
    }
}

/** Rules to JSON and back. Reading never throws: bad input gives defaults or is skipped. */
object AlertRulesCodec {
    private const val VERSION = 1
    private const val MAX_MINUTE = 24 * 60 - 1

    fun toJson(rules: AlertRules): JSONObject {
        val json = JSONObject()
            .put("v", VERSION)
            .put("vips", array(rules.vips))
            .put("keywords", array(rules.keywords))
            .put("ignored_apps", array(rules.ignoredApps))
            .put("muted_senders", array(rules.mutedSenders))
            .put(
                "app_overrides",
                JSONObject().apply { rules.appOverrides.forEach { (pkg, score) -> put(pkg, score.name) } },
            )
        rules.quietHours?.let {
            json.put("quiet", JSONObject().put("start", it.startMinute).put("end", it.endMinute))
        }
        return json
    }

    fun fromJson(json: JSONObject?): AlertRules {
        if (json == null) return AlertRules()
        return runCatching {
            AlertRules(
                vips = strings(json.optJSONArray("vips")).mapNotNull(SenderKey::of).toSet(),
                keywords = strings(json.optJSONArray("keywords")).map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                ignoredApps = strings(json.optJSONArray("ignored_apps")).map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                mutedSenders = strings(json.optJSONArray("muted_senders")).mapNotNull(SenderKey::of).toSet(),
                appOverrides = overrides(json.optJSONObject("app_overrides")),
                quietHours = quiet(json.optJSONObject("quiet")),
            )
        }.getOrDefault(AlertRules())
    }

    internal fun scoreByName(name: String?): AlertScore? {
        val wanted = name?.trim()?.uppercase() ?: return null
        return AlertScore.values().firstOrNull { it.name == wanted }
    }

    private fun array(values: Collection<String>): JSONArray = JSONArray().apply { values.forEach { put(it) } }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.opt(i) as? String }
    }

    private fun overrides(obj: JSONObject?): Map<String, AlertScore> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, AlertScore>()
        val names = obj.keys()
        while (names.hasNext()) {
            val pkg = names.next()
            val score = scoreByName(obj.opt(pkg) as? String) ?: continue
            if (pkg.isNotBlank()) out[pkg] = score
        }
        return out
    }

    private fun quiet(obj: JSONObject?): QuietHours? {
        if (obj == null) return null
        val start = (obj.opt("start") as? Number)?.toInt() ?: return null
        val end = (obj.opt("end") as? Number)?.toInt() ?: return null
        if (start !in 0..MAX_MINUTE || end !in 0..MAX_MINUTE) return null
        return QuietHours(start, end)
    }
}
