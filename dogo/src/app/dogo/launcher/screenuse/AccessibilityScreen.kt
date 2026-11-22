/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.screenuse

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import app.lawnchair.lawnchairApp
import kotlinx.coroutines.delay

/** A real accessibility node seen as a [ScreenNode]. Children are read only when asked for. */
internal class AccessibilityScreenNode(val info: AccessibilityNodeInfo) : ScreenNode {
    override val className: String? get() = info.className?.toString()

    // A password node's text is never asked for.
    override val text: String? get() = if (info.isPassword) null else info.text?.toString()
    override val contentDescription: String? get() = if (info.isPassword) null else info.contentDescription?.toString()
    override val bounds: ScreenBounds
        get() = Rect().also { info.getBoundsInScreen(it) }.let { ScreenBounds(it.left, it.top, it.right, it.bottom) }
    override val isVisibleToUser: Boolean get() = info.isVisibleToUser
    override val isClickable: Boolean get() = info.isClickable
    override val isEditable: Boolean get() = info.isEditable
    override val isScrollable: Boolean get() = info.isScrollable
    override val isPassword: Boolean get() = info.isPassword
    override val isFocused: Boolean get() = info.isFocused
    override val children: List<ScreenNode>
        get() = (0 until info.childCount).mapNotNull { info.getChild(it)?.let(::AccessibilityScreenNode) }
}

/**
 * Drives the phone through the Dogo accessibility service. Every action first re-reads the screen
 * and checks that the element is still what the agent was shown; if not, it fails rather than
 * pressing something else.
 */
internal class AccessibilityScreenDriver(private val context: Context) : ScreenDriver {
    private val service: AccessibilityService? get() = context.applicationContext.lawnchairApp.accessibilityService

    override val isReady: Boolean get() = service != null

    override val isLocked: Boolean
        get() {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            return keyguard?.isKeyguardLocked == true || power?.isInteractive == false
        }

    override suspend fun open(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess.also { delay(SETTLE_MS) }
    }

    override suspend fun snapshot(): ScreenSnapshot? = read()?.first

    /** The snapshot and the real node behind each element id. */
    private fun read(): Pair<ScreenSnapshot, Map<String, AccessibilityNodeInfo>>? {
        val root = service?.rootInActiveWindow ?: return null
        val nodes = HashMap<String, AccessibilityNodeInfo>()
        val snap = ScreenSnapshot.build(root.packageName?.toString(), AccessibilityScreenNode(root)) { node, element ->
            (node as? AccessibilityScreenNode)?.let { nodes[element.id] = it.info }
        }
        return snap to nodes
    }

    override suspend fun perform(step: ScreenStep, seen: ScreenSnapshot): StepResult {
        val svc = service ?: return StepResult.Failed("The accessibility service is off.")
        val result = when (step) {
            ScreenStep.Back -> global(svc, AccessibilityService.GLOBAL_ACTION_BACK)
            ScreenStep.Home -> global(svc, AccessibilityService.GLOBAL_ACTION_HOME)
            ScreenStep.Wait -> StepResult.Done
            is ScreenStep.Scroll -> onElement(seen, null) { _, nodes -> scroll(step.direction, nodes) }
            is ScreenStep.Tap -> onElement(seen, step.nodeId) { node, _ -> click(node) }
            is ScreenStep.Type -> onElement(seen, step.nodeId) { node, _ -> type(node, step.text) }
        }
        delay(SETTLE_MS)
        return result
    }

    private fun global(svc: AccessibilityService, action: Int) = if (svc.performGlobalAction(action)) StepResult.Done else StepResult.Failed("Android refused that.")

    /** Re-reads the screen; the element must still match what the agent was shown. */
    private fun onElement(seen: ScreenSnapshot, id: String?, act: (AccessibilityNodeInfo?, Map<String, AccessibilityNodeInfo>) -> Boolean): StepResult {
        val (now, nodes) = read() ?: return StepResult.Failed("Couldn't read the screen.")
        if (now.packageName != seen.packageName) return StepResult.Failed("The app changed.")
        var node: AccessibilityNodeInfo? = null
        if (id != null) {
            val before = seen.find(id) ?: return StepResult.Failed("That element isn't on screen.")
            val after = now.find(id)
            if (after == null || after.role != before.role || after.text != before.text || after.description != before.description || after.bounds != before.bounds) {
                return StepResult.Failed("The screen changed since it was read.")
            }
            node = nodes[id]
        }
        return if (act(node, nodes)) StepResult.Done else StepResult.Failed("That didn't work.")
    }

    /**
     * Presses exactly the element the policy judged. It never hops to a clickable parent: a
     * parent can wrap controls the policy never saw (a Delete button next to a label).
     */
    private fun click(node: AccessibilityNodeInfo?): Boolean = node != null && node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    private fun type(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null || !node.isEditable || node.isPassword) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.take(MAX_TYPED)) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scroll(direction: ScrollDirection, nodes: Map<String, AccessibilityNodeInfo>): Boolean {
        val target = nodes.values.firstOrNull { it.isScrollable } ?: return false
        val forward = direction == ScrollDirection.Down || direction == ScrollDirection.Right
        return target.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private companion object {
        const val SETTLE_MS = 700L
        const val MAX_TYPED = 500
    }
}
