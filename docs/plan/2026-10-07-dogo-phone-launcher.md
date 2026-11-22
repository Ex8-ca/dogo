# Dogo Phone Launcher — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Build an Android launcher + on-device AI agent that lets users configure their phone through natural conversation. Inspired by Omarchy (DHH's AI-configured Linux desktop), but for phones. Free, F-Droid-distributable, no Google Play Store dependency.

**Name:** Dogo (dogo.ca owned). Latin "I give," Spanish "I give" — a loyal working dog metaphor. Short, memorable, no major store collisions, .ca domain secured.

**Architecture:** Fork Lawnchair (open-source Android launcher) → add an AccessibilityService that drives UI + a chat-based setup wizard that uses a local LLM (Gemma 4 E2B/E4B via MediaPipe) for offline intelligence, with optional cloud API escalation. Bundle F-Droid as the recommended app source. Use Shizuku for elevated permissions without root.

**Tech Stack:**
- **Language:** Kotlin (Android)
- **Base:** Lawnchair launcher fork (Apache-2.0)
- **LLM Runtime:** MediaPipe LLM Inference (Gemma 4 E2B/E4B, FunctionGemma 270M)
- **UI Agent:** Android AccessibilityService
- **Permissions:** Shizuku API (no-root elevated access)
- **App Source:** F-Droid + Aurora Store fallback
- **Payments:** GitHub Sponsors / Patreon / self-hosted Stripe for $5 cloud tier
- **Build:** Gradle 8.x, Android SDK 34, min SDK 29 (Android 10)
- **Tests:** JUnit 5, Robolectric, Espresso UI tests

---

## Table of Contents

