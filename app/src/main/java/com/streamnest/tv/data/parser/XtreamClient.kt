package com.streamnest.tv.data.parser

import com.streamnest.tv.data.model.Channel
import com.streamnest.tv.data.model.ChannelKeys
import com.streamnest.tv.data.model.MediaType
import com.streamnest.tv.data.model.PlaylistSource
import com.streamnest.tv.data.model.Stream
import com.streamnest.tv.data.source.HttpHeaders
import com.streamnest.tv.data.source.HttpTextFetcher
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Xtream Codes `player_api.php` client.
 *
 * Deliberately tolerant: the API is implemented by dozens of unrelated panel
 * forks, so field types drift (ids arrive as string *or* number, arrays are
 * sometimes wrapped in an object, errors come back with HTTP 200). Everything
 * is parsed through [JsonElement] rather than typed models for that reason.
 */
class XtreamClient(private val fetcher: HttpTextFetcher) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun loadLiveChannels(source: PlaylistSource): List<Channel> {
        val base = source.url?.trim()?.trimEnd('/').orEmpty()
        require(base.isNotEmpty()) { "Xtream source is missing a server URL" }
        val user = source.username.orEmpty()
        val pass = source.password.orEmpty()

        val categories = runCatching { fetchCategories(base, user, pass) }
            .getOrDefault(emptyMap())
        val live = fetchArray("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_live_streams")
            ?: return emptyList()

        val channels = ArrayList<Channel>(live.size)
        for (element in live) {
            val obj = element as? JsonObject ?: continue
            val name = obj.str("name") ?: continue
            val streamId = obj.str("stream_id") ?: continue
            val categoryId = obj.str("category_id")
            val logo = obj.str("stream_icon")
            val epgId = obj.str("epg_channel_id")

            val direct = obj.str("direct_source")?.takeIf { it.startsWith("http") }
            val uri = direct ?: buildLiveUrl(base, user, pass, streamId)

            val tvgId = epgId?.takeIf { it.isNotBlank() }
            val key = ChannelKeys.key(tvgId, name)

            channels += Channel(
                id = ChannelKeys.idFor(key),
                key = key,
                name = name,
                logoUrl = logo,
                group = categoryId?.let { categories[it] },
                tvgId = tvgId,
                tvgName = name,
                sourceName = source.name,
                streams = listOf(
                    Stream(
                        url = uri,
                        mediaType = MediaType.HLS,
                        httpUserAgent = source.userAgent ?: HttpHeaders.DEFAULT_USER_AGENT,
                        httpReferrer = source.referrer,
                    ),
                ),
            )
        }
        return channels
    }

    private suspend fun fetchCategories(base: String, user: String, pass: String): Map<String, String> {
        val array = fetchArray("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_live_categories")
            ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val id = obj.str("category_id") ?: continue
            out[id] = obj.str("category_name") ?: id
        }
        return out
    }

    private suspend fun fetchArray(url: String): JsonArray? {
        val body = fetcher.getText(url, mapOf("Accept" to "application/json, */*"))
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("[")) return null
        return runCatching { json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
    }

    private fun buildLiveUrl(base: String, user: String, pass: String, streamId: String): String =
        "$base/live/${enc(user)}/${enc(pass)}/$streamId.m3u8"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.str(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }
}
