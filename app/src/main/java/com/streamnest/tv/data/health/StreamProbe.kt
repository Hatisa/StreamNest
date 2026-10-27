package com.streamnest.tv.data.health

import com.streamnest.tv.data.source.HttpHeaders
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Probes a stream URL to decide whether it is worth showing first. */
interface StreamProbe {
    suspend fun probe(url: String, userAgent: String? = null, referrer: String? = null): ProbeResult
}

/**
 * Cheap reachability probe: a 2 KB ranged GET with a short timeout.
 *
 * It answers "does this look alive right now" — not "will it sustain 1080p".
 * That is the right trade-off for scanning thousands of channels on a phone:
 * a full bandwidth test of every stream on a phone network would be both slow
 * and rude, and would get the user rate-limited by the origins.
 */
class HttpStreamProbe(
    private val client: OkHttpClient = defaultClient(),
) : StreamProbe {

    override suspend fun probe(url: String, userAgent: String?, referrer: String?): ProbeResult =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent ?: HttpHeaders.DEFAULT_USER_AGENT)
                    .header("Range", "bytes=0-2047")
                    .header("Connection", "close")
                referrer?.let { builder.header("Referer", it) }

                client.newCall(builder.build()).execute().use { response ->
                    val latency = (System.nanoTime() - started) / 1_000_000
                    if (!response.isSuccessful) {
                        return@withContext ProbeResult(false, latency, "http_${response.code}")
                    }
                    val body = response.body
                    val head = ByteArray(2048)
                    val read = body?.byteStream()?.let { stream ->
                        try {
                            stream.read(head)
                        } catch (_: IOException) {
                            -1
                        }
                    } ?: -1
                    val text = if (read > 0) String(head, 0, read, Charsets.ISO_8859_1) else ""
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val looksHls = url.substringBefore('?').lowercase().endsWith(".m3u8") ||
                        contentType.contains("mpegurl")

                    when {
                        looksHls && !text.trimStart().startsWith("#EXTM3U") ->
                            ProbeResult(false, latency, "not_hls")
                        !looksHls && read <= 0 ->
                            ProbeResult(false, latency, "empty_body")
                        else ->
                            ProbeResult(true, latency, null)
                    }
                }
            } catch (e: IOException) {
                val latency = (System.nanoTime() - started) / 1_000_000
                ProbeResult(false, latency, "io_error")
            } catch (e: IllegalArgumentException) {
                val latency = (System.nanoTime() - started) / 1_000_000
                ProbeResult(false, latency, "bad_url")
            }
        }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }
}