1. [Foundation Phase](#phase-1-foundation-weeks-1-3)
2. [Core AI Phase](#phase-2-core-ai-weeks-4-6)
3. [Agent Phase](#phase-3-agent-weeks-7-9)
4. [Distribution Phase](#phase-4-distribution-weeks-10-12)
5. [Monetization Phase](#phase-5-monetization-week-13)
6. [Future Work (ROM)](#phase-6-future-work-conditional)

---

# Phase 1: Foundation (Weeks 1-3)

> **Objective:** Stand up a forkable Lawnchair base, rebranded, with minimal modifications. Verify the build pipeline works before adding AI.

## Task 1.1: Fork Lawnchair repository

**Objective:** Create a clean working fork of Lawnchair as the base for this project.

**Files:**
- Create: `dogo-launcher/` (fork destination)
- Read: `https://lawnchair.app/contributors`

**Step 1: Fork upstream Lawnchair**

```bash
# In a fresh directory
git clone https://github.com/LawnchairLauncher/lawnchair.git dogo-launcher
cd dogo-launcher
git remote rename origin upstream
git remote add origin git@github.com:YOUR_USERNAME/dogo-launcher.git
git push -u origin main
```

**Step 2: Verify upstream is tracking**

```bash
git remote -v
# Expected:
# origin    git@github.com:YOUR_USERNAME/dogo-launcher.git (fetch)
# origin    git@github.com:YOUR_USERNAME/dogo-launcher.git (push)
# upstream  https://github.com/LawnchairLauncher/lawnchair.git (fetch)
```

**Step 3: Create feature branch**

```bash
git checkout -b foundation/rebrand
```

**Step 4: Commit fork marker**

```bash
git commit --allow-empty -m "chore: initial fork of Lawnchair for Dogo Phone project"
git push origin foundation/rebrand
```

**Verification:** GitHub repo exists, branch is pushed, upstream is set.

---

## Task 1.2: Rename package to avoid Play Store collisions

**Objective:** Rename `app.lawnchair` package to `app.dogo.launcher` so we can publish independently.

**Files:**
- Modify: `AndroidManifest.xml` (package attribute in each module)
- Modify: `*/build.gradle` (applicationId, namespace)
- Rename directories: All `app/lawnchair/...` → `app/dogo/launcher/...`

**Step 1: Write a failing build check**

Create `scripts/check_package.sh`:
```bash
#!/bin/bash
set -e
EXPECTED="app.dogo.launcher"
ACTUAL=$(grep -r "applicationId" launcher/build.gradle.kts | head -1 | grep -oE '"[^"]+"' | tr -d '"')
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "FAIL: expected $EXPECTED, got $ACTUAL"
  exit 1
fi
echo "PASS"
```

```bash
chmod +x scripts/check_package.sh
```

**Step 2: Run check to verify it fails**

```bash
./scripts/check_package.sh
# Expected: FAIL: expected app.dogo.launcher, got app.lawnchair
```

**Step 3: Rename package across project**

```bash
./gradlew updateApplicationShortcuts(...)
# Actually, use the IDE: Right-click `app.lawnchair` package → Refactor → Rename → `app.dogo.launcher`
# OR use sed for bulk replacement:
find . -type f \( -name "*.kt" -o -name "*.xml" -o -name "*.gradle.kts" \) \
  -exec sed -i 's/app\.lawnchair/app.dogo.launcher/g' {} +
```

**Step 4: Update applicationId in build.gradle.kts**

In `launcher/build.gradle.kts`:
```kotlin
android {
    namespace = "app.dogo.launcher"
    defaultConfig {
        applicationId = "app.dogo.launcher"
        // ...
    }
}
```

**Step 5: Run check to verify pass**

```bash
./scripts/check_package.sh
# Expected: PASS
```

**Step 6: Build**

```bash
./gradlew assembleQuickstepDebug
```

**Expected:** BUILD SUCCESSFUL. APK at `launcher/build/outputs/apk/quickstep/debug/launcher-quickstep-debug.apk`

**Step 7: Commit**

```bash
git add -A
git commit -m "refactor: rename package to app.dogo.launcher"
git push origin foundation/rebrand
```

---

## Task 1.3: Rebrand strings and resources

**Objective:** Change app name, colors, and visible branding from Lawnchair to Dogo Phone Launcher.

**Files:**
- Modify: `launcher/src/main/res/values/strings.xml`
- Modify: `launcher/src/main/res/values/colors.xml`
- Modify: `launcher/src/main/res/values/themes.xml`
- Modify: `launcher/src/main/AndroidManifest.xml` (android:label)

**Step 1: Write the failing test**

Create `launcher/src/test/java/app/dogo/launcher/BrandingTest.kt`:
```kotlin
package app.dogo.launcher

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrandingTest {
    @Test
    fun app_name_is_dogo_not_lawnchair() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Dogo Phone", appName)
    }
}
```

**Step 2: Run test to verify failure**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.BrandingTest"
```

**Expected:** FAIL — expected "Dogo Phone" but got "Lawnchair"

**Step 3: Update strings**

In `launcher/src/main/res/values/strings.xml`:
```xml
<string name="app_name">Dogo Phone</string>
<!-- Strip out: lawnchair-specific copy in onboarding strings -->
```

**Step 4: Update manifest**

In `launcher/src/main/AndroidManifest.xml`:
```xml
<application
    android:label="@string/app_name"
    ...>
```

**Step 5: Update colors (dogo palette — warm orange + dark)**

In `launcher/src/main/res/values/colors.xml`:
```xml
<color name="dogo_accent">#FF7043</color>  <!-- Warm orange, matches Dogo desktop -->
<color name="dogo_dark_bg">#1E1E1E</color>
<color name="dogo_light_bg">#FAFAFA</color>
```

**Step 6: Run test to verify pass**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.BrandingTest"
```

**Expected:** PASS

**Step 7: Commit**

```bash
git add -A
git commit -m "feat: rebrand to Dogo Phone Launcher"
git push origin foundation/rebrand
```

---

## Task 1.4: Set up CI/CD with reproducible APK builds

**Objective:** Add GitHub Actions that build + sign + upload APKs on every tag.

**Files:**
- Create: `.github/workflows/build.yml`
- Create: `keystore/README.md` (explanation for signing key)

**Step 1: Write the workflow file**

Create `.github/workflows/build.yml`:
```yaml
name: Build APK

on:
  push:
    tags: ['v*']
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: ~/.gradle
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*') }}
      
      - name: Build debug APK
        run: ./gradlew assembleQuickstepDebug
      
      - name: Build release APK (unsigned if no keystore)
        run: ./gradlew assembleQuickstepRelease || echo "Release build needs signing config — skipping"
      
      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: dogo-launcher-debug
          path: launcher/build/outputs/apk/quickstep/debug/*.apk
```

**Step 2: Create keystore documentation**

Create `keystore/README.md`:
```markdown
# Signing Key

For F-Droid builds: F-Droid will sign with their own key.
For self-hosted/Play Store: Generate your own.

Generate a release keystore:
keytool -genkey -v -keystore release.keystore -alias dogo \
  -keyalg RSA -keysize 2048 -validity 10000

Add to ~/.gradle/gradle.properties:
DOGO_KEYSTORE_FILE=/path/to/release.keystore
DOGO_KEYSTORE_PASSWORD=...
DOGO_KEY_ALIAS=dogo
DOGO_KEY_PASSWORD=...
```

**Step 3: Push and verify CI runs**

```bash
git add -A
git commit -m "ci: add GitHub Actions build pipeline"
git push origin foundation/rebrand
```

**Verification:** Go to GitHub Actions tab, see workflow run, see artifact.

**Step 4: Commit**

```bash
git commit --allow-empty -m "ci: verify workflow runs"
```

---

## Task 1.5: Verify F-Droid metadata format

**Objective:** Set up the metadata files F-Droid needs to build your app from source.

**Files:**
- Create: `metadata/app.dogo.launcher.yml` (F-Droid metadata)

**Step 1: Read F-Droid metadata spec**

Read: https://f-droid.org/docs/Build_Metadata_Reference/

**Step 2: Write F-Droid metadata**

Create `metadata/app.dogo.launcher.yml`:
```yaml
Categories:
  - Launcher
  - System
License: Apache-2.0
AuthorName: Dogo Phone Contributors
SourceCode: https://github.com/YOUR_USERNAME/dogo-launcher
IssueTracker: https://github.com/YOUR_USERNAME/dogo-launcher/issues

RepoType: git
Repo: https://github.com/YOUR_USERNAME/dogo-launcher.git

Builds:
  - versionName: 0.1.0-foundation
    versionCode: 1
    commit: foundation/rebrand
    output: launcher/build/outputs/apk/quickstep/release/launcher-quickstep-release-unsigned.apk
    srclibs:
      - id: lawnchair
        repotype: git
        repo: https://github.com/LawnchairLauncher/lawnchair.git
        ref: 12.0.0

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0-foundation
CurrentVersionCode: 1
```

**Step 3: Commit**

```bash
git add metadata/
git commit -m "build: add F-Droid metadata for reproducible builds"
git push origin foundation/rebrand
```

---

# Phase 2: Core AI (Weeks 4-6)

> **Objective:** Get a local LLM running on the phone, accessible from the launcher. Verify with a simple "describe screen" test.

## Task 2.1: Add MediaPipe LLM Inference dependency

**Objective:** Integrate Google's MediaPipe LLM Inference library, which supports Gemma models on Android.

**Files:**
- Modify: `launcher/build.gradle.kts` (add dependency)
- Create: `launcher/src/main/java/app/dogo/launcher/llm/ModelRunner.kt`
- Create: `launcher/src/test/java/app/dogo/launcher/llm/ModelRunnerTest.kt`

**Step 1: Add MediaPipe dependency**

In `launcher/build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
    // ...
}
```

**Step 2: Write the failing test**

Create `launcher/src/test/java/app/dogo/launcher/llm/ModelRunnerTest.kt`:
```kotlin
package app.dogo.launcher.llm

import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelRunnerTest {
    @Test
    fun model_runner_has_supported_variants() {
        val variants = ModelRunner.supportedVariants()
        assertNotNull(variants)
        assert(variants.isNotEmpty())
        assert(variants.any { it.contains("gemma-4-e2b") })
    }
}
```

**Step 3: Run test to verify failure**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.llm.ModelRunnerTest"
```

**Expected:** FAIL — "Unresolved reference: ModelRunner"

**Step 4: Implement ModelRunner**

Create `launcher/src/main/java/app/dogo/launcher/llm/ModelRunner.kt`:
```kotlin
package app.dogo.launcher.llm

/**
 * Manages on-device LLM inference via MediaPipe.
 * Supports Gemma 4 variants and FunctionGemma for routing.
 */
class ModelRunner(private val context: android.content.Context) {

    data class ModelVariant(
        val id: String,
        val displayName: String,
        val sizeMB: Int,
        val minRamGB: Int,
        val hfRepo: String,
        val filename: String,
        val useCase: UseCase
    )

    enum class UseCase { PRIMARY, ROUTER }

    companion object {
        fun supportedVariants(): List<ModelVariant> = listOf(
            ModelVariant(
                id = "gemma-4-e2b-q4",
                displayName = "Gemma 4 E2B (Q4)",
                sizeMB = 1500,
                minRamGB = 3,
                hfRepo = "google/gemma-4-e2b-it",
                filename = "gemma-4-e2b-it-q4_k_m.task",
                useCase = UseCase.PRIMARY
            ),
            ModelVariant(
                id = "gemma-4-e4b-q4",
                displayName = "Gemma 4 E4B (Q4)",
                sizeMB = 2500,
                minRamGB = 6,
                hfRepo = "google/gemma-4-e4b-it",
                filename = "gemma-4-e4b-it-q4_k_m.task",
                useCase = UseCase.PRIMARY
            ),
            ModelVariant(
                id = "gemma-3-1b-q4",
                displayName = "Gemma 3 1B (Q4)",
                sizeMB = 800,
                minRamGB = 2,
                hfRepo = "google/gemma-3-1b-it",
                filename = "gemma-3-1b-it-q4_k_m.task",
                useCase = UseCase.PRIMARY
            ),
            ModelVariant(
                id = "functiongemma-270m",
                displayName = "FunctionGemma 270M (router)",
                sizeMB = 200,
                minRamGB = 1,
                hfRepo = "google/functiongemma-270m-it",
                filename = "functiongemma-270m-it-q4.task",
                useCase = UseCase.ROUTER
            )
        )
    }

    // Real implementation in next task
    fun isReady(): Boolean = false
}
```

**Step 5: Run test to verify pass**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.llm.ModelRunnerTest"
```

**Expected:** PASS

**Step 6: Commit**

```bash
git add -A
git commit -m "feat: add MediaPipe LLM dependency + ModelRunner scaffold"
git push origin foundation/rebrand
```

---

## Task 2.2: Implement model download + storage

**Objective:** Download Gemma models from HuggingFace on demand, store in app's private dir.

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/llm/ModelDownloader.kt`
- Create: `launcher/src/test/java/app/dogo/launcher/llm/ModelDownloaderTest.kt`
- Modify: `launcher/src/main/AndroidManifest.xml` (INTERNET permission)

**Step 1: Add internet permission**

In `launcher/src/main/AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**Step 2: Write failing test**

Create `launcher/src/test/java/app/dogo/launcher/llm/ModelDownloaderTest.kt`:
```kotlin
package app.dogo.launcher.llm

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ModelDownloaderTest {
    @Test
    fun model_file_path_is_in_app_private_storage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloader = ModelDownloader(ctx)
        val variant = ModelRunner.supportedVariants().first()
        val expectedPath = File(ctx.filesDir, "models/${variant.filename}").absolutePath
        assertEquals(expectedPath, downloader.targetPath(variant).absolutePath)
    }
}
```

**Step 3: Run test to verify failure**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.llm.ModelDownloaderTest"
```

**Expected:** FAIL — "Unresolved reference: ModelDownloader"

**Step 4: Implement ModelDownloader**

Create `launcher/src/main/java/app/dogo/launcher/llm/ModelDownloader.kt`:
```kotlin
package app.dogo.launcher.llm

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads LLM model files from HuggingFace.
 * Streams progress as a Flow for UI display.
 */
class ModelDownloader(private val context: Context) {

    fun targetPath(variant: ModelRunner.ModelVariant): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, variant.filename)
    }

    fun isDownloaded(variant: ModelRunner.ModelVariant): Boolean {
        val file = targetPath(variant)
        return file.exists() && file.length() > 1_000_000  // Sanity check, not zero bytes
    }

    /**
     * Download with progress reporting.
     * Uses HF resolve URL. Production should support HF token for gated models.
     */
    fun download(variant: ModelRunner.ModelVariant): Flow<DownloadProgress> = flow {
        val url = URL("https://huggingface.co/${variant.hfRepo}/resolve/main/${variant.filename}")
        val target = targetPath(variant)
        val tmp = File(target.parentFile, "${variant.filename}.part")

        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "DogoPhone/0.1")

        val totalBytes = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var lastEmit = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (downloaded - lastEmit > 100_000) {  // Emit every ~100KB
                        emit(DownloadProgress(downloaded, totalBytes))
                        lastEmit = downloaded
                    }
                }
            }
        }
        if (!tmp.renameTo(target)) {
            throw IllegalStateException("Failed to move ${tmp.path} to ${target.path}")
        }
        emit(DownloadProgress(totalBytes, totalBytes))
    }

    data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
        val fraction: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
    }
}
```

**Step 5: Run test to verify pass**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.llm.ModelDownloaderTest"
```

