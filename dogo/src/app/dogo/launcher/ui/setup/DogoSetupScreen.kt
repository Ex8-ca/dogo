/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

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
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.AgentOptions

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

    // Pick up an agent changed in Dogo Options when returning to the chat.
    LifecycleResumeEffect(viewModel) {
        viewModel.reloadOptions()
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
                    MessageBubble(message, state.options)
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
                placeholder = { Text("e.g. make it dark and tidy my apps") },
                maxLines = 4,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
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
private fun MessageBubble(message: SetupChatViewModel.Message, options: AgentOptions) {
    val alignment = if (message.fromUser) Alignment.End else Alignment.Start
    val bg = if (message.fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (message.proposedActions.isNotEmpty()) {
            message.proposedActions.forEach { action ->
                ProposedActionCard(action, options)
            }
        }
    }
}

@Composable
private fun ProposedActionCard(action: Action, options: AgentOptions) {
    val title = when (action) {
        is Action.SetTheme -> "Set theme to ${action.theme}"
        is Action.SetWallpaper -> "Set wallpaper to ${action.color}"
        is Action.AddToHome -> "Add ${action.pkg} to home"
        is Action.HideApp -> "Hide ${action.pkg}"
        is Action.CreateFolder -> "Create folder \"${action.name}\""
        is Action.SetAnimationScale -> "Set animations to ${action.scale}x"
        Action.Unknown -> return
    }
    Card(modifier = Modifier.widthIn(max = 320.dp).padding(top = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Proposed change", style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    !options.isAllowed(action) -> "Switched off in Dogo Options"
                    options.needsConfirmation(action) -> "Tap to approve"
                    else -> "Allowed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
