/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** Open only HTTP(S) links explicitly mentioned in the reply. */
@Composable
internal fun AnswerSourceLinks(text: String) {
    val sources = remember(text) { AnswerSources.extract(text) }
    val opener = LocalUriHandler.current
    if (sources.isEmpty()) return
    Column(Modifier.padding(top = 4.dp)) {
        Text("Links in this answer", style = MaterialTheme.typography.labelSmall)
        sources.forEach { source ->
            TextButton(onClick = { runCatching { opener.openUri(source.url) } }) {
                Text(source.host, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
