package com.streamnest.tv.data.parser

import com.streamnest.tv.data.model.Channel
import com.streamnest.tv.data.model.ChannelKeys
import com.streamnest.tv.data.model.MediaTypes
import com.streamnest.tv.data.model.Stream

/**
 * Streaming M3U/M3U8 playlist parser for the extended `#EXTINF` dialect used by
 * basically every IPTV provider (Kodi, VLC, TiviMate, Xtream dumps).
 *
 * Handled, in order of how often they show up in real public playlists:
 *  - `#EXTINF:-1 tvg-id=".." tvg-logo=".." group-title="..",Name`
 *  - `#EXTGRP:Group` (Kodi style, applies to following entries)
 *  - `#EXTVLCOPT:http-user-agent=..` / `http-referrer=..`
 *  - `#KODIPROP:...=...`
 *  - `url|User-Agent=..&Referer=..` header suffix
 *  - CRLF, BOM, unquoted attributes, missing attributes, blank lines
 *
 * The parser never throws on malformed input: a broken line is skipped and the
 * rest of the playlist still loads. That matters because public playlists are
 * patched by hundreds of people and routinely contain junk.
 */
object M3uParser {

    // Escaped (non-raw) strings on purpose: a raw string ending in a quote
    // ("""..."""") relies on a subtle lexer rule; the escaped form is unambiguous.
    private val ATTR_QUOTED = Regex("([A-Za-z0-9_.\\-]+)\\s*=\\s*\"([^\"]*)\"")
    private val ATTR_BARE = Regex("([A-Za-z0-9_.\\-]+)\\s*=\\s*([^\\s\",]+)")
    private val QUALITY = Regex(
        "[(\\[](\\d{3,4}[pi]|4K|8K|FHD|UHD|HD|SD|HEVC)[)\\]]",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, sourceName: String? = null): List<Channel> {
        val merged = LinkedHashMap<String, MutableChannel>()
        var pending: Pending? = null
        var carriedGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF").removeSuffix("\r")
            if (line.isEmpty()) continue
            val upper = line.uppercase()
            when {
                upper.startsWith("#EXTINF") -> pending = parseExtInf(line)

                upper.startsWith("#EXTGRP:") -> {
                    carriedGroup = line.substringAfter(':').trim().ifBlank { null }
                    pending?.let { p -> if (p.group.isNullOrBlank()) p.group = carriedGroup }
                }

                upper.startsWith("#EXTVLCOPT:") || upper.startsWith("#KODIPROP:") ->
                    pending?.withOption(line.substringAfter(':'))

                line.startsWith("#") -> Unit // #EXTM3U, #EXT-X-*, comments

                else -> {
                    val p = pending
                    if (p != null && line.isNotBlank()) {
                        val group = p.group ?: carriedGroup
                        val key = ChannelKeys.key(p.attrs["tvg-id"], p.name)
                        val entry = merged.getOrPut(key) {
                            MutableChannel(
                                key = key,
                                name = p.name,
                                logo = p.logo,
                                group = group,
                                tvgId = p.attrs["tvg-id"],
                                tvgName = p.attrs["tvg-name"],
                                country = p.attrs["tvg-country"],
                                language = p.attrs["tvg-language"],
                                sourceName = sourceName,
                            )
                        }
                        entry.streams += buildStream(line, p)
                    }
                    pending = null
                }
            }
        }

        return merged.values.map { it.toChannel() }
    }

    private fun parseExtInf(line: String): Pending? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val body = line.substring(colon + 1)
        val comma = firstUnquotedComma(body)
        val attrPart = if (comma >= 0) body.substring(0, comma) else body
        val namePart = if (comma >= 0) body.substring(comma + 1).trim() else ""

        val attrs = LinkedHashMap<String, String>()
        for (m in ATTR_QUOTED.findAll(attrPart)) {
            attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        for (m in ATTR_BARE.findAll(attrPart)) {
            attrs.putIfAbsent(m.groupValues[1].lowercase(), m.groupValues[2])
        }

        val displayName = namePart
            .ifBlank { attrs["tvg-name"].orEmpty() }
            .ifBlank { "Unknown" }

        return Pending(displayName, attrs)
    }

    private fun buildStream(rawUrlLine: String, p: Pending): Stream {
        var url = rawUrlLine
        var userAgent = p.userAgent
        var referrer = p.referrer

        val pipe = rawUrlLine.indexOf('|')
        if (pipe >= 0) {
            url = rawUrlLine.substring(0, pipe)
            for (pair in rawUrlLine.substring(pipe + 1).split('&')) {
                val k = pair.substringBefore('=', "").trim().lowercase()
                val v = pair.substringAfter('=', "").trim()
                if (v.isEmpty()) continue
                when (k) {
                    "user-agent" -> userAgent = v
                    "referer", "referrer" -> referrer = v
                }
            }
        }

        val cleanUrl = url.trim()
        return Stream(
            url = cleanUrl,
            mediaType = MediaTypes.detect(cleanUrl),
            quality = p.attr("quality") ?: qualityFromName(p.name),
            label = p.attr("tvg-label") ?: p.attr("label"),
            httpReferrer = referrer,
            httpUserAgent = userAgent,
        )
    }

    private fun qualityFromName(name: String): String? =
        QUALITY.find(name)?.groupValues?.getOrNull(1)?.uppercase()

    private fun firstUnquotedComma(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    private class Pending(
        val name: String,
        val attrs: MutableMap<String, String>,
    ) {
        var group: String? = attr("group-title")
        var logo: String? = attr("tvg-logo")
        var userAgent: String? = attr("http-user-agent")
        var referrer: String? = attr("http-referrer")

        fun attr(key: String): String? = attrs[key]?.trim()?.takeIf { it.isNotEmpty() }

        fun withOption(option: String) {
            val k = option.substringBefore('=', "").trim().lowercase()
            val v = option.substringAfter('=', "").trim().takeIf { it.isNotEmpty() } ?: return
            when (k) {
                "http-user-agent", "user-agent" -> userAgent = v
                "http-referrer", "referrer", "referer" -> referrer = v
            }
        }
    }

    private class MutableChannel(
        val key: String,
        val name: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?,
        val tvgName: String?,
        val country: String?,
        val language: String?,
        val sourceName: String?,
    ) {
        val streams = mutableListOf<Stream>()

        fun toChannel() = Channel(
            id = ChannelKeys.idFor(key),
            key = key,
            name = name,
            logoUrl = logo,
            group = group,
            country = country,
            language = language,
            tvgId = tvgId,
            tvgName = tvgName,
            sourceName = sourceName,
            streams = streams.distinctBy { it.url },
        )
    }
}
