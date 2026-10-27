package com.streamnest.tv.data.source

import java.io.InputStream

/**
 * Minimal HTTP client used by the parsers and the health prober.
 *
 * Kept as an interface so all parsing/aggregation/scoring can be unit-tested on
 * the plain JVM with a fake implementation, without Android or the network.
 */
interface HttpTextFetcher {

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String

    /** Fetches at most [maxBytes] bytes — used to probe or sniff large files. */
    suspend fun getTextHead(
        url: String,
        maxBytes: Int = 64 * 1024,
        headers: Map<String, String> = emptyMap(),
    ): String

    /**
     * Opens a streaming body. The caller owns the returned stream and MUST close
     * it; closing also releases the underlying HTTP response. Needed for XMLTV
     * guides, which are far too large to buffer as a String.
     */
    suspend fun openStream(url: String, headers: Map<String, String> = emptyMap()): InputStream

    class HttpException(val statusCode: Int, message: String) : Exception(message)
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