**Expected:** PASS

**Step 6: Add coroutines dependency**

In `launcher/build.gradle.kts`:
```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

**Step 7: Commit**

```bash
git add -A
git commit -m "feat: implement model downloader with progress streaming"
git push origin foundation/rebrand
```

---

## Task 2.3: Add hardware capability detection

**Objective:** Detect phone RAM, available storage, NPU presence to recommend the right model.

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/llm/HardwareProfile.kt`
- Create: `launcher/src/test/java/app/dogo/launcher/llm/HardwareProfileTest.kt`

**Step 1: Write failing test**

Create `launcher/src/test/java/app/dogo/launcher/llm/HardwareProfileTest.kt`:
```kotlin
package app.dogo.launcher.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareProfileTest {
    @Test
    fun low_ram_recommends_tiny_model() {
        val rec = HardwareProfile.recommendModel(
            ramGB = 2,
            freeStorageGB = 5,
            hasNpu = false
        )
        assertEquals("gemma-3-1b-q4", rec.primary.id)
    }

    @Test
    fun high_ram_with_npu_recommends_best_model() {
        val rec = HardwareProfile.recommendModel(
            ramGB = 8,
            freeStorageGB = 10,
            hasNpu = true
        )
        assertEquals("gemma-4-e4b-q4", rec.primary.id)
    }

    @Test
    fun mid_ram_recommends_e2b_default() {
        val rec = HardwareProfile.recommendModel(
            ramGB = 4,
            freeStorageGB = 5,
            hasNpu = false
        )
        assertEquals("gemma-4-e2b-q4", rec.primary.id)
    }

    @Test
    fun recommendation_always_includes_router() {
        val rec = HardwareProfile.recommendModel(
            ramGB = 4,
            freeStorageGB = 5,
            hasNpu = false
        )
        assertEquals("functiongemma-270m", rec.router.id)
    }
}
```

**Step 2: Run test to verify failure**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.llm.HardwareProfileTest"
```

**Expected:** FAIL — "Unresolved reference: HardwareProfile"

**Step 3: Implement HardwareProfile**

Create `launcher/src/main/java/app/dogo/launcher/llm/HardwareProfile.kt`:
```kotlin
package app.dogo.launcher.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

/**
 * Detects device capabilities and recommends the appropriate LLM model.
 *
 * Decision matrix:
 *   RAM >= 6GB AND has NPU    -> Gemma 4 E4B  (best quality)
 *   RAM >= 3GB                -> Gemma 4 E2B  (default, good balance)
 *   RAM >= 2GB                -> Gemma 3 1B   (fallback, older phones)
 *
 *   Always pair with FunctionGemma 270M as the routing/escalation classifier.
 */
object HardwareProfile {

    data class Recommendation(
        val primary: ModelRunner.ModelVariant,
        val router: ModelRunner.ModelVariant,
        val rationale: String
    )

    fun recommendModel(ramGB: Int, freeStorageGB: Int, hasNpu: Boolean): Recommendation {
        val all = ModelRunner.supportedVariants()
        val router = all.first { it.useCase == ModelRunner.UseCase.ROUTER }

        val (primary, why) = when {
            ramGB >= 6 && hasNpu && freeStorageGB >= 4 ->
                all.first { it.id == "gemma-4-e4b-q4" } to
                "Your device has enough RAM and an NPU — using the highest-quality model."

            ramGB >= 3 && freeStorageGB >= 2 ->
                all.first { it.id == "gemma-4-e2b-q4" } to
                "Default configuration — good balance of quality and performance."

            ramGB >= 2 ->
                all.first { it.id == "gemma-3-1b-q4" } to
                "Lightweight model for older devices. Less capable but fast."

            else ->
                all.first { it.id == "gemma-3-1b-q4" } to
                "Warning: very low RAM. Cloud-only mode recommended."
        }

        return Recommendation(primary, router, why)
    }

    /**
     * Sample the actual device this app is running on.
     */
    fun fromDevice(context: Context): Recommendation {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val ramGB = (memInfo.totalMem / (1024 * 1024 * 1024)).toInt()

        val statFs = StatFs(Environment.getDataDirectory().path)
        val freeStorageGB = (statFs.availableBytes / (1024 * 1024 * 1024)).toInt()

        // NPU detection: API 31+ has getDeviceCharacteristicsInfo
        val hasNpu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000  // Heuristic; real NPUs vary
                // Production: also check NeuralNetworksApi.getAvailableDevices()
                true
            } catch (e: Exception) { false }
        } else false

        return recommendModel(ramGB, freeStorageGB, hasNpu)
    }
}
```

**Step 4: Run test to verify pass**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.llm.HardwareProfileTest"
```

**Expected:** PASS (4 tests)

**Step 5: Commit**

```bash
git add -A
git commit -m "feat: add hardware profile detection + model recommendation"
git push origin foundation/rebrand
```

---

## Task 2.4: Implement actual LLM inference call

**Objective:** Wire MediaPipe LLM Inference so we can send text and receive text back.

**Files:**
- Modify: `launcher/src/main/java/app/dogo/launcher/llm/ModelRunner.kt`
- Create: `launcher/src/test/java/app/dogo/launcher/llm/ModelRunnerInferenceTest.kt` (instrumented)

**Step 1: Add LLM Inference API dependency**

In `launcher/build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
}
```

**Step 2: Implement inference method**

Update `launcher/src/main/java/app/dogo/launcher/llm/ModelRunner.kt`:
```kotlin
package app.dogo.launcher.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ModelRunner(private val context: Context) {

    // ... (existing data classes and companion) ...

    private var llm: LlmInference? = null

    fun load(variant: ModelRunner.ModelVariant): Result<Unit> {
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(targetPath(variant).absolutePath)
                .setMaxTokens(1024)
                .setTemperature(0.7f)
                .setTopK(40)
                .build()
            llm = LlmInference.createFromOptions(context, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun targetPath(variant: ModelRunner.ModelVariant): java.io.File {
        val dir = java.io.File(context.filesDir, "models")
        return java.io.File(dir, variant.filename)
    }

    fun isReady(): Boolean = llm != null

    fun generate(prompt: String): Flow<String> = flow {
        val model = llm ?: throw IllegalStateException("Model not loaded. Call load() first.")
        val response = model.generateResponse(prompt)
        emit(response)
    }

    fun generateAsync(prompt: String): Flow<String> = flow {
        val model = llm ?: throw IllegalStateException("Model not loaded. Call load() first.")
        model.generateResponseAsync(prompt).collect { partial ->
            emit(partial)
        }
    }
}
```

**Step 3: Create instrumented test (runs on device)**

Create `launcher/src/androidTest/java/app/dogo/launcher/llm/ModelRunnerInferenceTest.kt`:
```kotlin
package app.dogo.launcher.llm

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModelRunnerInferenceTest {

    @Test
    fun can_load_model_and_generate_response(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val runner = ModelRunner(ctx)

        val variant = ModelRunner.supportedVariants().first { it.id == "gemma-3-1b-q4" }
        val downloader = ModelDownloader(ctx)
        assumeTrue(
            "Model must be pre-installed for this test (run scripts/download-test-model.sh)",
            File(ctx.filesDir, "models/${variant.filename}").exists()
        )

        val loadResult = runner.load(variant)
        assertTrue("Model load failed", loadResult.isSuccess)

        val response = runner.generate("Say 'hi' in one word").first()
        assertTrue("Response should not be empty", response.isNotBlank())
    }
}
```

**Step 4: Create test model download script**

