package app.dogo.launcher.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Picks the biggest model a device can plausibly run.
 *
 * The decision is deliberately conservative: loading a model that the device
 * cannot hold produces an out-of-memory kill of the launcher itself, which is
 * far worse than answering with a smaller model.
 */
class HardwareProfileTest {

    private fun recommend(ramGb: Int, freeStorageGb: Int, hasNpu: Boolean = false) = HardwareProfile.recommendModel(ramGb, freeStorageGb, hasNpu)

    @Test
    fun `high ram device gets the largest model`() {
        val rec = recommend(ramGb = 12, freeStorageGb = 20)

        assertThat(rec.primary?.id).isEqualTo("gemma-4-e4b-q4")
    }

    @Test
    fun `mid ram device gets the smaller model`() {
        val rec = recommend(ramGb = 6, freeStorageGb = 8)

        assertThat(rec.primary?.id).isEqualTo("gemma-4-e2b-q4")
    }

    @Test
    fun `device that cannot hold the smallest model gets no on-device model`() {
        val rec = recommend(ramGb = 2, freeStorageGb = 8)

        assertThat(rec.primary).isNull()
        assertThat(rec.cloudOnly).isTrue()
    }

    @Test
    fun `ample ram but no free storage gets no on-device model`() {
        val rec = recommend(ramGb = 12, freeStorageGb = 3)

        assertThat(rec.primary).isNull()
        assertThat(rec.cloudOnly).isTrue()
    }

    @Test
    fun `an npu does not promote the model past the ram ceiling`() {
        // The original plan had an NPU check that always evaluated to `true`,
        // so any device reporting an NPU skipped the RAM ceiling entirely.
        val withNpu = recommend(ramGb = 2, freeStorageGb = 8, hasNpu = true)
        val withoutNpu = recommend(ramGb = 2, freeStorageGb = 8, hasNpu = false)

        assertThat(withNpu.primary).isNull()
        assertThat(withNpu.primary).isEqualTo(withoutNpu.primary)
    }

    @Test
    fun `free storage requirement covers the model plus headroom`() {
        val variant = ModelCatalog.findById("gemma-4-e2b-q4")!!

        // One byte under what is needed, plus no headroom, is not enough.
        val tooTight = recommend(ramGb = 6, freeStorageGb = ModelCatalog.requiredStorageGb(variant))
        assertThat(tooTight.primary).isEqualTo(variant)

        val belowModel = recommend(
            ramGb = 6,
            freeStorageGb = ((variant.sizeBytes + ModelCatalog.STORAGE_HEADROOM_BYTES) / (1024 * 1024 * 1024) - 1)
                .toInt(),
        )
        assertThat(belowModel.primary).isNull()
    }

    @Test
    fun `rationale explains the choice`() {
        assertThat(recommend(ramGb = 12, freeStorageGb = 20).rationale).isNotEmpty()
        assertThat(recommend(ramGb = 2, freeStorageGb = 8).rationale).contains("cloud")
    }

    @Test
    fun `negative and zero inputs do not throw`() {
        assertThat(recommend(ramGb = -1, freeStorageGb = -1).primary).isNull()
        assertThat(recommend(ramGb = 0, freeStorageGb = 0).primary).isNull()
    }
}
