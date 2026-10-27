package com.streamnest.tv.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.streamnest.tv.data.model.Stream

/**
 * Playback abstraction.
 *
 * Scope note: this covers player construction and media-source building for a
 * single active stream. That is exactly what the phone build needs, and it is
 * what lets the UI stay ignorant of the engine. Adding a libVLC engine later
 * means extending this interface with a non-Media3 surface — a deliberate,
 * contained change rather than a rewrite.
 */
@androidx.annotation.OptIn(UnstableApi::class)
interface PlayerEngine {
    val id: String
    val displayName: String

    fun createPlayer(context: Context): ExoPlayer

    /** Builds a media source honouring per-stream headers (UA / Referer). */
    fun createMediaSource(context: Context, stream: Stream): MediaSource
}

/** Engines available in this build, best-first. */
object EngineRegistry {
    fun engines(): List<PlayerEngine> = listOf(Media3PlayerEngine)

    fun byId(id: String?): PlayerEngine =
        engines().firstOrNull { it.id == id } ?: Media3PlayerEngine
}