Create `scripts/download-test-model.sh`:
```bash
#!/bin/bash
# Downloads the smallest Gemma 3 1B model for instrumented tests.
# Usage: ./scripts/download-test-model.sh
set -e
DEST="$HOME/.cache/dogo-test-models"
mkdir -p "$DEST"
cd "$DEST"

if [ ! -f "gemma-3-1b-it-q4_k_m.task" ]; then
  echo "Downloading Gemma 3 1B..."
  # NOTE: This URL pattern is illustrative. Real HF token gated for some models.
  curl -L -o "gemma-3-1b-it-q4_k_m.task" \
    "https://huggingface.co/google/gemma-3-1b-it/resolve/main/gemma-3-1b-it-q4_k_m.task"
fi

# Symlink into app's private storage via adb
adb push "$DEST/gemma-3-1b-it-q4_k_m.task" /sdcard/Download/
echo "Run: adb shell run-as app.dogo.launcher.debug cp /sdcard/Download/gemma-3-1b-it-q4_k_m.task files/models/"
```

```bash
chmod +x scripts/download-test-model.sh
```

**Step 5: Build instrumented test APK**

```bash
./gradlew :launcher:assembleQuickstepDebugAndroidTest
```

**Expected:** BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add -A
git commit -m "feat: wire MediaPipe LLM Inference for on-device generation"
git push origin foundation/rebrand
```

**Note:** Full inference test requires a physical device with the model installed. CI runs unit tests only.

---

## Task 2.5: Build model recommendation UI screen

**Objective:** When user first opens the app, show a screen that recommends a model based on their hardware.

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/ui/setup/ModelPickerActivity.kt`
- Create: `launcher/src/main/res/layout/activity_model_picker.xml`
- Create: `launcher/src/main/res/values/strings_setup.xml`
- Modify: `launcher/src/main/AndroidManifest.xml`

**Step 1: Create layout XML**

Create `launcher/src/main/res/layout/activity_model_picker.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp"
    android:gravity="center_vertical">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/setup_model_title"
        android:textSize="28sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/rationale_text"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:textSize="16sp" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="32dp"
        android:text="@string/setup_model_recommended"
        android:textStyle="bold" />

    <RadioGroup
        android:id="@+id/model_options"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="@string/setup_model_storage_note"
        android:textSize="14sp"
        android:textColor="?android:attr/textColorSecondary" />

    <Button
        android:id="@+id/download_button"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="@string/setup_model_download" />

    <ProgressBar
        android:id="@+id/download_progress"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:visibility="gone"
        style="?android:attr/progressBarStyleHorizontal" />

</LinearLayout>
```

**Step 2: Create strings**

Create `launcher/src/main/res/values/strings_setup.xml`:
```xml
<resources>
    <string name="setup_model_title">Choose your AI model</string>
    <string name="setup_model_recommended">Options for your device:</string>
    <string name="setup_model_storage_note">Models run entirely on your phone — no data leaves your device.</string>
    <string name="setup_model_download">Download recommended</string>
</resources>
```

**Step 3: Write the activity**

Create `launcher/src/main/java/app/dogo/launcher/ui/setup/ModelPickerActivity.kt`:
```kotlin
package app.dogo.launcher.ui.setup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.dogo.launcher.R
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelDownloader
import app.dogo.launcher.llm.ModelRunner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ModelPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_picker)

        val rec = HardwareProfile.fromDevice(this)
        val downloader = ModelDownloader(this)
        val runner = ModelRunner(this)

        val rationale = findViewById<TextView>(R.id.rationale_text)
        rationale.text = rec.rationale

        val options = findViewById<RadioGroup>(R.id.model_options)
        val candidates = listOf(
            rec.primary,
            ModelRunner.supportedVariants().filter { it.useCase == ModelRunner.UseCase.PRIMARY }
                .sortedBy { it.sizeMB }
                .first { it.id != rec.primary.id }
        )

        candidates.distinctBy { it.id }.forEach { variant ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${variant.displayName}\n${variant.sizeMB / 1000.0f} GB · needs ${variant.minRamGB}GB+ RAM"
                tag = variant
            }
            options.addView(rb)
            if (variant.id == rec.primary.id) options.check(rb.id)
        }

        val downloadBtn = findViewById<Button>(R.id.download_button)
        val progress = findViewById<ProgressBar>(R.id.download_progress)
        downloadBtn.setOnClickListener {
            val selectedId = options.checkedRadioButtonId
            val selectedVariant = findViewById<RadioButton>(selectedId).tag as ModelRunner.ModelVariant
            downloadBtn.isEnabled = false
            progress.visibility = View.VISIBLE

            lifecycleScope.launch {
                downloader.download(selectedVariant).collectLatest { p ->
                    progress.progress = (p.fraction * 100).toInt()
                    if (p.fraction >= 1f) {
                        runner.load(selectedVariant)
                        startActivity(Intent(this@ModelPickerActivity, SetupChatActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }
}
```

**Step 4: Register activity in manifest**

In `launcher/src/main/AndroidManifest.xml`:
```xml
<activity
    android:name=".ui.setup.ModelPickerActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

**Step 5: Build**

```bash
./gradlew assembleQuickstepDebug
```

**Expected:** BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add -A
git commit -m "feat: add model picker activity with hardware-based recommendation"
git push origin foundation/rebrand
```

---

# Phase 3: Agent (Weeks 7-9)

> **Objective:** Make the AI agent actually drive the UI to configure the phone.

## Task 3.1: Build AccessibilityService for UI inspection

**Objective:** Service that can read the current foreground app's UI tree (similar to UiAutomator).

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/agent/DogoAccessibilityService.kt`
- Modify: `launcher/src/main/AndroidManifest.xml`
- Create: `launcher/src/main/res/xml/accessibility_service_config.xml`
- Modify: `launcher/src/main/res/values/strings.xml`

**Step 1: Create accessibility service config**

Create `launcher/src/main/res/xml/accessibility_service_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeViewClicked|typeViewFocused"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100"
    android:settingsActivity="app.dogo.launcher.ui.setup.SettingsActivity" />
```

**Step 2: Add description string**

In `launcher/src/main/res/values/strings.xml`:
```xml
<string name="accessibility_service_description">Dogo uses this to set up your phone and apps based on your conversation. It can see the foreground app to help configure it.</string>
```

**Step 3: Write failing test for AccessibilityService binding**

Create `launcher/src/test/java/app/dogo/launcher/agent/AccessibilityBindingTest.kt`:
```kotlin
package app.dogo.launcher.agent

import org.junit.Assert.assertNotNull
import org.junit.Test

class AccessibilityBindingTest {
    @Test
    fun accessibility_service_is_registered_in_manifest() {
        val serviceClass = DogoAccessibilityService::class.java
        assertNotNull(serviceClass)
        assert("AccessibilityService subclass of AccessibilityService") {
            android.accessibilityservice.AccessibilityService::class.java.isAssignableFrom(serviceClass)
        }
    }
}
```

**Step 4: Run test**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.agent.AccessibilityBindingTest"
```

**Expected:** PASS (the class compiles; full functionality test is on-device)

**Step 5: Implement AccessibilityService skeleton**

Create `launcher/src/main/java/app/dogo/launcher/agent/DogoAccessibilityService.kt`:
```kotlin
package app.dogo.launcher.agent

import android.accessibilityservice.AccessibilityService
import android.os.Parcelable
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the current foreground app's UI tree.
 * Can perform clicks, scrolls, text input on behalf of the user.
 *
 * CRITICAL: This service has access to everything visible on screen.
 * Treat as the user's sensitive key as a system service — never exfiltrate.
 */
class DogoAccessibilityService : AccessibilityService() {

    data class ScreenSnapshot(
        val packageName: String,
        val activityName: String,
        val visibleText: String,
        val interactiveElements: List<ElementRef>
    )

    data class ElementRef(
        val id: String?,
        val text: String?,
        val contentDescription: String?,
        val bounds: android.graphics.Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val className: String?
    )

    fun captureSnapshot(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        return buildSnapshot(root)
    }

    private fun buildSnapshot(root: AccessibilityNodeInfo): ScreenSnapshot {
        val elements = mutableListOf<ElementRef>()
        val textBuilder = StringBuilder()
        walk(root, elements, textBuilder, depth = 0)

        return ScreenSnapshot(
            packageName = root.packageName?.toString() ?: "unknown",
            activityName = root.className?.toString() ?: "unknown",
            visibleText = textBuilder.toString().trim(),
            interactiveElements = elements
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        elements: MutableList<ElementRef>,
        text: StringBuilder,
        depth: Int
    ) {
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        if (!nodeText.isNullOrBlank()) {
            repeat(times = depth) { text.append("  ") }
            text.append(nodeText).append("\n")
        }
        if (node.isClickable || node.isFocusable || node.isEditable) {
            elements.add(
                ElementRef(
                    id = node.viewIdResourceName,
                    text = nodeText,
                    contentDescription = nodeDesc,
                    bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) },
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    className = node.className?.toString()
                )
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, elements, text, depth + 1) }
        }
    }

    /**
     * Click an element by its text or content description.
     */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op for now; future: emit events to Flow for reactive agent
    }

    override fun onInterrupt() {
        // Required override
    }
}
```

