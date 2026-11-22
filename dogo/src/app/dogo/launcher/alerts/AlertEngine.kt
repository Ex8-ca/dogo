/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** Serial worker policy. Null means filtered/unchanged, never a missing model response. */
class AlertEngine(
    private val ownPackage: String,
    private val score: suspend (AlertCandidate) -> AlertScore?,
) {
    private val fingerprints = LinkedHashMap<String, List<Byte>>()
    private val senders = LinkedHashMap<String, Long>()

    suspend fun evaluate(candidate: AlertCandidate, rules: AlertRules, minuteOfDay: Int, now: Long): AlertDecision? {
        if (candidate.packageName == ownPackage || candidate.ongoing || candidate.groupSummary || candidate.media) return null
        if (candidate.title.isBlank() && candidate.text.isBlank()) return null
        // Keep no preview copy in the dedupe cache, which can outlive the inbox row.
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            listOf(candidate.packageName, candidate.title, candidate.text, candidate.sender, candidate.conversation)
                .joinToString("\u0000").toByteArray(Charsets.UTF_8),
        ).toList()
        if (fingerprints[candidate.key] == fingerprint) return null
        fingerprints[candidate.key] = fingerprint
        trim(fingerprints)
        val sender = SenderKey.of(candidate.sender ?: candidate.title)
        val senderId = "${candidate.packageName}:$sender"
        val reason: AlertReason
        val decidedBy: Decider
        var result: AlertScore
        val keyword = rules.keywords.firstOrNull {
            it.isNotBlank() && (candidate.title.contains(it, true) || candidate.text.contains(it, true))
        }
        when {
            candidate.packageName in rules.ignoredApps -> {
                result = AlertScore.IGNORE
                reason = AlertReason.IgnoredApp
                decidedBy = Decider.RULE
            }

            sender != null && sender in rules.mutedSenders -> {
                result = AlertScore.IGNORE
                reason = AlertReason.MutedSender
                decidedBy = Decider.RULE
            }

            candidate.packageName in rules.appOverrides -> {
                result = rules.appOverrides.getValue(candidate.packageName)
                reason = AlertReason.AppOverride
                decidedBy = Decider.RULE
            }

            senders[senderId]?.let { now - it in 0 until SENDER_COOLDOWN_MS } == true -> {
                result = AlertScore.DIGEST
                reason = AlertReason.Noise("sender cooldown")
                decidedBy = Decider.RULE
            }

            sender != null && sender in rules.vips -> {
                result = AlertScore.INTERRUPT
                reason = AlertReason.VipSender(sender)
                decidedBy = Decider.RULE
            }

            keyword != null -> {
                result = AlertScore.NOTIFY
                reason = AlertReason.Keyword(keyword)
                decidedBy = Decider.RULE
            }

            else -> {
                val scored = try {
                    score(candidate)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                result = scored ?: AlertScore.DIGEST
                reason = if (scored != null) AlertReason.Model else AlertReason.Default
                decidedBy = if (scored != null) Decider.MODEL else Decider.DEFAULT
            }
        }
        if (result.level >= AlertScore.NOTIFY.level) {
            senders[senderId] = now
            trim(senders)
        }
        val quiet = rules.quietHours?.contains(minuteOfDay) == true && result.level > AlertScore.DIGEST.level
        if (quiet) result = AlertScore.DIGEST
        return AlertDecision(result, listOfNotNull(reason, AlertReason.QuietHours.takeIf { quiet }), decidedBy)
    }

    fun clear() {
        fingerprints.clear()
        senders.clear()
    }

    private fun <T> trim(map: LinkedHashMap<String, T>) {
        while (map.size > 256) map.remove(map.keys.first())
    }

    companion object {
        const val SENDER_COOLDOWN_MS = 60_000L
    }
}
