package com.streamnest.tv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.streamnest.tv.data.model.MediaType
import com.streamnest.tv.data.model.Stream
import com.streamnest.tv.data.source.HttpHeaders

/**
 * ExoPlayer/Media3 engine — the only engine in v1.
 *
 * ExoPlayer is chosen as the base engine because it is Apache-2.0 (so the whole
 * project can stay MIT), integrates with Android's media session and
 * picture-in-picture, and handles HLS/DASH natively. Streams it cannot open are
 * reported as player errors and the app falls through to the channel's other
 * mirrors — see PlayerViewModel.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object Media3PlayerEngine : PlayerEngine {

    override val id: String = "media3"
    override val displayName: String = "Media3 / ExoPlayer"

    override fun createPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()

    override fun createMediaSource(context: Context, stream: Stream): MediaSource {
        val userAgent = stream.httpUserAgent ?: HttpHeaders.DEFAULT_USER_AGENT
        val headerMap = LinkedHashMap<String, String>().apply {
            put("User-Agent", userAgent)
            stream.httpReferrer?.let { put("Referer", it) }
        }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headerMap)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            // Public playlists routinely redirect http -> https (and back).
            .setAllowCrossProtocolRedirects(true)

        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply {
                when (stream.mediaType) {
                    MediaType.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    MediaType.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    else -> Unit
                }
            }
            .build()

        return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item)
    }
}