**Step 6: Register service in manifest**

In `launcher/src/main/AndroidManifest.xml`:
```xml
<service
    android:name=".agent.DogoAccessibilityService"
    android:exported="true"
    android:label="@string/app_name"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

**Step 7: Build and commit**

```bash
./gradlew assembleQuickstepDebug
# Expected: BUILD SUCCESSFUL

git add -A
git commit -m "feat: add AccessibilityService for UI inspection + interaction"
git push origin foundation/rebrand
```

---

## Task 3.2: Build the setup chat UI

**Objective:** Conversation interface where the user tells the AI how they want their phone set up.

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/ui/setup/SetupChatActivity.kt`
- Create: `launcher/src/main/res/layout/activity_setup_chat.xml`
- Create: `launcher/src/main/java/app/dogo/launcher/ui/setup/ChatAdapter.kt`

**Step 1: Create the layout**

Create `launcher/src/main/res/layout/activity_setup_chat.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/chat_recycler"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:padding="8dp" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="8dp">

        <EditText
            android:id="@+id/input_field"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="@string/setup_chat_hint"
            android:inputType="text" />

        <Button
            android:id="@+id/send_button"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/setup_chat_send" />

    </LinearLayout>

</LinearLayout>
```

**Step 2: Add strings**

In `launcher/src/main/res/values/strings_setup.xml`:
```xml
<string name="setup_chat_hint">Tell me how you want your phone set up…</string>
<string name="setup_chat_send">Send</string>
```

**Step 3: Write failing test**

Create `launcher/src/test/java/app/dogo/launcher/ui/setup/SetupChatTest.kt`:
```kotlin
package app.dogo.launcher.ui.setup

import org.junit.Test
import org.junit.Assert.assertEquals

class SetupChatTest {
    @Test
    fun system_prompt_for_setup_includes_user_intent_question() {
        val prompt = SetupChatAgent.systemPrompt
        assert(prompt.contains("how the user wants their phone configured"))
    }
}
```

**Step 4: Run test to verify failure**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.ui.setup.SetupChatTest"
```

**Expected:** FAIL — "Unresolved reference: SetupChatAgent"

**Step 5: Implement the agent**

Create `launcher/src/main/java/app/dogo/launcher/ui/setup/SetupChatAgent.kt`:
```kotlin
package app.dogo.launcher.ui.setup

import app.dogo.launcher.llm.ModelRunner

/**
 * Conversational agent that helps them describe what they want
 * and translates that into configuration actions.
 */
object SetupChatAgent {

    val systemPrompt = """
        You are Dogo Phone Setup, an AI assistant that helps the user configure their Android phone.

        Your job: have a conversation to understand how the user wants their phone configured, then propose specific actions like:
        - "Add WhatsApp and Telegram to the home screen, hide social media apps in a folder"
        - "Set a dark theme and reduce animation speed"
        - "Block notifications from these apps during work hours"
        - "Move all banking apps to a Secure folder"

        Always ask clarifying questions before suggesting changes. Keep responses brief and conversational.
        When you have enough information, output a JSON plan with specific actions.

        Available actions (use these in your JSON plans):
        { "action": "set_wallpaper", "params": { "color": "#1E1E1E" } }
        { "action": "add_to_home", "params": { "package": "org.telegram.messenger" } }
        { "action": "hide_app", "params": { "package": "com.example.app" } }
        { "action": "create_folder", "params": { "name": "Work", "apps": ["com.slack", "com.microsoft.office"] } }
        { "action": "set_theme", "params": { "theme": "dark" } }
    """

    fun buildPrompt(userMessage: String, history: List<Pair<String, String>>): String {
        val historyText = history.joinToString("\n") { (role, msg) ->
            "${if (role == "user") "User" else "Assistant"}: $msg"
        }
        return """
            $systemPrompt

            Conversation so far:
            $historyText

            User: $userMessage
            Assistant:
        """.trimIndent()
    }
}
```

**Step 6: Implement the activity**

Create `launcher/src/main/java/app/dogo/launcher/ui/setup/SetupChatActivity.kt`:
```kotlin
package app.dogo.launcher.ui.setup

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.dogo.launcher.R
import app.dogo.launcher.llm.ModelRunner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SetupChatActivity : Activity() {

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var runner: ModelRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_chat)

        val recycler = findViewById<RecyclerView>(R.id.chat_recycler)
        val input = findViewById<EditText>(R.id.input_field)
        val sendBtn = findViewById<Button>(R.id.send_button)

        adapter = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        runner = ModelRunner(this)
        // NOTE: Model must already be loaded by ModelPickerActivity

        // Greet
        messages.add(ChatMessage("assistant", "Hi! Tell me how you want your phone set up. For example: 'minimal, no social games on the dock, dark theme, work apps in a folder.'"))
        adapter.notifyItemInserted(0)

        sendBtn.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")

            messages.add(ChatMessage("user", text))
            adapter.notifyItemInserted(messages.size - 1)
            recycler.scrollToPosition(messages.size - 1)

            lifecycleScope.launch {
                val history = messages.dropLast(1).map { it.role to it.text }
                val prompt = SetupChatAgent.buildPrompt(text, history)
                val responseBuilder = StringBuilder()
                runner.generateAsync(prompt).collectLatest { partial ->
                    responseBuilder.append(partial)
                    // Update or insert assistant message live
                    val lastIdx = messages.lastIndex
                    if (messages[lastIdx].role == "assistant-streaming") {
                        messages[lastIdx] = ChatMessage("assistant-streaming", responseBuilder.toString())
                        adapter.notifyItemChanged(lastIdx)
                    } else {
                        messages.add(ChatMessage("assistant-streaming", responseBuilder.toString()))
                        adapter.notifyItemInserted(messages.size - 1)
                    }
                    recycler.scrollToPosition(messages.size - 1)
                }
                // Finalize
                val lastIdx = messages.lastIndex
                if (messages[lastIdx].role == "assistant-streaming") {
                    messages[lastIdx] = ChatMessage("assistant", responseBuilder.toString())
                    adapter.notifyItemChanged(lastIdx)
                }
            }
        }
    }
}

data class ChatMessage(val role: String, val text: String)
```

**Step 7: Implement ChatAdapter**

Create `launcher/src/main/java/app/dogo/launcher/ui/setup/ChatAdapter.kt`:
```kotlin
package app.dogo.launcher.ui.setup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.dogo.launcher.R

class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val isUser = viewType == 1
        val layout = if (isUser) R.layout.item_chat_user else R.layout.item_chat_assistant
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view.findViewById(R.id.message_text))
    }

    override fun getItemCount() = messages.size
    override fun getItemViewType(position: Int) = if (messages[position].role.startsWith("user")) 1 else 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.textView.text = messages[position].text
    }
}
```

**Step 8: Create item layouts**

Create `launcher/src/main/res/layout/item_chat_user.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/message_text"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="8dp"
    android:padding="12dp"
    android:background="@drawable/bubble_user"
    android:textColor="#FFFFFF" />
```

Create `launcher/src/main/res/layout/item_chat_assistant.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/message_text"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="8dp"
    android:padding="12dp"
    android:background="@drawable/bubble_assistant" />
```

**Step 9: Create bubble backgrounds**

Create `launcher/src/main/res/drawable/bubble_user.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/dogo_accent" />
    <corners android:radius="16dp" />
</shape>
```

Create `launcher/src/main/res/drawable/bubble_assistant.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#E0E0E0" />
    <corners android:radius="16dp" />
</shape>
```

**Step 10: Register activity**

In `launcher/src/main/AndroidManifest.xml`:
```xml
<activity android:name=".ui.setup.SetupChatActivity" android:exported="false" />
```

**Step 11: Build and commit**

```bash
./gradlew assembleQuickstepDebug
# Expected: BUILD SUCCESSFUL

./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.ui.setup.SetupChatTest"
# Expected: PASS

