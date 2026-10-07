package app.dogo.launcher

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves Robolectric can boot an Android context against our compileSdk
 * (37 / Android 17) and that merged app resources are visible to unit tests.
 *
 * CURRENTLY DISABLED — see `dogo/BLOCKERS.md` (#1) for the full diagnosis.
 *
 * Summary: AGP 9.4.1 runs a bytecode-instrumentation transform over every jar
 * on the unit-test runtime classpath. Robolectric pulls in
 * `org.bouncycastle:bcprov-jdk18on:1.85`, which is a *signed* multi-release jar
 * carrying 7,285 per-entry SHA-256 digests in its manifest. AGP rewrites the
 * class bytes but does not recompute those digests, so the JDK's JarVerifier
 * rejects the jar while `AndroidTestEnvironment.<clinit>` registers the
 * BouncyCastle provider:
 *
 *   java.lang.InternalError: cannot create instance of
 *   org.bouncycastle.jcajce.provider.digest.Blake2b$Mappings :
 *   java.lang.SecurityException: SHA-256 digest error for
 *   org/bouncycastle/internal/asn1/misc/MiscObjectIdentifiers.class
 *
 * Verified: the cached bcprov jars are byte-identical to Maven Central
 * (`698ebb55...` / `2320b854...`), so the corruption is introduced by Gradle's
 * transform cache at `~/.gradle/caches/9.8.0/transforms/...`.
 * `-Pandroid.experimental.self-instrumenting=false` does not avoid it.
 *
 * Everything else in `dogo/tests` is plain JVM JUnit and is unaffected.
 */
@Ignore("Blocked by AGP jar-instrumentation corrupting signed MR-JARs; see dogo/BLOCKERS.md #1")
@RunWith(RobolectricTestRunner::class)
class RobolectricSmokeTest {

    @Test
    fun `robolectric provides a real application context`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(context).isNotNull()
        assertThat(context.packageName).isNotEmpty()
    }

    @Test
    fun `merged app resources are readable from unit tests`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val appName = context.getString(com.android.launcher3.R.string.app_name)
        assertThat(appName).isNotEmpty()
    }
}