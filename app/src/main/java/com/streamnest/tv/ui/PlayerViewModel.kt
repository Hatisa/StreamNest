package com.streamnest.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.streamnest.tv.StreamNestApp
import com.streamnest.tv.data.model.Channel
import com.streamnest.tv.data.model.Stream
import com.streamnest.tv.player.Media3PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            // flaky CDN edge), so jumping to a different broadcast would be
            // worse than reconnecting: let Media3's own retry policy run, and
            // only move on once the stream has failed repeatedly.
            midStreamFailures += 1
            if (url != null && midStreamFailures >= MID_STREAM_FAILURE_LIMIT) {
                // Demote exactly once, then fail over (or give up) rather than
                // leaving the UI stuck on "reconnecting" forever.
                if (midStreamFailures == MID_STREAM_FAILURE_LIMIT) {
                    container.health.recordPlaybackFailure(url, error.errorCodeName)
                    advance()
                }
            } else {
                _state.value = _state.value.copy(message = "播放中断，正在自动重连…")
            }
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
        index = 0
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
        index = 0
        renderedSomething = false
        midStreamFailures = 0
        _state.value = _state.value.copy(failed = false, retrying = true, message = null)
        prepareCurrent()
    }

    private fun prepareCurrent() {
        val stream = candidates.getOrNull(index) ?: return
        renderedSomething = false
        midStreamFailures = 0
        _state.value = _state.value.copy(
            attempt = index + 1,
            totalCandidates = candidates.size,
            retrying = index > 0,
            failed = false,
            message = if (index > 0) "镜像 ${index + 1}/${candidates.size} 连接失败，正在切换" else null,
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
            _state.value = _state.value.copy(
                retrying = false,
                failed = true,
                message = "所有镜像都无法播放，可以稍后重试或换一个频道",
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.removeListener(listener)
        player.release()
    }

    private companion object {
        const val MID_STREAM_FAILURE_LIMIT = 3
    }
}
