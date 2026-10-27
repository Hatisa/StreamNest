package com.streamnest.tv.data.source

import com.streamnest.tv.data.model.PlaylistSource
import com.streamnest.tv.data.model.SourceKind
import com.streamnest.tv.core.Ids

/**
 * Built-in channel catalogues.
 *
 * All entries point at **iptv-org** published playlists, which are released
 * into the public domain (The Unlicense). iptv-org is a link index, not a
 * broadcaster: it stores no video and grants no rights to any channel — see
 * DISCLAIMER.md in this repository.
 *
 * These exist purely as a zero-configuration starting point. Every one of them
 * is disabled by a single switch in Settings, and the app remains fully usable
 * with only user-supplied sources (local M3U / Xtream / remote subscription).
 */
object BuiltinSources {

    private const val IPTV_ORG = "https://iptv-org.github.io/iptv"

    fun defaultSources(now: Long = System.currentTimeMillis()): List<PlaylistSource> = listOf(
        remote("iptv-org:index", "iptv-org · All channels", "$IPTV_ORG/index.m3u", now),
        remote("iptv-org:news", "iptv-org · News", "$IPTV_ORG/categories/news.m3u", now),
        remote("iptv-org:movies", "iptv-org · Movies", "$IPTV_ORG/categories/movies.m3u", now),
        remote("iptv-org:sports", "iptv-org · Sports", "$IPTV_ORG/categories/sports.m3u", now),
        remote("iptv-org:cn", "iptv-org · China", "$IPTV_ORG/countries/cn.m3u", now),
        remote("iptv-org:us", "iptv-org · United States", "$IPTV_ORG/countries/us.m3u", now),
    )

    fun remote(id: String, name: String, url: String, now: Long = System.currentTimeMillis()) =
        PlaylistSource(
            id = id,
            name = name,
            kind = SourceKind.REMOTE_M3U,
            url = url,
            builtin = true,
            enabled = false,
            addedAt = now,
        )

    fun newUserSourceId(): String = "user-" + Ids.stableKey(System.nanoTime().toString())

    /**
     * iptv-org publishes community EPG bundles; this is a convenience default
     * that the user can replace with their own XMLTV URL.
     */
    const val DEFAULT_EPG_URL = "https://iptv-org.github.io/epg/guides/guide.cn.xml"
}
