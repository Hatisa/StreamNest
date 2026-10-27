package com.streamnest.tv

import com.streamnest.tv.data.model.MediaType
import com.streamnest.tv.data.model.MediaTypes
import com.streamnest.tv.data.parser.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The m3u parser is the single most exercised piece of code in the app: every
 * public playlist in the wild is slightly malformed in its own way. These tests
 * pin the dialects actually seen in the wild.
 */
class M3uParserTest {

    @Test
    fun `parses standard EXTINF attributes`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="CCTV1.cn" tvg-logo="http://x/1.png" group-title="中国",CCTV-1 (1080p)
            http://host/cctv1.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(m3u)

        assertEquals(1, channels.size)
        val channel = channels.first()
        assertEquals("CCTV-1 (1080p)", channel.name)
        assertEquals("中国", channel.group)
        assertEquals("CCTV1.cn", channel.tvgId)
        assertEquals("http://x/1.png", channel.logoUrl)
        assertEquals("1080P", channel.primaryStream?.quality)
        assertEquals(MediaType.HLS, channel.primaryStream?.mediaType)
    }

    @Test
    fun `merges duplicate tvg-id into one channel with several mirrors`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x.tv",X TV
            http://a/x.m3u8
            #EXTINF:-1 tvg-id="x.tv",X TV backup
            http://b/x.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(m3u)

        assertEquals(1, channels.size)
        assertEquals(2, channels.first().streams.size)
    }

    @Test
    fun `applies EXTGRP to following entries`() {
        val m3u = """
            #EXTM3U
            #EXTGRP:News
            #EXTINF:-1,DW English
            http://host/dw.m3u8
            #EXTGRP:Sports
            #EXTINF:-1,Eurosport
            http://host/euro.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(m3u)

        assertEquals("News", channels.first { it.name == "DW English" }.group)
        assertEquals("Sports", channels.first { it.name == "Eurosport" }.group)
    }

    @Test
    fun `parses per-url header suffix`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Header Test
            http://host/x.m3u8|User-Agent=CustomUA/1.0&Referer=http://ref.example/
        """.trimIndent()

        val stream = M3uParser.parse(m3u).first().primaryStream

        assertEquals("http://host/x.m3u8", stream?.url)
        assertEquals("CustomUA/1.0", stream?.httpUserAgent)
        assertEquals("http://ref.example/", stream?.httpReferrer)
    }

    @Test
    fun `parses VLC style player options`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Opts
            #EXTVLCOPT:http-user-agent=UA-FROM-OPT
            #EXTVLCOPT:http-referrer=http://o.example/
            http://host/o.m3u8
        """.trimIndent()

        val stream = M3uParser.parse(m3u).first().primaryStream

        assertEquals("UA-FROM-OPT", stream?.httpUserAgent)
        assertEquals("http://o.example/", stream?.httpReferrer)
    }

    @Test
    fun `handles BOM CRLF blank lines and dumb input without throwing`() {
        val m3u = "\uFEFF#EXTM3U\r\n\r\n  \r\n#EXTINF:-1 tvg-id=bare,No Quotes\r\nhttp://host/bare.m3u8\r\n" +
            "#EXTINF:-1 broken entry with no comma url\n#just a comment\nnot-a-url-but-tolerated"

        val channels = M3uParser.parse(m3u)

        assertEquals("bare", channels.first().tvgId)
        assertEquals("No Quotes", channels.first().name)
        assertTrue(channels.size >= 1)
    }

    @Test
    fun `falls back to the name when tvg-id is missing`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,A Channel Without Id
            http://host/n.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(m3u).first()

        assertNull(channel.tvgId)
        assertEquals("a channel without id", channel.key)
        assertEquals(MediaType.HLS, channel.primaryStream?.mediaType)
    }

    @Test
    fun `detects protocols from URL shape`() {
        assertEquals(MediaType.HLS, MediaTypes.detect("http://h/live.m3u8?token=1"))
        assertEquals(MediaType.DASH, MediaTypes.detect("http://h/live.mpd"))
        assertEquals(MediaType.RTSP, MediaTypes.detect("rtsp://h/live"))
        assertEquals(MediaType.RTMP, MediaTypes.detect("rtmp://h/live"))
        assertEquals(MediaType.PROGRESSIVE, MediaTypes.detect("http://h/movie.mp4"))
        assertEquals(MediaType.UNKNOWN, MediaTypes.detect("http://h/weird"))
    }
}
