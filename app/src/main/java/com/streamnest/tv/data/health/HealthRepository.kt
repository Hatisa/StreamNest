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

    /** Only one probe pass at a time — see [check]. */
    private val checkMutex = Mutex()

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
     * Probes up to [maxTargets] *channels*, least-recently-checked first, so the
     * table rotates instead of re-testing the same handful of channels.
     *
     * Each channel contributes exactly one target: its mirror with the oldest
     * measurement. Picking the primary stream (as this used to) meant a backup
     * mirror never got a score at all, so the fail-over in PlayerViewModel was
     * choosing between the primary's score and a constant 0.5. Rotating over the
     * mirrors instead gives every URL a score within a few passes while keeping
     * the per-pass cost proportional to the channel count.
     *
     * A light probe, not a bandwidth test: see [StreamProbe] for why. Returns the
     * number of channels examined.
     *
     * Two details matter for cost:
     *  * the whole pass writes the table **once** ([applyBatch]) instead of once
     *    per probe. Every write to [_metrics] wakes every collector — including
     *    ChannelRepository's `combine`, which re-maps and re-sorts the entire
     *    channel list. Writing per probe turned one 150-target pass into 150 full
     *    re-sorts of a 20k-entry list.
     *  * [checkMutex] serialises passes. The UI refresh and RefreshWorker can
     *    fire together, and without this they would probe overlapping targets and
     *    each pay for a full table rewrite.
     */
    suspend fun check(
        channels: List<Channel>,
        maxTargets: Int = 150,
        concurrency: Int = 6,
    ): Int = checkMutex.withLock {
        val snapshot = _metrics.value

        fun lastChecked(url: String) = snapshot[url]?.measuredAt ?: 0L

        val targets = channels
            .mapNotNull { channel ->
                channel.streams.minByOrNull { lastChecked(it.url) }?.let { channel to it }
            }
            .sortedBy { lastChecked(it.second.url) }
            .take(maxTargets)
        if (targets.isEmpty()) return@withLock 0

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))

        val results: List<Pair<String, ProbeResult>> = coroutineScope {
            targets.map { (_, stream) ->
                async {
                    semaphore.withPermit {
                        val result = runCatching {
                            probe.probe(stream.url, stream.httpUserAgent, stream.httpReferrer)
                        }.getOrElse { ProbeResult(false, 0, "probe_error") }
                        stream.url to result
                    }
                }
            }.awaitAll()
        }

        applyBatch(results)
        persist()
        targets.size
    }

    /**
     * Applies a whole probe pass in one shot.
     *
     * Blending happens here rather than inside each probe coroutine for two
     * reasons: the previous score is read against the *current* table (so a probe
     * result never overwrites a real playback failure recorded while the pass was
     * running), and the table is published once instead of once per probe. Every
     * publish wakes ChannelRepository's `combine`, which re-maps and re-sorts the
     * entire channel list, so a pass now costs one re-sort instead of one per
     * probe.
     *
     * The compare-and-set retry loop exists for the same reason as
     * [applyInMemory]: a single-entry write from the UI thread (playback feedback)
     * can land between reading [current] and publishing, and neither update may be
     * dropped.
     */
    private fun applyBatch(results: List<Pair<String, ProbeResult>>) {
        if (results.isEmpty()) return
        while (true) {
            val current = _metrics.value
            val now = System.currentTimeMillis()
            var next = current
            for ((url, result) in results) {
                val prior = next[url]
                val score = HealthScorer.blend(prior?.score, HealthScorer.scoreFromProbe(result))
                val record = HealthRecord(
                    url = url,
                    status = HealthScorer.statusFor(score, hasProbe = true),
                    latencyMs = result.latencyMs,
                    score = score,
                    failureKind = result.failureKind,
                    measuredAt = now,
                )
                next = next + (url to record)
            }
            if (_metrics.compareAndSet(current, next)) return
        }
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