git add -A
git commit -m "feat: add conversational setup chat UI"
git push origin foundation/rebrand
```

---

## Task 3.3: Wire agent actions to launcher config

**Agent: connect SetupChatAgent JSON output to actual Lawnchair config mutations.**

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/agent/ActionExecutor.kt`
- Create: `launcher/src/test/java/app/dogo/launcher/agent/ActionExecutorTest.kt`

**Step 1: Write failing test**

Create `launcher/src/test/java/app/dogo/launcher/agent/ActionExecutorTest.kt`:
```kotlin
package app.dogo.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun parses_set_theme_action() {
        val json = """{"action": "set_theme", "params": {"theme": "dark"}}"""
        val action = ActionExecutor.parseAction(json)
        assertTrue(action is ActionExecutor.Action.SetTheme)
        assertEquals("dark", (action as ActionExecutor.Action.SetTheme).theme)
    }

    @Test
    fun parses_create_folder_action() {
        val json = """{"action": "create_folder", "params": {"name": "Work", "apps": ["com.slack"]}}"""
        val action = ActionExecutor.parseAction(json)
        assertTrue(action is ActionExecutor.Action.CreateFolder)
        val fa = action as ActionExecutor.Action.CreateFolder
        assertEquals("Work", fa.name)
        assertEquals(listOf("com.slack"), fa.apps)
    }

    @Test
    fun returns_unknown_for_invalid_json() {
        val json = """{"action": "nonexistent"}"""
        val action = ActionExecutor.parseAction(json)
        assertTrue(action is ActionExecutor.Action.Unknown)
    }
}
```

**Step 2: Run test to verify failure**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.agent.ActionExecutorTest"
```

**Expected:** FAIL — "Unresolved reference: ActionExecutor"

**Step 3: Implement ActionExecutor**

Create `launcher/src/main/java/app/dogo/launcher/agent/ActionExecutor.kt`:
```kotlin
package app.dogo.launcher.agent

import org.json.JSONObject

/**
 * Parses and runs actions proposed by the AI agent.
 * Each action is a discrete change to the launcher config.
 */
class ActionExecutor {

    sealed class Action {
        data class SetTheme(val theme: String) : Action()
        data class SetWallpaper(val color: String) : Action()
        data class AddToHome(val pkg: String) : Action()
        data class HideApp(val pkg: String) : Action()
        data class CreateFolder(val name: String, val apps: List<String>) : Action()
        data class SetAnimationScale(val scale: Float) : Action()
        object Unknown : Action()
    }

    companion object {
        /**
         * Parse a JSON action string from the agent.
         * Returns Unknown on parse failure (does not throw).
         */
        fun parseAction(json: String): Action {
            return try {
                val obj = JSONObject(json)
                when (val action = obj.optString("action")) {
                    "set_theme" -> Action.SetTheme(obj.getJSONObject("params").optString("theme", "system"))
                    "set_wallpaper" -> Action.SetWallpaper(obj.getJSONObject("params").optString("color", "#1E1E1E"))
                    "add_to_home" -> Action.AddToHome(obj.getJSONObject("params").optString("package"))
                    "hide_app" -> Action.HideApp(obj.getJSONObject("params").optString("package"))
                    "create_folder" -> {
                        val params = obj.getJSONObject("params")
                        val appsArr = params.optJSONArray("apps")
                        val apps = (0 until (appsArr?.length() ?: 0)).mapNotNull { appsArr?.optString(it) }
                        Action.CreateFolder(params.optString("name", "Folder"), apps)
                    }
                    "set_animation_scale" -> Action.SetAnimationScale(obj.getJSONObject("params").optDouble("scale", 1.0).toFloat())
                    else -> Action.Unknown
                }
            } catch (e: Exception) {
                Action.Unknown
            }
        }
    }

    // Real execution requires the launcher service context.
    // Wired in next task.
    fun execute(action: Action): Boolean = false
}
```

**Step 4: Run test to verify pass**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.agent.ActionExecutorTest"
```

**Expected:** PASS (3 tests)

**Step 5: Commit**

```bash
git add -A
git commit -m "feat: add ActionExecutor for parsing agent JSON commands"
git push origin foundation/rebrand
```

---

## Task 3.4: Connect chat agent → AccessibilityService for app config

**Objective:** When the user says "set up WhatsApp notifications the way I like", the agent should be able to open WhatsApp's notification settings and adjust them.

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/agent/AgentOrchestrator.kt`
- Modify: `launcher/src/main/java/app/dogo/launcher/ui/setup/SetupChatActivity.kt`

**Step 1: Write the orchestrator**

Create `launcher/src/main/java/app/dogo/launcher/agent/AgentOrchestrator.kt`:
```kotlin
package app.dogo.launcher.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.dogo.launcher.llm.ModelRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Coordinates between the chat agent (LLM) and the AccessibilityService.
 *
 * Loop:
 *   1. User says something in SetupChatActivity
 *   2. Orchestrator asks LLM what action to take
 *   3. LLM emits a JSON action
 *   4. Orchestrator parses → opens relevant Android settings or app
 *   5. AccessibilityService reads the screen → confirms action was possible
 *   6. Loop until user is happy
 */
class AgentOrchestrator(
    private val context: Context,
    private val llm: ModelRunner,
    private val accessibilityService: DogoAccessibilityService
) {
    suspend fun handleUserIntent(userMessage: String, conversationHistory: List<Pair<String, String>>): Flow<String> = flow {
        // Step 1: Get agent's proposal
        val agentPrompt = buildPrompt(userMessage, conversationHistory)
        var agentResponse = ""
        llm.generateAsync(agentPrompt).collect { emit(it); agentResponse += it }

        // Step 2: Parse any action it suggested
        val action = extractFirstJsonAction(agentResponse)
        if (action != null) {
            val parsed = ActionExecutor.parseAction(action)
            executeAndReport(parsed)
        }
    }

    private suspend fun executeAndReport(action: ActionExecutor.Action) {
        when (action) {
            is ActionExecutor.Action.SetTheme -> {
                // Open system theme settings
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            is ActionExecutor.Action.HideApp -> {
                // In a real Lawnchair fork, this would write to launcher preferences
                // For now, log intent and emit guidance to user
            }
            // ... other actions
            else -> { /* No-op */ }
        }
    }

    private fun buildPrompt(userMessage: String, history: List<Pair<String, String>>): String {
        return SetupChatAgent.buildPrompt(userMessage, history) +
                "\n\nCurrent screen:\n${accessibilityService.captureSnapshot()?.visibleText ?: "(unknown)"}\n\n" +
                "If you need to make a change, respond with a JSON action block followed by a brief explanation."
    }

    private fun extractFirstJsonAction(text: String): String? {
        val regex = Regex("""\{[^{}]*"action"[^{}]*\{[^{}]*\}[^{}]*\}""")
        return regex.find(text)?.value
    }
}
```

**Step 2: Wire into SetupChatActivity**

In `launcher/src/main/java/app/dogo/launcher/ui/setup/SetupChatActivity.kt`, replace the send handler to use AgentOrchestrator:

```kotlin
import app.dogo.launcher.agent.AgentOrchestrator
import app.dogo.launcher.agent.DogoAccessibilityService

// In sendBtn.setOnClickListener:
val accessibilityService = DogoAccessibilityService.getInstance()
if (accessibilityService == null) {
    // Show prompt to enable accessibility service
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    return@setOnClickListener
}
val orchestrator = AgentOrchestrator(this, runner, accessibilityService)
// ... use orchestrator.handleUserIntent instead of direct runner.generateAsync
```

**Step 3: Add static instance getter to AccessibilityService**

In `DogoAccessibilityService.kt`, add:
```kotlin
companion object {
    @Volatile private var instance: DogoAccessibilityService? = null

    fun getInstance(): DogoAccessibilityService? = instance
}

override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    // ...
}

override fun onDestroy() {
    instance = null
    super.onDestroy()
}
```

**Step 4: Build and commit**

```bash
./gradlew assembleQuickstepDebug
# Expected: BUILD SUCCESSFUL

git add -A
git commit -m "feat: wire chat → orchestrator → AccessibilityService loop"
git push origin foundation/rebrand
```

---

# Phase 4: Distribution (Weeks 10-12)

> **Objective:** Make the launcher installable on regular Android phones without Google Play Store.

## Task 4.1: Add F-Droid installation flow to onboarding

**Objective:** During the chat setup, if user wants an app not installed, recommend F-Droid and guide them through install.

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/distribution/FdroidHelper.kt`
- Create: `launcher/src/test/java/app/dogo/launcher/distribution/FdroidHelperTest.kt`

