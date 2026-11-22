/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dogo.launcher.agent.Action
import app.dogo.launcher.voice.CallScreen
import app.dogo.launcher.voice.DogoCall
import app.dogo.launcher.voice.Orb
import kotlinx.coroutines.launch

/**
 * Dogo, the assistant: one place to type, talk, and come back to earlier
 * conversations.
 *
 * Typed and spoken turns land in the same conversation. While a call is on,
 * a strip with the light sits on the composer; it opens to the full call
 * screen. The drawer lists saved conversations, newest first, searchable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogoSetupScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val call by viewModel.call.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var callExpanded by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var inboxOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary

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
        if (uri != null) {
            PhotoCodec.load(context, uri)?.let { viewModel.sendPhoto(it, input) }
                ?: viewModel.showStatus("Couldn't read that photo. Try a different one.")
            input = ""
        }
    }

    // While the chat is on screen it holds the call; leaving ends it a moment
    // later (a mic left open in the background would be a surprise).
    LifecycleResumeEffect(viewModel) {
        Assistant.hold()
        viewModel.reloadOptions()
        viewModel.recheckBlocked()
        onPauseOrDispose { Assistant.release() }
    }
    LaunchedEffect(call.phase) { if (call.phase == DogoCall.Phase.IDLE) callExpanded = false }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.conversationId, state.busy) {
        val count = state.messages.size + (if (state.busy) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val send = {
        if (input.isNotBlank() && !state.busy) {
            viewModel.send(input)
            input = ""
        }
    }
    val talk = {
        if (call.phase != DogoCall.Phase.IDLE || hasMic(context)) {
            viewModel.toggleVoice()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    if (renameOpen) {
        var name by remember { mutableStateOf(ConversationCodec.displayTitle(state.title, state.messages)) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename conversation") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameConversation(state.conversationId, name)
                    renameOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }

    ChatWallpaper(modifier = modifier) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                HistoryDrawer(
                    history = history,
                    openId = state.conversationId,
                    onNew = {
                        viewModel.newConversation()
                        scope.launch { drawer.close() }
                    },
                    onOpen = { id ->
                        viewModel.openConversation(id)
                        scope.launch { drawer.close() }
                    },
                    onDelete = viewModel::deleteConversation,
                    onRename = viewModel::renameConversation,
                )
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent,
                            ),
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawer.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Past conversations")
                                }
                            },
                            title = {
                                Column(Modifier.clickable(enabled = state.messages.isNotEmpty()) { renameOpen = true }) {
                                    Text(
                                        if (state.messages.isEmpty()) "Dogo" else ConversationCodec.displayTitle(state.title, state.messages),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    state.modelName?.let { name ->
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { inboxOpen = !inboxOpen }) {
                                    Icon(Icons.Filled.Notifications, contentDescription = "Notification inbox")
                                }
                                if (state.messages.isNotEmpty()) {
                                    IconButton(onClick = viewModel::newConversation, enabled = !state.busy) {
                                        Icon(Icons.Filled.Add, contentDescription = "New conversation")
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
                            .imePadding()
                            .padding(horizontal = 16.dp),
                    ) {
                        state.status?.let { StatusNote(it, onDismiss = viewModel::dismissStatus) }

                        if (inboxOpen) {
                            Column(Modifier.weight(1f).fillMaxWidth()) {
                                TextButton(onClick = { inboxOpen = false }) { Text("Back to conversation") }
                                app.dogo.launcher.alerts.AlertsInbox(onDiscuss = { context ->
                                    if (viewModel.discussNotification(context)) inboxOpen = false
                                })
                            }
                        } else if (state.messages.isEmpty() && !state.busy) {
                            EmptyState(
                                accent = accent,
                                modifier = Modifier.weight(1f),
                                onSuggestion = { input = it },
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(vertical = 12.dp),
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
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Text("Waiting for ${state.modelName ?: "your agent"}…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Composer(
                            input = input,
                            onInput = { input = it },
                            busy = state.busy,
                            call = call,
                            accent = accent,
                            onSend = send,
                            onTalk = talk,
                            onMute = { viewModel.setVoiceMuted(call.phase != DogoCall.Phase.MUTED) },
                            onExpand = { callExpanded = true },
                            onCamera = {
                                val dir = java.io.File(context.externalCacheDir ?: context.cacheDir, "photos").apply { mkdirs() }
                                val file = java.io.File(dir, "look-${System.currentTimeMillis()}.jpg")
                                photoFile = file
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                runCatching { takePhoto.launch(uri) }
                            },
                            onGallery = {
                                pickPhoto.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        )
                    }
                }

                // The full call, over the chat. Down arrow returns to the strip.
                AnimatedVisibility(callExpanded && call.phase != DogoCall.Phase.IDLE, enter = fadeIn(), exit = fadeOut()) {
                    CallScreen(accent = accent, onClose = { viewModel.stopVoice() }, onMinimize = { callExpanded = false })
                }
            }
        }
    }
}

private fun hasMic(context: android.content.Context) = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
    android.content.pm.PackageManager.PERMISSION_GRANTED

@Composable
private fun EmptyState(accent: Color, modifier: Modifier = Modifier, onSuggestion: (String) -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Orb(DogoCall.Phase.IDLE, 0f, accent, Modifier.size(132.dp))
        Spacer(Modifier.height(18.dp))
        Text("What would you like to change?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Type, send a photo of a phone to copy, or tap the mic and just talk. Your conversations are kept here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
        )
        Spacer(Modifier.height(18.dp))
        listOf("Give me a cool dark home screen", "Add a clock widget", "Tidy my apps into folders").forEach { s ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = chatGlassColor(MaterialTheme.colorScheme.surfaceVariant),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = chatGlassBorder(),
                modifier = Modifier.padding(vertical = 4.dp).clickable { onSuggestion(s) },
            ) { Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
        }
    }
}

/** The text box, the photo buttons, the mic, and the live call strip on top while talking. */
@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    busy: Boolean,
    call: DogoCall.State,
    accent: Color,
    onSend: () -> Unit,
    onTalk: () -> Unit,
    onMute: () -> Unit,
    onExpand: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = chatGlassColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = chatGlassBorder(),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).navigationBarsPadding(),
    ) {
        Column {
            AnimatedVisibility(call.phase != DogoCall.Phase.IDLE, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    CallStrip(call, accent, onMute = onMute, onEnd = onTalk, onExpand = onExpand)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
            TextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (call.active) "Or type while you talk…" else "Message Dogo") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCamera, enabled = !busy) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Take a photo of a phone to copy its look")
                }
                IconButton(onClick = onGallery, enabled = !busy) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Choose a screenshot or photo to copy")
                }
                Spacer(Modifier.weight(1f))
                if (call.phase == DogoCall.Phase.IDLE || call.phase == DogoCall.Phase.ERROR) {
                    IconButton(onClick = onTalk) { Icon(Icons.Filled.Mic, contentDescription = "Talk to Dogo", tint = accent) }
                }
                FilledIconButton(onClick = onSend, enabled = input.isNotBlank() && !busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun CallStrip(call: DogoCall.State, accent: Color, onMute: () -> Unit, onEnd: () -> Unit, onExpand: () -> Unit) {
    val level by DogoCall.level.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Orb(call.phase, level, accent, Modifier.size(44.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                when (call.phase) {
                    DogoCall.Phase.CONNECTING -> "Waking up…"
                    DogoCall.Phase.LISTENING -> "Listening"
                    DogoCall.Phase.THINKING -> "Working on it"
                    DogoCall.Phase.SPEAKING -> "Speaking"
                    DogoCall.Phase.MUTED -> "Muted"
                    DogoCall.Phase.ERROR -> "Couldn't connect"
                    DogoCall.Phase.IDLE -> ""
                },
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            val line = when {
                call.phase == DogoCall.Phase.ERROR -> call.error
                call.caption.isNotBlank() -> call.caption
                call.heard.isNotBlank() -> "“${call.heard}”"
                else -> "Just talk. Tap here for the full view."
            }
            Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onMute, enabled = call.active) {
            val muted = call.phase == DogoCall.Phase.MUTED
            Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = if (muted) "Unmute" else "Mute")
        }
        FilledIconButton(
            onClick = onEnd,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE5484D), contentColor = Color.White),
        ) { Icon(Icons.Filled.CallEnd, contentDescription = "Stop talking") }
        IconButton(onClick = onExpand) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Full view") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryDrawer(
    history: List<ConversationSummary>,
    openId: String,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<ConversationSummary?>(null) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var searching by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    val now = System.currentTimeMillis()
    val shown = history.filter { it.matches(query) }

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = chatGlassColor(MaterialTheme.colorScheme.surfaceContainer, alpha = 0.96f),
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dogo", style = MaterialTheme.typography.headlineSmall)
                Text("Conversations, typed and spoken", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                searching = !searching
                if (!searching) query = ""
            }) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searching) "Stop searching" else "Search conversations") }
        }
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).height(48.dp), shape = RoundedCornerShape(24.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("New conversation", modifier = Modifier.padding(start = 8.dp))
        }
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search what was said") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        if (shown.isEmpty()) {
            Text(
                if (history.isEmpty()) "Nothing yet. Your conversations will show up here." else "No conversation mentions that.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            var section = ""
            shown.forEach { c ->
                val s = ConversationCodec.sectionOf(c.updatedAt, now)
                if (s != section) {
                    section = s
                    item(key = "h-$s") {
                        Text(
                            s.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = c.id) {
                    val open = c.id == openId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (open) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .combinedClickable(onClick = { onOpen(c.id) }, onLongClick = { menuFor = c })
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (c.hasVoice) {
                                    Icon(Icons.Outlined.Mic, contentDescription = "Has voice", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    "${ConversationCodec.whenLabel(c.updatedAt, now)} · ${c.messageCount} msgs",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (c.preview.isNotBlank()) {
                                Text(c.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { confirmDelete = c }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete ${c.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { c ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        renaming = c
                        menuFor = null
                    }) { Text("Rename") }
                    TextButton(onClick = {
                        confirmDelete = c
                        menuFor = null
                    }) { Text("Delete") }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } },
        )
    }
    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    singleLine = true,
                    supportingText = { if (c.named) Text("Leave it empty to use the first message again.") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(c.id, name)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this conversation?") },
            text = { Text("“${c.title}” will be removed from this phone. Changes it made stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(c.id)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun StatusNote(text: String, onDismiss: () -> Unit) {
    Surface(
        color = chatGlassColor(MaterialTheme.colorScheme.secondaryContainer, alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = chatGlassBorder(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onDismiss),
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
    if (message.note) {
        Text(
            message.text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    val alignment = if (message.fromUser) Alignment.End else Alignment.Start
    val bg = chatGlassColor(if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, alpha = 0.92f)
    val foreground = if (message.fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        val photo = message.photo ?: message.photoFile?.let { name ->
            remember(name) { runCatching { android.graphics.BitmapFactory.decodeFile(Assistant.photoFile(name).path) }.getOrNull() }
        }
        photo?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Photo you sent",
                modifier = Modifier.widthIn(max = 200.dp).padding(bottom = 4.dp).clip(RoundedCornerShape(14.dp)),
            )
        }
        val text = stripActionJson(message.text)
        if (!(message.proposals.isNotEmpty() && message.spoken)) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(bg, RoundedCornerShape(16.dp))
                    .border(chatGlassBorder(), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = foreground)
            }
        }
        if (!message.fromUser) AnswerSourceLinks(message.text)
        if (message.spoken) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)) {
                Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" spoken", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    Card(
        modifier = Modifier.widthIn(max = 340.dp).padding(top = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatGlassColor(MaterialTheme.colorScheme.surfaceContainerHigh, alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = chatGlassBorder(),
    ) {
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
