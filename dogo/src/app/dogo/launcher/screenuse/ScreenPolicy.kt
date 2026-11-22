/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.screenuse

import java.text.Normalizer
import java.util.Locale

enum class ScrollDirection { Up, Down, Left, Right }

/** One thing the agent proposes to do on screen. Proposed by the model, so never trusted as is. */
sealed interface ScreenStep {
    data class Tap(val nodeId: String) : ScreenStep

    data class Type(val nodeId: String, val text: String) : ScreenStep

    data class Scroll(val direction: ScrollDirection) : ScreenStep

    data object Back : ScreenStep

    data object Home : ScreenStep

    data object Wait : ScreenStep
}

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision

    /** Ask the user, with a card for this one step, whatever the task approval said. */
    data class NeedsApproval(val reason: String) : PolicyDecision

    data class Denied(val reason: String) : PolicyDecision
}

/**
 * The fixed rules for driving other apps. Pure and stateless.
 *
 * Authority comes only from the approved [ScreenTask], the proposed [ScreenStep] and the fixed
 * lists below. On-screen text is used for exactly one thing: to label the node being acted on, so
 * words like Send or Delete can *add* an approval requirement. No text can remove one, widen the
 * app, or approve anything, and reasons never repeat screen text.
 */
object ScreenPolicy {
    const val NEEDS_OWN_CARD = "Needs its own approval card"
    const val REASON_DENIED_APP = "This app is on the never-drive list"
    const val REASON_OUTSIDE_APP = "Outside the approved app"
    const val REASON_UNKNOWN_NODE = "Unknown screen element"
    const val REASON_PASSWORD = "Never types into password or unreadable fields"
    const val REASON_NOT_INPUT = "Not an input field"
    const val REASON_SENSITIVE_APP = "Sensitive app: needs its own approval card"
    const val REASON_LONG_LABEL = "Label too long to check: needs its own approval card"

    private val DENIED_PREFIXES = listOf(
        // Wallet and payments
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa",
        // Install and permission surfaces
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // Password managers
        "com.x8bit.bitwarden",
        "com.paypal",
        "com.venmo",
        "com.coinbase",
        "com.bitwarden",
        "com.lastpass",
        "com.onepassword",
        "com.agilebits",
        "org.keepassdroid",
        "com.kunzisoft",
        "proton.android.pass",
        "app.dogo.launcher",
    )

    /** Any package containing a segment that starts with "bank" (the glob `*.bank*`). */
    private const val BANK_SEGMENT = ".bank"

    /** Allowed to drive, but every tap or type needs its own card. */
    private val SENSITIVE_EXACT = setOf("com.android.vending")

    private fun normalisePackage(packageName: String?): String = packageName.orEmpty().trim().lowercase(Locale.ROOT)

    fun isDeniedPackage(packageName: String?): Boolean {
        val p = normalisePackage(packageName)
        if (p.isEmpty()) return false
        return DENIED_PREFIXES.any { p.startsWith(it) } ||
            p.startsWith("bank") ||
            p.contains(BANK_SEGMENT)
    }

    fun isSensitivePackage(packageName: String?): Boolean = normalisePackage(packageName) in SENSITIVE_EXACT

    /**
     * Decides one proposed [step] against the approved [task], given the [snapshot] the agent
     * was looking at (needed to resolve node ids and to know which app is in front).
     */
    fun decide(task: ScreenTask, step: ScreenStep, snapshot: ScreenSnapshot): PolicyDecision {
        if (isDeniedPackage(task.appPackage) || isDeniedPackage(snapshot.packageName)) {
            return PolicyDecision.Denied(REASON_DENIED_APP)
        }
        if (snapshot.packageName.trim() != task.appPackage) {
            return PolicyDecision.Denied(REASON_OUTSIDE_APP)
        }
        return when (step) {
            ScreenStep.Back, ScreenStep.Home, ScreenStep.Wait, is ScreenStep.Scroll -> PolicyDecision.Allowed
            is ScreenStep.Tap -> decideOnNode(step.nodeId, snapshot, typing = false)
            is ScreenStep.Type -> decideOnNode(step.nodeId, snapshot, typing = true)
        }
    }