**Step 1: Write failing test**

Create `launcher/src/test/java/app/dogo/launcher/distribution/FdroidHelperTest.kt`:
```kotlin
package app.dogo.launcher.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FdroidHelperTest {
    @Test
    fun fdroid_package_name_is_correct() {
        assertEquals("org.fdroid.fdroid", FdroidHelper.FDROID_PACKAGE)
    }

    @Test
    fun fdroid_download_url_is_official() {
        assertNotNull(FdroidHelper.FDROID_DOWNLOAD_URL)
        assert(FdroidHelper.FDROID_DOWNLOAD_URL.startsWith("https://"))
    }
}
```

**Step 2: Run test to verify failure**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.distribution.FdroidHelperTest"
```

**Expected:** FAIL — "Unresolved reference: FdroidHelper"

**Step 3: Implement FdroidHelper**

Create `launcher/src/main/java/app/dogo/launcher/distribution/FdroidHelper.kt`:
```kotlin
package app.dogo.launcher.distribution

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Helpers for installing F-Droid as the user's app source.
 * F-Droid is the recommended store for privacy-respecting users.
 */
object FdroidHelper {
    const val FDROID_PACKAGE = "org.fdroid.fdroid"
    const val FDROID_DOWNLOAD_URL = "https://www.f-droid.org/F-Droid.apk"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(FDROID_PACKAGE, 0)
            true
        } catch (e: Exception) { false }
    }

    /**
     * Open F-Droid if installed, otherwise open the download URL in browser.
     */
    fun openOrDownload(context: Context) {
        if (isInstalled(context)) {
            val launch = context.packageManager.getLaunchIntentForPackage(FDROID_PACKAGE)
            launch?.let { context.startActivity(it) }
        } else {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_DOWNLOAD_URL))
            context.startActivity(intent)
        }
    }
}
```

**Step 4: Run test to verify pass**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.distribution.FdroidHelperTest"
```

**Expected:** PASS

**Step 5: Commit**

```bash
git add -A
git commit -m "feat: add F-Droid helper for installer + launcher flow"
git push origin foundation/rebrand
```

---

## Task 4.2: Set up Obtanium for in-app model downloads

**Objective:** Use Obtanium's URL pattern so users can also fetch model APKs from there if they prefer.

(Out of scope for v0.1 — defer to Phase 6)

---

## Task 4.3: Publish to F-Droid

**Objective:** Submit metadata so F-Droid builds and signs the APK from source.

**Files:**
- Create: `metadata/app.dogo.launcher.yml` (already exists from Task 1.5)
- Modify: `README.md`

**Step 1: Add F-Droid submission instructions to README**

Create `F-DROID.md`:
```markdown
# Submitting to F-Droid

F-Droid builds all apps from source. They verify reproducibility — your build config must be deterministic.

1. Fork this repo to your own GitHub/GitLab
2. Add your fork URL to `metadata/app.dogo.launcher.yml`
3. Submit a merge request to https://gitlab.com/fdroid/fdroiddata
4. File format: https://f-droid.org/docs/Submitting_an_App/

F-Droid signing keys are separate from your own. The repo they build from must build successfully.
```

**Step 2: Verify the build is reproducible**

```bash
./gradlew clean
./gradlew assembleQuickstepRelease
./gradlew clean
./gradlew assembleQuickstepRelease
# Compare the two APKs:
sha256sum launcher/build/outputs/apk/quickstep/release/*.apk
# If hashes match, F-Droid will accept.
```

**Step 3: Tag a release**

```bash
git tag -a v0.1.0-alpha -m "First public alpha"
git push origin v0.1.0-alpha
```

**Step 4: Commit**

```bash
git add F-DROID.md
git commit -m "docs: add F-Droid submission guide"
git push origin foundation/rebrand
```

---

# Phase 5: Monetization (Week 13)

> **Objective:** Add the $5 cloud escalation tier without breaking the free experience.

## Task 5.1: Add cloud API config + key storage

**Objective:** Let users plug in OpenAI / Anthropic / OpenRouter keys for cloud escalation of complex queries.

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/cloud/CloudApiService.kt`
- Create: `launcher/src/main/java/app/dogo/launcher/cloud/KeyStorage.kt`
- Create: `launcher/src/test/java/app/dogo/launcher/cloud/KeyStorageTest.kt`

**Step 1: Write failing test**

Create `launcher/src/test/java/app/dogo/launcher/cloud/KeyStorageTest.kt`:
```kotlin
package app.dogo.launcher.cloud

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyStorageTest {
    @Test
    fun stored_key_can_be_retrieved() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = KeyStorage(ctx)
        storage.save("openai", "sk-test-123")
        assertEquals("sk-test-123", storage.get("openai"))
    }

    @Test
    fun deleted_key_returns_null() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = KeyStorage(ctx)
        storage.save("openai", "sk-test-123")
        storage.delete("openai")
        assertNull(storage.get("openai"))
    }
}
```

**Step 2: Run test**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.cloud.KeyStorageTest"
```

**Expected:** FAIL — "Unresolved reference: KeyStorage"

**Step 3: Implement KeyStorage using EncryptedSharedPreferences**

In `launcher/build.gradle.kts`:
```kotlin
dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
```

Create `launcher/src/main/java/app/dogo/launcher/cloud/KeyStorage.kt`:
```kotlin
package app.dogo.launcher.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for cloud API keys. Uses Android Keystore-backed encryption.
 */
class KeyStorage(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "dogo_api_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(provider: String, key: String) {
        prefs.edit().putString(provider, key).apply()
    }

    fun get(provider: String): String? = prefs.getString(provider, null)

    fun delete(provider: String) {
        prefs.edit().remove(provider).apply()
    }

    fun list(): Set<String> = prefs.all.keys
}
```

**Step 4: Run test**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.cloud.KeyStorageTest"
```

**Expected:** PASS

**Step 5: Commit**

```bash
git add -A
git commit -m "feat: add encrypted cloud API key storage"
git push origin foundation/rebrand
```

---

## Task 5.2: Implement cloud router with FunctionGemma as classifier

**Objective:** Use the local 270M FunctionGemma as a router that decides "should I escalate to cloud?"

**Files:**
- Create: `launcher/src/main/java/app/dogo/launcher/cloud/CloudRouter.kt`
- Create: `launcher/src/test/java/app/dogo/launcher/cloud/CloudRouterTest.kt`

**Step 1: Write failing test**

Create `launcher/src/test/java/app/dogo/launcher/cloud/CloudRouterTest.kt`:
```kotlin
package app.dogo.launcher.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRouterTest {
    @Test
    fun simple_query_does_not_escalate() {
        val result = CloudRouter.shouldEscalate(
            query = "Change wallpaper to dark",
            localConfidence = 0.9f
        )
        assertFalse(result.shouldEscalate)
    }

    @Test
    fun low_local_confidence_escalates() {
        val result = CloudRouter.shouldEscalate(
            query = "Configure the rest of my phone to be optimal for ADHD focus",
            localConfidence = 0.3f
        )
        assertTrue(result.shouldEscalate)
    }

    @Test
    fun complex_query_escalates_even_with_adequate_confidence() {
        val result = CloudRouter.shouldEscalate(
            query = "Set up my entire home screen with work apps, leisure apps, a focus mode, and a routine that adapts to my calendar",
            localConfidence = 0.8f
        )
        assertTrue(result.shouldEscalate)
    }
}
```

**Step 2: Run test to verify failure**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.cloud.CloudRouterTest"
```

**Expected:** FAIL — "Unresolved reference: CloudRouter"

**Step 3: Implement CloudRouter**

