package com.streamnest.tv.data.source

import java.io.InputStream

/**
 * Upper bound on a response this app is willing to buffer as a single String.
 *
 * Playlists and `player_api.php` bodies are normally a few megabytes; the
 * generous cap only exists so that a misconfigured or hostile source cannot make
 * the app allocate until it dies. A truncated/huge body is rejected outright
 * rather than parsed partially, because a half-parsed playlist would otherwise
 * look like a legitimately short one.
 */
const val MAX_TEXT_RESPONSE_BYTES = 32 * 1024 * 1024

/**
 * Minimal HTTP client used by the parsers and the health prober.
 *
 * Kept as an interface so all parsing/aggregation/scoring can be unit-tested on
 * the plain JVM with a fake implementation, without Android or the network.
 */
interface HttpTextFetcher {

    /**
     * Fetches the body as text, refusing to buffer more than [maxBytes].
     *
     * @throws ResponseTooLargeException when the source exceeds [maxBytes].
     */
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_TEXT_RESPONSE_BYTES,
    ): String

    /**
     * Opens a streaming body. The caller owns the returned stream and MUST close
     * it; closing also releases the underlying HTTP response. Needed for XMLTV
     * guides, which are far too large to buffer as a String.
     */
    suspend fun openStream(url: String, headers: Map<String, String> = emptyMap()): InputStream

    class HttpException(val statusCode: Int, message: String) : Exception(message)

    /** Guards against unbounded buffering — see [MAX_TEXT_RESPONSE_BYTES]. */
    class ResponseTooLargeException(limitBytes: Int, url: String) :
        Exception("响应超过 ${limitBytes / (1024 * 1024)} MB 上限，已中止下载：$url")
}

object HttpHeaders {
    /**
     * A mainstream desktop UA. Many public HLS origins reject unknown clients
     * (and a few reject OkHttp's default UA outright), so a browser-like UA is
     * the pragmatic default.
     */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val ACCEPT_M3U =
        "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, */*"
}
