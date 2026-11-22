/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Who Dogo is talking to, and how it should sound. Used by typed chat and by
 * calls. Leaving it empty is fine: Dogo then neither uses a name nor guesses one.
 */
@Composable
fun AssistantProfileSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember { ProfileStore(context) }
    var profile by remember { mutableStateOf(store.load()) }
    var status by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun update(next: AssistantProfile) {
        profile = next
        store.save(next)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Tell Dogo what to call you and how to talk to you. Calls greet you by name. " +
                "This text is sent to your chosen agent, and to OpenAI during voice calls.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = profile.name,
            onValueChange = { update(profile.copy(name = it.take(ProfileText.MAX_NAME))) },
            label = { Text("What should Dogo call you?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = profile.style,
            onValueChange = { update(profile.copy(style = it.take(ProfileText.MAX_STYLE))) },
            label = { Text("Anything to add to its personality? (optional)") },
            placeholder = { Text("Short and direct, a little dry humour") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Remember earlier chats on calls", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Gives the voice the titles and last lines of your recent conversations, so a call can pick up where you left off. Chats that include notifications are never shared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = profile.recallEarlier, onCheckedChange = { update(profile.copy(recallEarlier = it)) })
        }
        val soulStore = remember { SoulStore.of(context) }
        var soul by remember { mutableStateOf(soulStore.read()) }
        Text(
            if (soul.source == SoulStore.Source.HERMES) {
                "Personality: copied from your Hermes agent (" + soul.text.length + " characters), kept on this phone."
            } else {
                "Personality: Dogo's own. Connect Hermes and Dogo copies yours over it."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !asking,
                onClick = {
                    asking = true
                    status = "Asking your Hermes agent…"
                    scope.launch {
                        val result = SoulSync.sync(context)
                        profile = store.load()
                        soul = soulStore.read()
                        status = when (result) {
                            is SoulSync.Result.Synced -> "Copied your Hermes personality (" + result.chars + " characters). Your own notes below are kept."
                            is SoulSync.Result.Nothing -> result.why
                            is SoulSync.Result.Failed -> result.why
                        }
                        asking = false
                    }
                },
            ) { Text("Copy from my Hermes agent") }
            if (soul.source == SoulStore.Source.HERMES) {
                TextButton(onClick = {
                    soulStore.reset()
                    soul = soulStore.read()
                    status = "Back to Dogo's own personality."
                }) { Text("Use Dogo's own") }
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
