/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp

private val LocalChatWallpaper = staticCompositionLocalOf { false }

/**
 * Shows the system-managed wallpaper, never a bitmap copy (which needs privileged
 * access on recent Android versions). The containing preferences surfaces must
 * also be transparent for this destination only. No hardware blur is required.
 */
@Composable
internal fun ChatWallpaper(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val window = remember(context) { context.activity()?.window }
    var wallpaper by remember(window) { mutableStateOf(false) }

    DisposableEffect(window, preview) {
        val supported = !preview && window != null && runCatching {
            WallpaperManager.getInstance(context).isWallpaperSupported
        }.getOrDefault(false)
        if (supported && window != null) {
            val background = window.decorView.background
            val format = window.attributes.format
            val hadWallpaper = window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setFormat(PixelFormat.TRANSLUCENT)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            wallpaper = true
            onDispose {
                // Do not leave other settings transparent, or change unrelated flags.
                if (!hadWallpaper) window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setFormat(format)
                window.setBackgroundDrawable(background)
            }
        } else {
            wallpaper = false
            onDispose { }
        }
    }

    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < colors.onSurface.luminance()
    // A strong full-screen scrim preserves contrast even on a white/black or busy
    // live wallpaper. The small primary tint follows Lawnchair's wallpaper palette.
    val tint = colors.primary.copy(alpha = 0.035f).compositeOver(colors.surface)
    val scrim = if (wallpaper) tint.copy(alpha = if (dark) 0.84f else 0.92f) else colors.surface
    CompositionLocalProvider(LocalChatWallpaper provides wallpaper) {
        Box(modifier.fillMaxSize().background(scrim)) {
            content()
        }
    }
}

/** Translucency is optional; previews and devices without wallpaper stay opaque. */
@Composable
internal fun chatGlassColor(color: Color, alpha: Float = 0.86f): Color = if (LocalChatWallpaper.current) color.copy(alpha = alpha) else color.copy(alpha = 1f)

@Composable
internal fun chatGlassBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
