package com.streamnest.tv.data.model

import com.streamnest.tv.core.Ids
import kotlinx.serialization.Serializable

/** How a stream should be handed to the player. */
enum class MediaType { HLS, DASH, RTSP, RTMP, PROGRESSIVE, PLAYLIST, UNKNOWN }

object MediaTypes {

    /**
     * Best-effort protocol detection from the URL alone. Xtream/`player_api.php`
     * responses carry no protocol field, so this is the only signal available
     * for the majority of public playlists.
     */
    fun detect(rawUrl: String): MediaType {
        val url = rawUrl.trim()
        val lower = url.lowercase()
        if (lower.startsWith("rtsp://")) return MediaType.RTSP
        if (lower.startsWith("rtmp://") || lower.startsWith("rtmps://")) return MediaType.RTMP
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") -> MediaType.HLS
            path.endsWith(".mpd") -> MediaType.DASH
            path.endsWith(".m3u") -> MediaType.PLAYLIST
            path.endsWith(".ts") -> MediaType.PROGRESSIVE
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".flv") ||
                path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".webm") ->
                MediaType.PROGRESSIVE
            else -> MediaType.UNKNOWN
        }
    }
}

@Serializable
data class Stream(
    val url: String,
    val mediaType: MediaType = MediaTypes.detect(url),
    /** e.g. "720p", "HD" — scraped from the channel name or the playlist. */
    val quality: String? = null,
    /** iptv-org style markers: "Not 24/7", "Geo-blocked". */
    val label: String? = null,
    val httpReferrer: String? = null,
    val httpUserAgent: String? = null,
) {
    /** The channel as declared, without any credential redaction. */
    val host: String
        get() = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
}

@Serializable
data class Channel(
    val id: String,
    /** Normalised dedupe key (tvg-id or lowercased name). */
    val key: String,
    val name: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val country: String? = null,
    val language: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    /** Which playlist this channel was first seen in (for display/debug). */
    val sourceName: String? = null,
    val streams: List<Stream> = emptyList(),
    /**
     * Rolling health score in 0..1, produced by the background health pass.
     * Used only for ordering — never to hide a channel outright, so a channel
     * that recovers becomes visible again on the next pass.
     */
    val health: Double = 0.5,
) {
    val primaryStream: Stream? get() = streams.firstOrNull()
    val isPlayable: Boolean get() = streams.isNotEmpty()
}

object ChannelKeys {

    fun key(tvgId: String?, name: String): String {
        val tvg = tvgId?.trim()?.takeIf { it.isNotEmpty() }
        return (tvg ?: name.trim()).lowercase()
    }

    fun idFor(key: String): String = Ids.stableKey(key)
}

/** A user-configurable or built-in origin of channels. */
@Serializable
data class PlaylistSource(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
    /** For LOCAL_M3U: absolute path to a text file in app storage. */
    val filePath: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val enabled: Boolean = true,
    /** Built-in sources can be disabled but not deleted. */
    val builtin: Boolean = false,
    val addedAt: Long = 0L,
)

enum class SourceKind { REMOTE_M3U, XTREAM, LOCAL_M3U }

@Serializable
data class SourceStatus(
    val sourceId: String,
    val ok: Boolean,
    val channelCount: Int = 0,
    val message: String? = null,
    val updatedAt: Long = 0L,
)

enum class HealthStatus { UNKNOWN, OK, SLOW, FAILED }

@Serializable
data class HealthRecord(
    val url: String,
    val status: HealthStatus,
    val latencyMs: Long? = null,
    val score: Double = 0.0,
    val failureKind: String? = null,
    val measuredAt: Long = 0L,
)

enum class EpgStatus { UNKNOWN, FRESH, STALE }

@Serializable
data class EpgProgramme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
) {
    fun isNow(atMs: Long): Boolean = atMs in startMs until stopMs
}

/**
 * Lookups over a programme table. Pure functions so screens can collect the
 * table as state and recompute without any hidden dependency on a repository.
 */
object EpgQuery {

    fun nowPlaying(
        programmes: Map<String, List<EpgProgramme>>,
        tvgId: String?,
        atMs: Long = System.currentTimeMillis(),
    ): EpgProgramme? = tvgId?.let { id ->
        programmes[id]?.firstOrNull { it.isNow(atMs) }
    }

    fun upNext(
        programmes: Map<String, List<EpgProgramme>>,
        tvgId: String?,
        atMs: Long = System.currentTimeMillis(),
    ): EpgProgramme? = tvgId?.let { id ->
        programmes[id]?.firstOrNull { it.startMs > atMs }
    }
}
