/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dogo.launcher.tiles.TileGeometry
import app.dogo.launcher.tiles.TileParser

/** A requested layout, not a rendering of the widgets or a promise that every box can be placed. */
@Composable
internal fun TileProposalPreview(text: String) {
    val config = remember(text) { TileParser.parse(text) }
    if (config.isEmpty) return
    var expanded by remember(text) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide requested layout" else "Show requested layout")
        }
        if (!expanded) return@Column
        Text(
            "Requested layout only; consult the change list for refusals. Grid changes can alter placement results after reload. Widget content is not shown. Tile style settings currently have no live effect.",
            style = MaterialTheme.typography.labelSmall,
        )
        val outline = MaterialTheme.colorScheme.outlineVariant
        val accent = MaterialTheme.colorScheme.primary
        val fill = MaterialTheme.colorScheme.primaryContainer
        val ink = MaterialTheme.colorScheme.onPrimaryContainer
        Column {
            config.pages.forEach { page ->
                val descriptions = page.tiles.map { tile ->
                    val name = tile.kind.substringAfterLast('/').substringAfterLast('.').take(24)
                    "$name: ${tile.width} by ${tile.height}, column ${tile.x}, row ${tile.y}"
                }
                Text("Requested page ${page.number}: ${page.columns} × ${page.rows}", style = MaterialTheme.typography.labelMedium)
                Canvas(
                    Modifier.fillMaxWidth().height(160.dp).semantics {
                        contentDescription = "Requested page ${page.number}. " + descriptions.joinToString(". ")
                    },
                ) {
                    for (column in 0..page.columns) {
                        val x = size.width * column / page.columns
                        drawLine(outline, Offset(x, 0f), Offset(x, size.height))
                    }
                    for (row in 0..page.rows) {
                        val y = size.height * row / page.rows
                        drawLine(outline, Offset(0f, y), Offset(size.width, y))
                    }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ink.toArgb()
                        textSize = 10.dp.toPx()
                        textAlign = Paint.Align.CENTER
                    }
                    page.tiles.forEachIndexed { index, tile ->
                        // This schematic uses a fixed tiny gap, not the unimplemented live style keys.
                        val rect = TileGeometry.cellRect(page, tile, size.width.toInt(), size.height.toInt(), 2.dp.toPx().toInt())
                        val topLeft = Offset(rect[0].toFloat(), rect[1].toFloat())
                        val boxSize = Size((rect[2] - rect[0]).toFloat(), (rect[3] - rect[1]).toFloat())
                        drawRoundRect(fill, topLeft, boxSize, CornerRadius(3.dp.toPx()))
                        drawRoundRect(accent, topLeft, boxSize, CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
                        val label = "${index + 1}"
                        drawContext.canvas.nativeCanvas.drawText(label, topLeft.x + boxSize.width / 2, topLeft.y + boxSize.height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
                    }
                }
                descriptions.forEachIndexed { index, description ->
                    Text("${index + 1}. $description", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
