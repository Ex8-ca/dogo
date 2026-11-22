/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.dogo.launcher.ui.setup.SetupChatViewModel.ActionState as AS
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.util.Themes
import com.android.launcher3.views.BaseDragLayer
import kotlinx.coroutines.delay

/**
 * The call screen over the home screen: the orb, live captions, change cards
 * and Mute / End. Opened from the light in the Dogo Ring's centre. Back or
 * End hangs up; the call never outlives this view.
 */
@SuppressLint("ViewConstructor")
class DogoCallView private constructor(private val launcher: Launcher) : AbstractFloatingView(launcher, null) {

    init {
        mIsOpen = true
        addView(
            ComposeView(launcher).apply {
                setContent {
                    CallScreen(
                        accent = Color(Themes.getColorAccent(launcher)),
                        onClose = { close(true) },
                        // The same conversation, as text, with the history beside it.
                        onOpenChat = {
                            handedOff = true
                            close(false)
                            launcher.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("lawnchair://settings/dogo-setup"))
                                    .setPackage(launcher.packageName),
                            )
                        },
                    )
                }
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** Set when the call moves into the chat rather than ending. */
    private var handedOff = false

    override fun handleClose(animate: Boolean) {
        // Moving into the chat keeps the call; the chat holds it from here.
        if (!handedOff) DogoCall.stop()
        app.dogo.launcher.ui.setup.Assistant.release()
        animate().alpha(0f).setDuration(if (animate) 160L else 0L).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
        }.start()
    }

    override fun isOfType(type: Int): Boolean = type and TYPE_COMPOSE_VIEW != 0

    // The call owns every touch on the home screen while it's up.
    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean = false

    companion object {
        /** Opens the call screen, or asks for the microphone first. */
        fun open(launcher: Launcher) {
            if (ContextCompat.checkSelfPermission(launcher, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                launcher.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                return
            }
            closeAllOpenViews(launcher)
            val view = DogoCallView(launcher)
            val lp = BaseDragLayer.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.ignoreInsets = true
            view.alpha = 0f
            launcher.dragLayer.addView(view, lp)
            view.animate().alpha(1f).setDuration(220).start()
            app.dogo.launcher.ui.setup.Assistant.hold()
            DogoCall.start(launcher, fresh = true)
        }

        const val REQUEST_MIC = 0x0D060
    }
}

/**
 * The full-screen call: over the home screen, or over the chat when it is
 * expanded there. [onMinimize] (chat only) shrinks it back to the strip;
 * [onOpenChat] (home screen only) carries on in the chat.
 */
@Composable
internal fun CallScreen(
    accent: Color,
    onClose: () -> Unit,
    onMinimize: (() -> Unit)? = null,
    onOpenChat: (() -> Unit)? = null,
) {
    val state by DogoCall.state.collectAsState()
    val level by DogoCall.level.collectAsState()
    val conversation by app.dogo.launcher.ui.setup.Assistant.state.collectAsState()
    val cards = state.cards.mapNotNull { id -> conversation.messages.firstNotNullOfOrNull { m -> m.proposals.firstOrNull { it.id == id } } }
    val ink = Color(0xFF0D0B09)

    // A failed call shows why for a moment, then closes.
    val latestOnClose by androidx.compose.runtime.rememberUpdatedState(onClose)
    LaunchedEffect(state.phase) {
        if (state.phase == DogoCall.Phase.ERROR) {
            delay(4_000)
            DogoCall.clearError()
            latestOnClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFF18130F), ink, ink),
                ),
            )
            .clickable(remember { MutableInteractionSource() }, indication = null) { },
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onMinimize != null) {
                    IconButton(onClick = onMinimize) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Back to the chat", tint = Color(0xFF9C9388)) }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(phaseLabel(state), color = Color(0xFFF1EADF), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (state.active) CallTimer(state.startedAt)
                }
                if (onOpenChat != null) {
                    IconButton(onClick = onOpenChat) { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Open the chat and past conversations", tint = Color(0xFF9C9388)) }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.weight(0.6f))

            val interaction = remember { MutableInteractionSource() }
            Orb(
                state.phase,
                level,
                accent,
                Modifier
                    .size(260.dp)
                    .clickable(interaction, indication = null) {
                        // Tap the light to cut the reply short, like Vessel.
                        if (state.phase == DogoCall.Phase.SPEAKING) DogoCall.interrupt()
                    },
            )

            Spacer(Modifier.height(20.dp))
            val words = when {
                state.phase == DogoCall.Phase.ERROR -> state.error
                state.phase == DogoCall.Phase.THINKING -> workingLine(state)
                state.caption.isNotBlank() -> state.caption
                state.heard.isNotBlank() -> "“${state.heard}”"
                state.phase == DogoCall.Phase.LISTENING -> "Say what you'd like. Change the theme, tidy apps, ask anything."
                else -> ""
            }
            Text(
                words,
                color = if (state.phase == DogoCall.Phase.ERROR) OrbPalette.Coral else Color(0xFFF1EADF),
                fontSize = 18.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 340.dp).height(130.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                cards.forEach { card -> ChangeCard(card, accent) }
            }
            Spacer(Modifier.weight(0.4f))

            Row(
                Modifier.fillMaxWidth().padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val muted = state.phase == DogoCall.Phase.MUTED
                RoundButton(
                    if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    if (muted) "Unmute" else "Mute",
                    Color(0xFF2A231D),
                    enabled = state.active,
                ) { DogoCall.setMuted(!muted) }
                RoundButton(Icons.Filled.CallEnd, "End", Color(0xFFE5484D)) { onClose() }
            }
        }
    }
}

