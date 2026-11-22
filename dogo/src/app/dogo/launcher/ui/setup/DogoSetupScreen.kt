/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dogo.launcher.agent.Action

/**
 * The conversational setup assistant.
 *
 * This is the surface that makes the AI reachable at all: everything underneath
 * it (model download, on-device inference, action parsing) existed before this,
 * but had no way for a person to open it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogoSetupScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.toggleVoice() }
    // Photos of another phone (or a screenshot) for "make mine look like this".
    var photoFile by remember { mutableStateOf<java.io.File?>(null) }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        val file = photoFile
        if (ok && file != null) {
            PhotoCodec.load(context, android.net.Uri.fromFile(file))?.let { viewModel.sendPhoto(it, input) }
                ?: viewModel.showStatus("Couldn't read that photo. Try again, or pick it from your gallery.")
            input = ""
            file.delete()
        }
    }
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        android.util.Log.i("DogoPhoto", "picked $uri")
        if (uri != null) {
            PhotoCodec.load(context, uri)?.let { viewModel.sendPhoto(it, input) }
                ?: viewModel.showStatus("Couldn't read that photo. Try a different one.")
            input = ""
        }
    }
    // Leaving the screen ends the call; a mic left open in the background would be a surprise.
    androidx.compose.runtime.DisposableEffect(viewModel) { onDispose { viewModel.stopVoice() } }

    // Pick up an agent changed in Dogo Options when returning to the chat.
    LifecycleResumeEffect(viewModel) {
        viewModel.reloadOptions()
        viewModel.recheckBlocked()
        onPauseOrDispose { }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Dogo Setup")
                        state.modelName?.let { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            state.status?.let { StatusNote(it) }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                items(state.messages) { message ->
                    MessageBubble(
                        message = message,
                        onApprove = viewModel::approve,
                        onDismiss = viewModel::dismiss,
                    )
                }
                if (state.busy) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "Thinking…",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("e.g. add a clock widget, or send a photo of a phone to copy") },
                maxLines = 4,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceControls(
                    voice = state.voice,
                    onToggle = {
                        if (state.voice != SetupChatViewModel.VoiceState.Off || hasMic(context)) {
                            viewModel.toggleVoice()
                        } else {
                            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onMute = viewModel::setVoiceMuted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val dir = java.io.File(context.externalCacheDir ?: context.cacheDir, "photos").apply { mkdirs() }
                        val file = java.io.File(dir, "look-${System.currentTimeMillis()}.jpg")
                        photoFile = file
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        runCatching { takePhoto.launch(uri) }
                    },
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Take a photo of a phone to copy its look")
                }
                IconButton(
                    onClick = {
                        pickPhoto.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Choose a screenshot or photo to copy")
                }
                IconButton(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !state.busy,
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

private fun hasMic(context: android.content.Context) = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
    android.content.pm.PackageManager.PERMISSION_GRANTED

@Composable
private fun VoiceControls(
    voice: SetupChatViewModel.VoiceState,
    onToggle: () -> Unit,
    onMute: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val live = voice as? SetupChatViewModel.VoiceState.Live
        val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "voice")
        val scale by pulse.animateFloat(
            initialValue = 1f,
            targetValue = if (live?.speaking == true) 1.25f else 1.08f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(if (live?.speaking == true) 350 else 1200),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        androidx.compose.material3.FilledIconButton(
            onClick = onToggle,
            modifier = Modifier.size(52.dp).then(if (live != null) Modifier.graphicsLayer(scaleX = scale, scaleY = scale) else Modifier),
            colors = if (voice == SetupChatViewModel.VoiceState.Off) {
                androidx.compose.material3.IconButtonDefaults.filledIconButtonColors()
            } else {
                androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
            },
        ) {
            when (voice) {
                SetupChatViewModel.VoiceState.Off -> Icon(androidx.compose.material.icons.Icons.Filled.Mic, contentDescription = "Talk to Dogo")
                SetupChatViewModel.VoiceState.Connecting -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                is SetupChatViewModel.VoiceState.Live -> Icon(androidx.compose.material.icons.Icons.Filled.CallEnd, contentDescription = "Stop talking")
            }
        }
        Text(
            when {
                voice == SetupChatViewModel.VoiceState.Connecting -> "Connecting…"
                live?.muted == true -> "Muted"
                live?.speaking == true -> "Listening…"
                live != null -> "Live. Just talk."
                else -> "Talk"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        if (live != null) {
            IconButton(onClick = { onMute(!live.muted) }) {
                Icon(
                    if (live.muted) androidx.compose.material.icons.Icons.Filled.MicOff else androidx.compose.material.icons.Icons.Filled.Mic,
                    contentDescription = if (live.muted) "Unmute" else "Mute",
                )
            }
        }
    }
}

@Composable
private fun StatusNote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    message: SetupChatViewModel.Message,
    onApprove: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    val alignment = if (message.fromUser) Alignment.End else Alignment.Start
    val bg = if (message.fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        message.photo?.let { photo ->
            androidx.compose.foundation.Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "Photo you sent",
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(stripActionJson(message.text), style = MaterialTheme.typography.bodyMedium)
        }
        message.proposals.forEach { proposal ->
            ProposedActionCard(proposal, onApprove, onDismiss)
        }
    }
}

/**
 * Hides the raw JSON lines from the bubble: each one is shown as a card
 * underneath instead, so it isn't said twice.
 */
