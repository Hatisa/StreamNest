package com.streamnest.tv.data.health

import com.streamnest.tv.data.model.Channel
import com.streamnest.tv.data.model.HealthRecord
import com.streamnest.tv.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Owns the per-stream health table and the two things that fill it in.
 *
 * Two signals feed the same table:
 *  1. the background probe pass ([check]), and
 *  2. what actually happens during playback ([recordPlaybackSuccess] /
 *     [recordPlaybackFailure]) — the strongest signal there is, because a stream
 *     the user just failed to open is broken regardless of what a probe thought.
 *
 * Every write goes through compare-and-set ([applyInMemory]) because callers
 * arrive from the main thread (playback events), Dispatchers.Default (the app
 * scope) and the probe workers at the same time; a plain `value = value + entry`
 * would silently drop concurrent updates, and losing a playback failure is
 * exactly the bug that would make the list rot.
 */
class HealthRepository(
    private val settings: SettingsStore,
    private val probe: StreamProbe,
    private val scope: CoroutineScope,
) {

    private val _metrics = MutableStateFlow<Map<String, HealthRecord>>(emptyMap())
    val metrics: StateFlow<Map<String, HealthRecord>> = _metrics.asStateFlow()

    /** Serialises disk writes; each write stores the freshest snapshot. */
    private val persistMutex = Mutex()

    suspend fun load() {
        _metrics.value = settings.metrics().associateBy { it.url }
    }

    fun recordPlaybackFailure(url: String, failureKind: String = "player_error") {
        applyInMemory(url) { prior, now ->
            val score = HealthScorer.blend(prior?.score, 0.0)
            HealthRecord(
                url = url,
                status = HealthScorer.statusFor(score, hasProbe = true),
                latencyMs = prior?.latencyMs,
                score = score,
                failureKind = failureKind,
                measuredAt = now,
            )
        }
        persist()
    }

    fun recordPlaybackSuccess(url: String, latencyMs: Long? = null) {
        applyInMemory(url) { prior, now ->
            val measured = latencyMs ?: prior?.latencyMs ?: STARTUP_LATENCY_MS
            val score = HealthScorer.blend(prior?.score, HealthScorer.scoreFromProbe(ProbeResult(true, measured)))
            HealthRecord(
                url = url,
                status = HealthScorer.statusFor(score, hasProbe = true),
                latencyMs = measured,
                score = score,
                failureKind = null,
                measuredAt = now,
            )
        }
        persist()
    }

    /**
     * Probes up to [maxTargets] streams, least-recently-checked first, so the
     * table rotates instead of re-testing the same handful of streams.
     *
     * A light probe, not a bandwidth test: see [StreamProbe] for why. Returns the
     * number of streams examined.
     */
    suspend fun check(channels: List<Channel>, maxTargets: Int = 150, concurrency: Int = 6): Int {
        val snapshot = _metrics.value
        val targets = channels
            .mapNotNull { channel -> channel.primaryStream?.let { channel to it } }
            .sortedBy { snapshot[it.second.url]?.measuredAt ?: 0L }
            .take(maxTargets)
        if (targets.isEmpty()) return 0

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))

        coroutineScope {
            targets.map { (_, stream) ->
                async {
                    semaphore.withPermit {
                        val result = runCatching {
                            probe.probe(stream.url, stream.httpUserAgent, stream.httpReferrer)
                        }.getOrElse { ProbeResult(false, 0, "probe_error") }

                        // Blend against the value at write time, not against the
                        // snapshot taken before a job that can run for minutes —
                        // otherwise a probe pass would overwrite real playback
                        // results collected while it was running.
                        applyInMemory(stream.url) { prior, now ->
                            val score = HealthScorer.blend(prior?.score, HealthScorer.scoreFromProbe(result))
                            HealthRecord(
                                url = stream.url,
                                status = HealthScorer.statusFor(score, hasProbe = true),
                                latencyMs = result.latencyMs,
                                score = score,
                                failureKind = result.failureKind,
                                measuredAt = now,
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        persist()
        return targets.size
    }

    /**
     * Lock-free read-modify-write of one entry.
     *
     * [MutableStateFlow.compareAndSet] retries the whole transformation when
     * another thread won the race, so no update is ever lost.
     */
    private fun applyInMemory(url: String, transform: (HealthRecord?, Long) -> HealthRecord) {
        while (true) {
            val current = _metrics.value
            val next = current + (url to transform(current[url], System.currentTimeMillis()))
            if (_metrics.compareAndSet(current, next)) return
        }
    }

    /**
     * Fire-and-forget persistence. The snapshot is read *inside* the launched
     * block so the file always receives the latest table; the mutex keeps two
     * writes from interleaving.
     */
    private fun persist() {
        scope.launch {
            persistMutex.withLock {
                runCatching { settings.saveMetrics(_metrics.value.values.toList()) }
            }
        }
    }

    private companion object {
        /** Used when playback succeeded but reported no measurable latency. */
        const val STARTUP_LATENCY_MS = 800L
    }
}