/** Hermes's request is written for Hermes; show the user's own words and a timer instead. */
private fun workingLine(state: DogoCall.State): String {
    val asked = state.heard.takeIf { it.isNotBlank() }?.let { "“$it”\n\n" }.orEmpty()
    return asked + "Looking into it on your computer…"
}

private fun phaseLabel(state: DogoCall.State) = when (state.phase) {
    DogoCall.Phase.IDLE -> "Dogo"
    DogoCall.Phase.CONNECTING -> "Waking up…"
    DogoCall.Phase.LISTENING -> "Listening"
    DogoCall.Phase.THINKING -> "Working on it"
    DogoCall.Phase.SPEAKING -> "Speaking · tap the light to interrupt"
    DogoCall.Phase.MUTED -> "Muted"
    DogoCall.Phase.ERROR -> "Couldn't connect"
}

@Composable
private fun CallTimer(startedAt: Long) {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val secs = ((now.longValue - startedAt) / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(secs / 60, secs % 60), color = Color(0xFF9C9388), fontSize = 13.sp)
}

@Composable
private fun ChangeCard(card: app.dogo.launcher.ui.setup.SetupChatViewModel.Proposal, accent: Color) {
    val title = app.dogo.launcher.ui.setup.describeAction(card.action)
    AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1511), RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFFF1EADF), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val (note, color) = when (val s = card.state) {
                    is AS.Ready -> s.route to Color(0xFF9C9388)
                    is AS.Blocked -> s.hint to OrbPalette.Coral
                    AS.Disallowed -> "Switched off in Dogo Options." to OrbPalette.Coral
                    AS.Applying -> "Applying…" to Color(0xFF9C9388)
                    is AS.Applied -> "✓ ${s.message}" to OrbPalette.Jade
                    is AS.Failed -> s.message to OrbPalette.Coral
                    AS.Dismissed -> "Dismissed" to Color(0xFF9C9388)
                }
                Text(note, color = color, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when (card.state) {
                is AS.Ready -> {
                    TextButton(onClick = { DogoCall.dismissCard(card.id) }) { Text("No", color = Color(0xFF9C9388)) }
                    TextButton(onClick = { DogoCall.approve(card.id) }) { Text("Apply", color = accent, fontWeight = FontWeight.SemiBold) }
                }

                is AS.Blocked, is AS.Failed, AS.Disallowed ->
                    TextButton(onClick = { DogoCall.dismissCard(card.id) }) { Text("OK", color = Color(0xFF9C9388)) }

                else -> Unit
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .background(if (enabled) color else color.copy(alpha = 0.4f), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = Color.White) }
        Text(label, color = Color(0xFF9C9388), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