    /**
     * Short cleaned label of the node a Tap or Type acts on, for the approval card. Null for other
     * steps or unknown nodes. This is screen text: show it as data, never act on it.
     */
    fun labelOf(step: ScreenStep, snapshot: ScreenSnapshot): String? {
        val id = when (step) {
            is ScreenStep.Tap -> step.nodeId
            is ScreenStep.Type -> step.nodeId
            else -> return null
        }
        return snapshot.find(id)?.label
    }

    private fun decideOnNode(nodeId: String, snapshot: ScreenSnapshot, typing: Boolean): PolicyDecision {
        val node = snapshot.find(nodeId) ?: return PolicyDecision.Denied(REASON_UNKNOWN_NODE)
        if (typing) {
            if (node.password || !node.readable) return PolicyDecision.Denied(REASON_PASSWORD)
            if (!node.editable) return PolicyDecision.Denied(REASON_NOT_INPUT)
        }
        if (isSensitivePackage(snapshot.packageName)) return PolicyDecision.NeedsApproval(REASON_SENSITIVE_APP)

        // A tap on a container presses whatever it wraps, so judge what is drawn inside it too.
        // Scrollable lists are skipped: a tap lands on one row, not on the whole list.
        val wrapsContent = !typing && !node.scrollable
        val labelled = if (wrapsContent) listOf(node) + containedIn(node, snapshot) else listOf(node)
        for (e in labelled) {
            if (!e.readable) continue
            if (e.clipped) {
                // The label was clipped, so a risky word could be hidden past the cut.
                return PolicyDecision.NeedsApproval(REASON_LONG_LABEL)
            }
            if (matchesSensitiveWord(e.text) || matchesSensitiveWord(e.description)) {
                return PolicyDecision.NeedsApproval(NEEDS_OWN_CARD)
            }
        }
        return PolicyDecision.Allowed
    }

    /** Elements listed after [node] (its possible descendants) that are drawn inside its bounds. */
    private fun containedIn(node: ScreenElement, snapshot: ScreenSnapshot): List<ScreenElement> {
        val b = node.bounds
        val index = snapshot.elements.indexOfFirst { it.id == node.id }
        if (index < 0) return emptyList()
        return snapshot.elements.subList(index + 1, snapshot.elements.size).filter {
            it.bounds.left >= b.left && it.bounds.top >= b.top &&
                it.bounds.right <= b.right && it.bounds.bottom <= b.bottom
        }
    }

    // Verbs that change something outside the screen: send, buy, delete, account changes.
    // Optional endings catch sends, sending, deleted, payment. "sent" is left out on purpose:
    // it is the name of a mail folder, not an action.
    private val SINGLE_WORDS = listOf(
        // English
        "send", "buy", "pay", "purchase", "order", "delete", "remove", "confirm", "submit",
        "post", "transfer", "subscribe", "uninstall", "submitted", "submitting", "transferred", "transferring", "bought",
        // French
        "envoyer", "envoyez", "envoyé", "envoi", "supprimer", "supprimez", "supprimé", "acheter", "achetez", "payer", "payez", "paiement",
        // Spanish
        "enviar", "envia", "envía", "eliminar", "elimina", "borrar", "borra", "comprar", "compra", "pagar", "paga", "pago",
        // German
        "senden", "sende", "löschen", "loschen", "lösche", "kaufen", "kaufe", "bezahlen", "bezahle",
        // Irregular English form
        "paid",
    )

    private const val SUFFIX = "(?:s|es|ed|d|ing|ment|ments)?"
    private const val START = "(?<![\\p{L}\\p{N}])"
    private const val END = "(?![\\p{L}\\p{N}])"

    private val SENSITIVE_REGEX: Regex = run {
        val parts = ArrayList<String>()
        for (w in SINGLE_WORDS) {
            parts += Regex.escape(w) + SUFFIX
            if (w.endsWith("e")) parts += Regex.escape(w.dropLast(1)) + "(?:ing|ed)"
        }
        parts += "sign\\s*out"
        parts += "factory\\s*reset"
        Regex(
            "$START(?:${parts.joinToString("|")})$END",
            setOf(RegexOption.IGNORE_CASE),
        )
    }

    /** True if [raw] contains one of the approval-needed words as a whole word. */
    internal fun matchesSensitiveWord(raw: String): Boolean {
        if (raw.isEmpty()) return false
        val text = Normalizer.normalize(cleanScreenText(raw), Normalizer.Form.NFKC)
            .replace('_', ' ')
            .replace('-', ' ')
        return SENSITIVE_REGEX.containsMatchIn(text)
    }
}