internal fun stripActionJson(text: String): String = text.lines()
    .filterNot { line ->
        val t = line.trim()
        t.startsWith("{") && t.endsWith("}") && t.contains("\"action\"")
    }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
    .ifEmpty { text.trim() }

/** One-line, plain-language title for a proposed change. */
fun describeAction(action: Action): String = describe(action) ?: "Change something"

internal fun describe(action: Action): String? = when (action) {
    is Action.SetTheme -> "Set Dogo's theme to ${action.theme}"

    is Action.SetSystemDarkMode -> if (action.enabled) "Turn on the phone's dark mode" else "Turn off the phone's dark mode"

    is Action.SetWallpaper -> if (action.color2 == null) "Set wallpaper to ${action.color}" else "Gradient wallpaper ${action.color} → ${action.color2}"

    is Action.AddWidget -> "Add a widget: ${action.provider.substringAfterLast('.').substringAfterLast('/')}" +
        (action.size?.let { " ($it)" } ?: "") + (action.page?.let { " on page ${it + 1}" } ?: "")

    is Action.StyleHome -> "Home screen: ${action.style.clamped().describe().ifEmpty { "no change" }}"

    is Action.SetDock -> "Dock: ${action.apps.joinToString { it.substringAfterLast('.') }}"

    is Action.AddToHome -> "Add ${action.pkg} to home"

    is Action.HideApp -> "Hide ${action.pkg}"

    is Action.CreateFolder -> "Create folder \"${action.name}\" (${action.apps.size} apps)"

    is Action.SetAnimationScale -> "Set animations to ${action.scale}x"

    is Action.ArrangeRing -> buildString {
        if (action.groups.isNotEmpty()) append("Arrange the ring: ${action.groups.joinToString { "${it.name} (${it.apps.size})" }}")
        if (action.style != null) append(if (isEmpty()) "Set the ring style to ${action.style}" else ", ${action.style} style")
    }

    is Action.Plugin -> "${action.name.replace('_', ' ').replaceFirstChar { it.uppercase() }} (${action.pluginId})"

    Action.Unknown -> null
}

@Composable
private fun ProposedActionCard(
    proposal: SetupChatViewModel.Proposal,
    onApprove: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    val title = describe(proposal.action) ?: return
    val state = proposal.state
    Card(modifier = Modifier.widthIn(max = 320.dp).padding(top = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Proposed change", style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.bodyMedium)
            val (note, isError) = when (state) {
                is SetupChatViewModel.ActionState.Ready -> "Uses: ${state.route}" to false
                SetupChatViewModel.ActionState.Disallowed -> "Switched off in Dogo Options" to true
                is SetupChatViewModel.ActionState.Blocked -> state.hint to true
                SetupChatViewModel.ActionState.Applying -> "Applying…" to false
                is SetupChatViewModel.ActionState.Applied -> "✓ ${state.message}" to false
                is SetupChatViewModel.ActionState.Failed -> state.message to true
                SetupChatViewModel.ActionState.Dismissed -> "Dismissed" to false
            }
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state is SetupChatViewModel.ActionState.Ready) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { onDismiss(proposal.id) }) { Text("Dismiss") }
                    Button(onClick = { onApprove(proposal.id) }) { Text("Apply") }
                }
            }
        }
    }
}
