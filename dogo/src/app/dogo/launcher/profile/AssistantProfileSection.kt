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
import app.dogo.launcher.agent.backend.AgentCatalog
import app.dogo.launcher.agent.backend.resolve
import app.dogo.launcher.ui.options.OptionsStore
import app.dogo.launcher.voice.HermesHandoff
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
            label = { Text("How should Dogo talk? (optional)") },
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
        TextButton(
            enabled = !asking,
            onClick = {
                asking = true
                status = "Asking your Hermes agent…"
                scope.launch {
                    val hermes = OptionsStore(context).load().agentConfigs[AgentCatalog.HERMES.id]
                        ?.let { AgentCatalog.HERMES.resolve(it) }
                        ?.takeIf { it.problems().isEmpty() && !it.apiKey.isNullOrBlank() }
                    if (hermes == null) {
                        status = "Set up Hermes Agent first (Dogo Options > Agent)."
                    } else {
                        val reply = HermesHandoff(hermes.baseUrl, hermes.apiKey!!, timeoutMs = 60_000).ask(ProfileText.IMPORT_REQUEST)
                        val found = ProfileText.parseImport(reply)
                        if (found == null) {
                            status = "Hermes didn't answer in a form Dogo can use. Nothing was changed."
                        } else {
                            update(profile.copy(name = found.name.ifEmpty { profile.name }, style = found.style.ifEmpty { profile.style }))
                            status = "Filled in from your Hermes agent. Check it, and change anything you like."
                        }
                    }
                    asking = false
                }
            },
        ) { Text("Fill in from my Hermes agent") }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
