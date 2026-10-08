package app.dogo.launcher.ui.options

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.llm.ModelCatalog

/**
 * Lets the user choose which model Dogo uses, where it runs, and what the
 * agent is allowed to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DogoOptionsScreen(
    modifier: Modifier = Modifier,
    viewModel: DogoOptionsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Dogo Options") }) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.recommendation?.let { rec ->
                RecommendationCard(
                    rationale = rec.rationale,
                    ramGb = rec.ramGb,
                    freeStorageGb = rec.freeStorageGb,
                )
            }

            state.issues.forEach { issue ->
                IssueCard(issue)
            }

            SectionLabel("Where it runs")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val runtimes = AgentOptions.Runtime.entries
                runtimes.forEachIndexed { index, runtime ->
                    SegmentedButton(
                        selected = state.options.runtime == runtime,
                        onClick = { viewModel.setRuntime(runtime) },
                        shape = SegmentedButtonDefaults.itemShape(index, runtimes.size),
                    ) {
                        Text(if (runtime == AgentOptions.Runtime.ON_DEVICE) "On device" else "Cloud")
                    }
                }
            }

            if (state.options.runtime == AgentOptions.Runtime.CLOUD) {
                Text(
                    "Cloud mode needs a network connection and sends your requests " +
                        "off the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionLabel("Model")
            Text(
                "Automatically follow this phone" +
                    if (state.options.modelId == null) " (currently selected)" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setModel(null) }
                    .padding(vertical = 8.dp),
            )
            state.models.forEach { row ->
                ModelCard(
                    row = row,
                    selected = state.options.modelId == row.variant.id,
                    onSelect = { viewModel.setModel(row.variant) },
                    onDownload = { viewModel.download(row.variant) },
                    onDelete = { viewModel.delete(row.variant) },
                )
            }

            SectionLabel("Thinking level")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val levels = AgentOptions.ThinkingLevel.entries
                levels.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = state.options.thinkingLevel == level,
                        onClick = { viewModel.setThinkingLevel(level) },
                        shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                    ) {
                        Text(level.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
            Text(
                state.options.thinkingLevel.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("What Dogo may change")
            Text(
                "Ask before each change",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Confirm every action", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.options.requireConfirmation,
                    onCheckedChange = viewModel::setRequireConfirmation,
                )
            }
            AgentOptions.ActionType.entries.forEach { type ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(type.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            type.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = type in state.options.allowedActions,
                        onCheckedChange = { viewModel.toggleAction(type) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun RecommendationCard(rationale: String, ramGb: Int, freeStorageGb: Int) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("This phone", style = MaterialTheme.typography.labelLarge)
            Text(
                "$ramGb GB RAM · $freeStorageGb GB free",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                rationale,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun IssueCard(issue: AgentOptions.Issue) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(issue.message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModelCard(
    row: DogoOptionsViewModel.ModelRow,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val variant = row.variant
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(variant.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (row.recommended) {
                            Text(
                                "  Recommended",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        buildString {
                            append("%.1f GB".format(variant.sizeGb))
                            append(" · needs ")
                            append(variant.minRamGb)
                            append(" GB RAM")
                            if (!row.fits) append(" · won't fit on this phone")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.fits) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                when {
                    row.downloading -> CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )

                    row.downloaded -> IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "Delete model")
                    }

                    else -> IconButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, "Download model")
                    }
                }
            }
            if (row.downloading) {
                LinearProgressIndicator(
                    progress = { row.progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                Text(
                    "${(row.progress * 100).toInt()}% downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
