package app.dogo.launcher.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Decides whether a query should be answered by the on-device model or
 * escalated to a cloud provider.
 */
class CloudRouterTest {

    @Test
    fun `simple query with high local confidence stays local`() {
        val decision = CloudRouter.shouldEscalate(
            query = "Change wallpaper to dark",
            localConfidence = 0.9f,
        )

        assertThat(decision.shouldEscalate).isFalse()
        assertThat(decision.reason).isNotEmpty()
    }

    @Test
    fun `low local confidence always escalates`() {
        val decision = CloudRouter.shouldEscalate(
            query = "Change wallpaper to dark",
            localConfidence = 0.3f,
        )

        assertThat(decision.shouldEscalate).isTrue()
        assertThat(decision.reason).contains("0.3")
    }

    @Test
    fun `complex query escalates even with good confidence`() {
        val decision = CloudRouter.shouldEscalate(
            query = "Set up my entire home screen with work apps, leisure apps, " +
                "a focus mode, and a routine that adapts to my calendar",
            localConfidence = 0.8f,
        )

        assertThat(decision.shouldEscalate).isTrue()
    }

    @Test
    fun `confidence exactly at the boundary is treated as local`() {
        val decision = CloudRouter.shouldEscalate(
            query = "Set the wallpaper colour",
            localConfidence = CloudRouter.LOCAL_CONFIDENCE_THRESHOLD,
        )

        assertThat(decision.shouldEscalate).isFalse()
    }

    @Test
    fun `blank query does not blow up`() {
        val decision = CloudRouter.shouldEscalate(query = "", localConfidence = 0.9f)

        assertThat(decision.shouldEscalate).isFalse()
        assertThat(decision.complexityScore).isAtLeast(0f)
    }

    @Test
    fun `complexity score is bounded to zero and one`() {
        val shortQuery = CloudRouter.shouldEscalate("dark", 0.9f).complexityScore
        val hugeQuery = CloudRouter.shouldEscalate(
            "everything all optimal perfect complete comprehensive routine " +
                "workflow automate integrate and then some more words to make it long",
            0.9f,
        ).complexityScore

        assertThat(shortQuery).isIn(com.google.common.collect.Range.closed(0f, 1f))
        assertThat(hugeQuery).isIn(com.google.common.collect.Range.closed(0f, 1f))
        assertThat(hugeQuery).isGreaterThan(shortQuery)
    }

    @Test
    fun `matching is case insensitive`() {
        val lower = CloudRouter.shouldEscalate("set up my entire home screen", 0.9f)
        val upper = CloudRouter.shouldEscalate("SET UP MY ENTIRE HOME SCREEN", 0.9f)

        assertThat(upper.complexityScore).isEqualTo(lower.complexityScore)
    }
}
