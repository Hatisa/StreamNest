package com.streamnest.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.streamnest.tv.StreamNestApp
import com.streamnest.tv.data.model.Channel
import com.streamnest.tv.data.model.Stream
import com.streamnest.tv.player.Media3PlayerEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val channel: Channel? = null,
    val attempt: Int = 0,
    val totalCandidates: Int = 0,
    val retrying: Boolean = false,
    val failed: Boolean = false,
    val message: String? = null,
)

/**
 * Owns the ExoPlayer instance and the mirror-failover loop.
 *
 * This is the pragmatic answer to "public IPTV links rot": a channel usually
 * carries several URLs for the same broadcast, so when one fails to *render*
 * (not merely to connect — a stream that shows a frame and then stalls is still
 * a working stream) the next-best mirror from the health table is tried
 * automatically. That single behaviour is what makes the app feel stable even
 * though the underlying free links are not.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val container = (application as StreamNestApp).container

    val engine = Media3PlayerEngine
    val player: ExoPlayer = engine.createPlayer(application)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var candidates: List<Stream> = emptyList()
    private var index: Int = 0
    private var renderedSomething = false
    private var midStreamFailures = 0

    /** In-flight mid-stream reconnect, so repeated errors cannot stack up. */
    private var reconnectJob: Job? = null

    private val listener = object : Player.Listener {

        override fun onRenderedFirstFrame() {
            renderedSomething = true
            midStreamFailures = 0
            val url = candidates.getOrNull(index)?.url ?: return
            container.health.recordPlaybackSuccess(url, null)
            _state.value = _state.value.copy(failed = false, retrying = false, message = null)
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = candidates.getOrNull(index)?.url
            if (!renderedSomething) {
                // Nothing ever rendered: this URL is genuinely unusable — report
                // it and try the next mirror.
                if (url != null) container.health.recordPlaybackFailure(url, error.errorCodeName)
                advance()
                return
            }

            // Mid-stream failure. The URL clearly worked a moment ago (e.g. a
            // flaky CDN edge), so jumping to a different broadcast is worse than
            // reconnecting. `onPlayerError` means the player has already stopped
            // (Media3 does NOT re-prepare on its own), so the reconnect has to be
            // issued here — the earlier version only changed the message and left
            // the UI stuck on "reconnecting" forever. Only after repeated
            // failures do we demote the URL and fail over to the next mirror.
            midStreamFailures += 1
            if (url != null && midStreamFailures >= MID_STREAM_FAILURE_LIMIT) {
                container.health.recordPlaybackFailure(url, error.errorCodeName)
                advance()
            } else {
                scheduleReconnect()
            }
        }
    }

    /**
     * Re-prepares the current stream after a short delay.
     *
     * The reconnect goes through [prepareCurrent] with `resetFailures = false`
     * on purpose: if re-preparing cleared the counter, a stream that dies every
     * few seconds would reconnect forever instead of reaching the fail-over
     * limit. One failure is counted per *attempt* — a successful frame resets it
     * (see [onRenderedFirstFrame]). Any pending reconnect is cancelled first, and
     * a reconnect whose counter was cleared meanwhile (user retry, channel
     * change) bails out instead of fighting the newer state.
     */
    private fun scheduleReconnect() {
        val attempt = midStreamFailures
        val message = "播放中断，正在自动重连…（$attempt/$MID_STREAM_FAILURE_LIMIT）"
        _state.value = _state.value.copy(retrying = true, message = message)
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(RECONNECT_DELAY_MS * attempt)
            if (midStreamFailures != attempt) return@launch
            prepareCurrent(resetFailures = false, connectingMessage = message)
        }
    }

    init {
        player.addListener(listener)
        player.repeatMode = Player.REPEAT_MODE_OFF
    }

    fun open(channel: Channel) {
        if (_state.value.channel?.id == channel.id) return

        // Best mirror first: health score descending, unknown scoring as neutral.
        candidates = channel.streams.sortedByDescending { stream ->
            container.health.metrics.value[stream.url]?.score ?: 0.5
        }
        cancelReconnect()

        _state.value = PlayerUiState(
            channel = channel,
            attempt = 0,
            totalCandidates = candidates.size,
        )

        if (candidates.isEmpty()) {
            _state.value = _state.value.copy(failed = true, message = "该频道没有可用的播放地址")
            return
        }
        prepareCurrent()
    }

    fun retry() {
        if (candidates.isEmpty()) return
        cancelReconnect()
        index = 0
        renderedSomething = false
        midStreamFailures = 0
        prepareCurrent()
    }

    /**
     * Loads the stream at [index] into the player.
     *
     * [resetFailures] is false only for the automatic mid-stream reconnect, which
     * must keep counting so that a repeatedly dying stream eventually fails over
     * instead of reconnecting forever. [connectingMessage] is supplied by the
     * reconnect path so the overlay explains *why* the picture is blank.
     */
    private fun prepareCurrent(resetFailures: Boolean = true, connectingMessage: String? = null) {
        val stream = candidates.getOrNull(index) ?: return
        renderedSomething = false
        if (resetFailures) {
            midStreamFailures = 0
            cancelReconnect()
        }
        // A message is present exactly while the user is waiting on a switch or a
        // reconnect, which is also when the overlay belongs. The initial open of a
        // channel deliberately leaves it null (and retrying false) so the player
        // shows its own buffering indicator instead of a redundant overlay.
        val message = connectingMessage ?: if (index > 0) {
            "镜像 ${index + 1}/${candidates.size} 连接失败，正在切换"
        } else {
            null
        }
        _state.value = _state.value.copy(
            attempt = index + 1,
            totalCandidates = candidates.size,
            retrying = message != null,
            failed = false,
            message = message,
        )
        player.setMediaSource(engine.createMediaSource(app, stream))
        player.prepare()
        player.playWhenReady = true
    }

    private fun advance() {
        if (index + 1 < candidates.size) {
            index += 1
            prepareCurrent()
        } else {
            cancelReconnect()
            _state.value = _state.value.copy(
                retrying = false,
                failed = true,
                message = "所有镜像都无法播放，可以稍后重试或换一个频道",
            )
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelReconnect()
        player.removeListener(listener)
        player.release()
    }

    private companion object {
        const val MID_STREAM_FAILURE_LIMIT = 3

        /** Backoff between mid-stream reconnect attempts, multiplied by attempt. */
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
