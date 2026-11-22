/*
 * Copyright 2026 The Dogo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.dogo.launcher.themes

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.lawnchair.preferences2.asState
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.theme.color.ColorOption
import com.android.launcher3.BuildConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val previousAccentKey = stringPreferencesKey("dogo_previous_non_wallpaper_accent")

/** Explicit opt-in only: opening this section never changes an accent or wallpaper. */
@Composable
fun WallpaperThemeSection(modifier: Modifier = Modifier) {
    if (BuildConfig.FLAVOR_channel != "dogo") return

    val context = LocalContext.current.applicationContext
    val preferences = preferenceManager2()
    val accent by preferences.accentColor.asState()
    val scope = rememberCoroutineScope()
    var changingAccent by remember { mutableStateOf(false) }
    var applyingWallpaper by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        app.dogo.launcher.ui.options.SectionLabel("Wallpaper colours")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Match wallpaper colours",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = accent == ColorOption.WallpaperPrimary,
                enabled = !changingAccent && ColorOption.WallpaperPrimary.isSupported,
                modifier = Modifier.semantics { contentDescription = "Match wallpaper colours" },
                onCheckedChange = { enabled ->
                    changingAccent = true
                    message = null
                    scope.launch {
                        try {
                            // Read at click time, not from the last frame. Preserve a manual or
                            // system accent, including one selected elsewhere while this is open.
                            val current = preferences.accentColor.get().first()
                            if (enabled && current != ColorOption.WallpaperPrimary) {
                                preferences.preferencesDataStore.edit {
                                    it[previousAccentKey] = current.toString()
                                }
                                preferences.accentColor.set(ColorOption.WallpaperPrimary)
                            } else if (!enabled && current == ColorOption.WallpaperPrimary) {
                                val saved = preferences.preferencesDataStore.data.first()[previousAccentKey]
                                val previous = saved?.let(ColorOption::fromString)
                                    ?.takeUnless { it == ColorOption.WallpaperPrimary }
                                    ?: ColorOption.LawnchairBlue
                                preferences.accentColor.set(previous)
                            }
                            isError = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            isError = true
                            message = "Could not save the colour preference. Please try again."
                        } finally {
                            changingAccent = false
                        }
                    }
                },
            )
        }
        Text(
            "Use your home wallpaper's palette in Dogo, in both light and dark mode. " +
                "Turning this off restores your previous accent (or Lawnchair blue if none was saved). " +
                "You can still choose a manual or system accent in Home settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = !applyingWallpaper,
            onClick = {
                applyingWallpaper = true
                message = null
                scope.launch {
                    try {
                        applyDogoWallpaper(context)
                        isError = false
                        message = "Dogo home wallpaper applied. Enable Match wallpaper colours to use its palette in Dogo."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        isError = true
                        message = when (error) {
                            is SecurityException -> "Android did not allow Dogo to change the wallpaper. Check device or work-profile restrictions."
                            is Resources.NotFoundException -> "This build does not include the Dogo wallpaper. Update Dogo and try again."
                            is IOException, is IllegalArgumentException -> "Android could not read or apply the Dogo wallpaper image. Please try again."
                            is IllegalStateException -> error.message ?: "Wallpaper changes are unavailable on this device."
                            else -> "Could not apply the Dogo wallpaper. Please try again."
                        }
                    } finally {
                        applyingWallpaper = false
                    }
                }
            },
        ) {
            Text(if (applyingWallpaper) "Applying wallpaper…" else "Use Dogo wallpaper")
        }
        Text(
            "Changes the home wallpaper only, without requesting a lock-screen change. " +
                "This does not enable wallpaper colours automatically. For Android and supported apps, " +
                "choose Wallpaper colours in Android Settings > Wallpaper & style. " +
                "Dogo cannot force the system palette or other apps' styles.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** No bitmap decoding or wallpaper service I/O on the UI thread; no lock-screen flag. */
private suspend fun applyDogoWallpaper(context: Context) = withContext(Dispatchers.IO) {
    val manager = WallpaperManager.getInstance(context)
    check(manager.isWallpaperSupported && manager.isSetWallpaperAllowed) {
        "Wallpaper changes are disabled on this device or profile."
    }
    // The asset lives in the Dogo channel. Do not reference R.drawable here: shared
    // sources must also compile in upstream channels without that resource.
    val wallpaperId = context.resources.getIdentifier("dogo_brand_wallpaper", "drawable", context.packageName)
    if (wallpaperId == 0) throw Resources.NotFoundException("dogo_brand_wallpaper")
    context.resources.openRawResource(wallpaperId).use { stream ->
        check(manager.setStream(stream, null, true, WallpaperManager.FLAG_SYSTEM) != 0) {
            "Android did not apply the home wallpaper. Please try again."
        }
    }
}