Create `launcher/src/main/java/app/dogo/launcher/cloud/CloudRouter.kt`:
```kotlin
package app.dogo.launcher.cloud

/**
 * Decides whether a query should be handled locally or escalated to cloud.
 *
 * Strategy:
 *   - High local confidence + simple query  -> local only
 *   - High local confidence + complex query -> escalate for quality
 *   - Low local confidence                 -> always escalate
 *
 * The actual scoring is done by FunctionGemma 270M running on device.
 * This is a heuristic fallback for when the router model isn't available.
 */
object CloudRouter {

    data class Decision(
        val shouldEscalate: Boolean,
        val reason: String,
        val complexityScore: Float
    )

    private val COMPLEX_KEYWORDS = setOf(
        "entire", "all", "everything", "optimal", "perfect", "complete",
        "comprehensive", "routine", "workflow", "automate", "integrate"
    )

    fun shouldEscalate(query: String, localConfidence: Float): Decision {
        val lower = query.lowercase()
        val complexity = computeComplexity(lower)

        return when {
            localConfidence < 0.5f -> Decision(
                shouldEscalate = true,
                reason = "Local model uncertain (confidence: $localConfidence)",
                complexityScore = complexity
            )
            complexity > 0.7f -> Decision(
                shouldEscalate = true,
                reason = "Complex multi-step request",
                complexityScore = complexity
            )
            else -> Decision(
                shouldEscalate = false,
                reason = "Local model can handle this",
                complexityScore = complexity
            )
        }
    }

    private fun computeComplexity(query: String): Float {
        val words = query.split(" ").size
        val complexWords = COMPLEX_KEYWORDS.count { query.contains(it) }
        val lengthScore = (words / 20f).coerceAtMost(1f)
        val keywordScore = (complexWords / 3f).coerceAtMost(1f)
        return (lengthScore * 0.5f + keywordScore * 0.5f)
    }
}
```

**Step 4: Run test**

```bash
./gradlew :launcher:testQuickstepDebugUnitTest --tests "app.dogo.launcher.cloud.CloudRouterTest"
```

**Expected:** PASS (3 tests)

**Step 5: Commit**

```bash
git add -A
git commit -m "feat: add CloudRouter with complexity heuristic"
git push origin foundation/rebrand
```

---

## Task 5.3: Add GitHub Sponsors + Stripe-backed key sales

**Objective:** Sell $5 cloud-tier unlock codes through Stripe or sell directly.

**Files:**
- Create: `pricing/CLOUD_TIER.md`

**Step 1: Document the pricing model**

Create `pricing/CLOUD_TIER.md`:
```markdown
# Cloud Tier Pricing

**Free tier:**
- All on-device LLM features
- F-Droid build
- Forever free

**$5 one-time — Cloud Unlock:**
- API key slot for OpenAI, Anthropic, OpenRouter, etc.
- 1 year of bug-fix releases
- Discord/support priority

**$10/month — Pro (optional):**
- Hosted cloud escalation included (no API key needed)
- Cross-device config sync
- Shared community setups

**Why $5 one-time:**
- Most users stay on local-only forever
- One-time matches FOSS conventions (Lawnchair, NewPipe, etc.)
- Subscription fatigue is real; people unsubscribe at $20+/mo
- Cloud is a value-add for power users, not core

**Implementation:**
- Stripe Payment Links for the $5 tier
- GitHub Sponsors tier for the $10/mo Pro
- License key verified on device via server check (or local signed token)
```

**Step 2: Set up Stripe Payment Link**

(Tutorial: https://stripe.com/docs/payment-links)

```
1. Stripe Dashboard → Payment Links → New
2. Product: "Dogo Cloud Unlock"
3. Price: $5.00 (one-time)
4. After payment: redirect to a page that emails the license key
5. License keys are signed JWTs with expiration
6. App verifies signature + expiration before unlocking cloud features
```

**Step 3: Commit**

```bash
git add pricing/
git commit -m "docs: document cloud tier pricing strategy"
git push origin foundation/rebrand
```

---

# Phase 6: Future Work (Conditional)

> **These tasks are explicitly NOT in v1.0.** Skip unless you hit a real wall.

## Phase 6.A: Custom ROM fork

**Only do this if:**
- Market validation shows >10k active weekly users
- You have ≥3 months of full-time bandwidth
- Permissions ceiling on standard Android stops being acceptable

**Approach:** Fork GrapheneOS or LineageOS, add your launcher as the default home, integrate with system-level permissions. Realistically 6-12 months of focused work for one developer.

## Phase 6.B: Cross-device config sync

E2E-encrypted sync of launcher layouts + AI agent conversations across devices. Use libsodium-watt for encryption, IndexedDB-style storage on backend.

## Phase 6.C: Multi-modal agent (vision)

Add a vision-capable model (Gemma 4 12B Unified, PaliGemma 2) so the agent can see screenshots, not just the accessibility tree. Useful for design decisions like "is this wallpaper a good fit?"

---

# Appendix A: Acceptance Criteria

The MVP (Phase 1-4) is considered **shipped** when:

1. ✅ App installs via F-Droid on Android 10+
2. ✅ Onboarding recommends based on device hardware
3. ✅ User can chat with on-device LLM (any Gemma model)
4. ✅ AccessibilityService can take screenshots and tap elements
5. ✅ SetupChatActivity guides user through configuration
6. ✅ App recommends F-Droid for new apps, opens it
7. ✅ All unit tests pass: `./gradlew test`
8. ✅ APK builds reproducible from source
9. ✅ GitHub Actions CI green

**Stretch goals:**
- $5 cloud tier live with Stripe
- 100 GitHub stars
- 10 active users testing it daily

---

# Appendix B: Quick Reference

**Common commands:**

```bash
# Build debug APK
./gradlew assembleQuickstepDebug

# Run all unit tests
./gradlew test

# Install on connected device
./gradlew installQuickstepDebug
adb shell am start -n app.dogo.launcher.debug/app.dogo.launcher.ui.setup.ModelPickerActivity

# Lint check
./gradlew lintQuickstepDebug

# Format Kotlin
./gradlew ktlintFormat
```

**File layout:**

```
dogo-launcher/
├── launcher/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/app/dogo/launcher/
│   │   │   │   ├── agent/                # AccessibilityService + ActionExecutor
│   │   │   │   ├── cloud/                # CloudRouter + KeyStorage
│   │   │   │   ├── distribution/         # FdroidHelper + ObtainiumHelper
│   │   │   │   ├── llm/                  # ModelRunner + Downloader + HardwareProfile
│   │   │   │   └── ui/setup/             # ModelPicker + SetupChat activities
│   │   │   └── res/
│   │   │       ├── layout/               # Activity layouts
│   │   │       ├── values/               # Strings, colors, themes
│   │   │       ├── drawable/             # Shapes, icons
│   │   │       └── xml/                  # AccessibilityService config
│   │   ├── test/                         # Unit tests (Robolectric)
│   │   └── androidTest/                  # Instrumented tests
│   └── build.gradle.kts
├── metadata/                             # F-Droid metadata
├── pricing/                              # Pricing strategy docs
├── scripts/                              # Build/test helpers
├── .github/workflows/                    # CI
└── README.md
```

**Useful resources:**

- MediaPipe LLM Inference: https://ai.google.dev/edge/mediapipe/solutions/genai/llm-inference
- Lawnchair docs: https://lawnchair.app/
- F-Droid build metadata: https://f-droid.org/docs/Build_Metadata_Reference/
- FunctionGemma: https://ai.google.dev/gemma/docs/functiongemma
- Android AccessibilityService: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService

---

# Appendix C: Risk Register

| Risk | Mitigation |
|---|---|
| Google Play Store delisting | Ship via F-Droid only; don't publish to Play. Play Store is hostile to accessibility-based automation. |
| AccessibilityService getting revoked by OS | Acceptable — degrade gracefully to "manual setup with chat guidance only" |
| Model download fails mid-flight | Resume support; store partial files in `*.part`, rename on complete |
| LLM gives bad config advice | Show all proposed actions to user before executing; require explicit "Apply" |
| User privacy: AI sees their apps | Document clearly. Open-source. Trust through transparency, not through promises. |
| Shizuku not granted | Detect and prompt; fall back to standard Android perms |
| F-Droid build takes weeks to review | Submit early; iterate while waiting. Build locally in parallel. |

---

# Appendix D: Test Data

When testing on-device, use these "user personas" to drive the agent:

**Persona 1: Minimalist**
> "Set up a minimal home screen. Just phone, messages, camera, browser. Hide everything else. Dark theme."

**Persona 2: Productivity**
> "Work apps in a folder called Work on the second home screen page. Personal apps on the first page. Focus mode during work hours 9-5."

**Persona 3: Accessibility**
> "Make all text and touch targets larger. High contrast. Slow down animations. Add a 'recent apps' shortcut to home."

**Persona 4: Family**
> "Set up kid mode — restrict app store to my approval. Family-friendly wallpaper. Larger text."

Each persona should result in a distinct, verifiable config.

---

*Plan version 0.1 — generated 2026-10-07*