package com.streamnest.tv.data.health

import com.streamnest.tv.data.model.HealthStatus

data class ProbeResult(
    val ok: Boolean,
    val latencyMs: Long,
    val failureKind: String? = null,
)

/**
 * Turns a raw probe into a 0..1 score and blends it over time.
 *
 * Pure functions on purpose: this is the heart of "the app still works in six
 * months", so it must be trivially unit-testable and have no hidden state.
 */
object HealthScorer {

    const val GOOD_LATENCY_MS = 1_500L
    const val FAIL_LATENCY_MS = 6_000L

    /** Weight of the newest probe in the moving average. */
    private const val BLEND_WEIGHT = 0.6

    /** Public so the UI can colour by the same thresholds used to score. */
    const val OK_THRESHOLD = 0.7
    const val SLOW_THRESHOLD = 0.45

    const val NEUTRAL_SCORE = 0.5

    fun scoreFromProbe(result: ProbeResult): Double {
        if (!result.ok) return 0.0
        val latency = result.latencyMs.coerceAtLeast(0)
        if (latency <= GOOD_LATENCY_MS) return 1.0
        if (latency >= FAIL_LATENCY_MS) return 0.35
        val span = (FAIL_LATENCY_MS - GOOD_LATENCY_MS).toDouble()
        val ratio = (latency - GOOD_LATENCY_MS) / span
        return 1.0 - ratio * 0.65
    }

    /**
     * Exponential moving average: one transient failure (or one lucky fast
     * probe) must not reshuffle the list. A stream that stays broken sinks
     * within a few passes; a stream that recovers climbs back.
     */
    fun blend(previous: Double?, next: Double, weight: Double = BLEND_WEIGHT): Double {
        if (previous == null) return next.coerceIn(0.0, 1.0)
        return (previous * (1 - weight) + next * weight).coerceIn(0.0, 1.0)
    }

    fun statusFor(score: Double, hasProbe: Boolean): HealthStatus = when {
        !hasProbe -> HealthStatus.UNKNOWN
        score >= OK_THRESHOLD -> HealthStatus.OK
        score >= SLOW_THRESHOLD -> HealthStatus.SLOW
        else -> HealthStatus.FAILED
    }
}
