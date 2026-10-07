package app.dogo.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Smoke test proving the unit-test harness works for the app module.
 *
 * This is intentionally trivial — its job is to fail loudly if the Gradle test
 * wiring (src/test/java, testImplementation deps) is broken, so that every
 * other Dogo test can be trusted.
 */
class TestHarnessSmokeTest {

    @Test
    fun `pure junit works`() {
        assertThat(2 + 2).isEqualTo(4)
    }
}
