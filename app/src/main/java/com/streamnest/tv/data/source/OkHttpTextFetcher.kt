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

    override suspend fun getText(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            client.newCall(request(url, headers).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpTextFetcher.HttpException(response.code, "HTTP ${response.code} for $url")
                }
                response.body?.string()
                    ?: throw HttpTextFetcher.HttpException(response.code, "Empty body for $url")
            }
        }

    override suspend fun getTextHead(url: String, maxBytes: Int, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            client.newCall(request(url, headers).header("Range", "bytes=0-$maxBytes").build())
                .execute().use { response ->
                    if (!response.isSuccessful) {
                        throw HttpTextFetcher.HttpException(response.code, "HTTP ${response.code} for $url")
                    }
                    val body = response.body
                        ?: throw HttpTextFetcher.HttpException(response.code, "Empty body for $url")
                    val out = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    var total = 0
                    body.byteStream().use { stream ->
                        while (total < maxBytes) {
                            val want = minOf(chunk.size, maxBytes - total)
                            val read = stream.read(chunk, 0, want)
                            if (read <= 0) break
                            out.write(chunk, 0, read)
                            total += read
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
