package com.streamnest.tv

import com.streamnest.tv.data.health.HealthScorer
import com.streamnest.tv.data.health.ProbeResult
import com.streamnest.tv.data.model.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health score is what keeps the list usable over months. Its contract must
 * hold exactly: fast = 1.0, dead = 0.0, and one bad probe must not wipe a good
 * channel out.
 */
class HealthScorerTest {

    @Test
    fun `fast successful probe scores full marks`() {
        val score = HealthScorer.scoreFromProbe(ProbeResult(true, latencyMs = 200))
        assertEquals(1.0, score, 0.0001)
    }

    @Test
    fun `failed probe scores zero`() {
        assertEquals(0.0, HealthScorer.scoreFromProbe(ProbeResult(false, 100)), 0.0001)
    }

    @Test
    fun `score decreases monotonically with latency`() {
        val fast = HealthScorer.scoreFromProbe(ProbeResult(true, 1_000))
        val mid = HealthScorer.scoreFromProbe(ProbeResult(true, 4_000))
        val slow = HealthScorer.scoreFromProbe(ProbeResult(true, 6_000))
        assertTrue(fast > mid)
        assertTrue(mid > slow)
        assertTrue(slow >= 0.0)
    }

    @Test
    fun `first probe becomes the score directly`() {
        assertEquals(1.0, HealthScorer.blend(null, 1.0), 0.0001)
        assertEquals(0.0, HealthScorer.blend(null, 0.0), 0.0001)
    }

    @Test
    fun `blend is a moving average in both directions`() {
        // A previously healthy stream that fails once drops but is not erased.
        val afterOneFailure = HealthScorer.blend(1.0, 0.0)
        assertTrue(afterOneFailure in 0.2..0.6)

        // A previously dead stream that recovers climbs back up.
        val afterRecovery = HealthScorer.blend(0.0, 1.0)
        assertTrue(afterRecovery in 0.4..0.8)
    }

    @Test
    fun `score is clamped to zero one`() {
        assertTrue(HealthScorer.blend(1.0, 1.0) <= 1.0)
        assertTrue(HealthScorer.blend(0.0, 0.0) >= 0.0)
    }

    @Test
    fun `status thresholds are stable`() {
        assertEquals(HealthStatus.UNKNOWN, HealthScorer.statusFor(0.9, hasProbe = false))
        assertEquals(HealthStatus.OK, HealthScorer.statusFor(0.9, hasProbe = true))
        assertEquals(HealthStatus.SLOW, HealthScorer.statusFor(0.5, hasProbe = true))
        assertEquals(HealthStatus.FAILED, HealthScorer.statusFor(0.1, hasProbe = true))
    }
}
