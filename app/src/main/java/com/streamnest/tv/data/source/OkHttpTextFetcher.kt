package com.streamnest.tv.data.source

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** OkHttp-backed [HttpTextFetcher]. */
class OkHttpTextFetcher(
    private val client: OkHttpClient = defaultClient(),
) : HttpTextFetcher {

    override suspend fun getText(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
    ): String = withContext(Dispatchers.IO) {
        client.newCall(request(url, headers).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpTextFetcher.HttpException(response.code, "HTTP ${response.code} for $url")
            }
            val body = response.body
                ?: throw HttpTextFetcher.HttpException(response.code, "Empty body for $url")

            // Fail fast on a declared oversized body. Content-Length is advisory
            // (absent on chunked responses, sometimes wrong), so the read below
            // still enforces the cap; this only avoids downloading the obvious
            // cases at all.
            val declared = body.contentLength()
            if (declared > maxBytes) {
                throw HttpTextFetcher.ResponseTooLargeException(maxBytes, url)
            }

            // Read incrementally instead of body.string(): the whole point is to
            // stop before the allocation, not after. The initial capacity is
            // clamped so a bogus Content-Length cannot itself allocate hugely.
            val initial = if (declared in 1..maxBytes.toLong()) declared.toInt() else 64 * 1024
            val out = ByteArrayOutputStream(initial)
            val chunk = ByteArray(16 * 1024)
            var total = 0
            body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw HttpTextFetcher.ResponseTooLargeException(maxBytes, url)
                    }
                    out.write(chunk, 0, read)
                }
            }
            out.toString("UTF-8")
        }
    }

    override suspend fun openStream(url: String, headers: Map<String, String>): InputStream =
        withContext(Dispatchers.IO) {
            val response = client.newCall(request(url, headers).build()).execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw HttpTextFetcher.HttpException(code, "HTTP $code for $url")
            }
            val body = response.body
            if (body == null) {
                response.close()
                throw HttpTextFetcher.HttpException(response.code, "Empty body for $url")
            }
            // Closing the stream must also release the response.
            object : FilterInputStream(body.byteStream()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        response.close()
                    }
                }
            }
        }

    private fun request(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpHeaders.DEFAULT_USER_AGENT)
            .header("Accept", headers["Accept"] ?: HttpHeaders.ACCEPT_M3U)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
