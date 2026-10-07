package app.dogo.launcher.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates [HardwareProfile] against real hardware rather than fixtures.
 *
 * The unit tests in `dogo/tests` pin the selection *rules*; they cannot tell us
 * whether the numbers we sample off a real device are the right ones. This does.
 */
@RunWith(AndroidJUnit4::class)
class HardwareProfileDeviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun totalRamGb(): Int {
        val info = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(info)
        return (info.totalMem / ModelCatalog.BYTES_PER_GB).toInt()
    }

    private fun freeStorageGb(): Int {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.availableBytes / ModelCatalog.BYTES_PER_GB).toInt()
    }

    @Test
    fun deviceRamAndStorageAreSampledSensibly() {
        val ram = totalRamGb()
        val free = freeStorageGb()
        println("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.first()})")
        println("ram=$ram GB  freeStorage=$free GB")

        // Guards a units bug: totalMem is a byte count, so a missing divide
        // would report ~11 million "GB".
        assertThat(ram).isIn(1..256)
        assertThat(free).isIn(0..4096)
    }

    @Test
    fun recommendationAgreesWithSampledHardware() {
        val recommendation = HardwareProfile.fromDevice(context)
        println("recommendation: ${recommendation.rationale}")

        val ram = totalRamGb()
        val free = freeStorageGb()

        if (recommendation.primary == null) {
            assertThat(recommendation.cloudOnly).isTrue()
            // Nothing fit. On a real phone that should only happen for a
            // genuinely low-end device or a full disk, so say which.
            assertThat("unexpected cloud-only on ${ram}GB/$free GB free")
                .isEqualTo(true)
        } else {
            assertThat(recommendation.cloudOnly).isFalse()
            val chosen = recommendation.primary!!
            assertThat(ram).isAtLeast(chosen.minRamGb)
            assertThat(free * ModelCatalog.BYTES_PER_GB)
                .isAtLeast(ModelCatalog.requiredStorageBytes(chosen))
        }
    }

    @Test
    fun recommendationIsStableAcrossRepeatedCalls() {
        val first = HardwareProfile.fromDevice(context)
        val second = HardwareProfile.fromDevice(context)
        assertThat(second.primary?.id).isEqualTo(first.primary?.id)
    }

    /**
     * The launcher must stay usable even when the model cannot load, so the
     * models directory has to be writable and the download target must be
     * inside it.
     */
    @Test
    fun modelsDirectoryIsUsable() {
        val dir = File(context.filesDir, "models")
        assertThat(dir.exists() || dir.mkdirs()).isTrue()
        assertThat(dir.isDirectory).isTrue()

        val probe = File(dir, ".probe")
        assertThat(probe.createNewFile()).isTrue()
        assertThat(probe.delete()).isTrue()
    }
}
