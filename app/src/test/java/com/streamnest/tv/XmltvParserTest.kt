package com.streamnest.tv

import com.streamnest.tv.data.parser.XmltvParser
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvParserTest {

    private val guide = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="CCTV1.cn"><display-name>CCTV-1</display-name></channel>
          <programme start="20261026200000 +0800" stop="20261026210000 +0800" channel="CCTV1.cn">
            <title lang="zh">新闻联播</title>
            <desc>今日要闻</desc>
          </programme>
          <programme start="20261026210000 +0800" stop="20261026220000 +0800" channel="Other.tv">
            <title>Other Show</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun `parses programmes and respects the channel filter`() {
        val programmes = XmltvParser.parse(
            ByteArrayInputStream(guide.toByteArray()),
            channelFilter = setOf("CCTV1.cn"),
        )

        assertEquals(1, programmes.size)
        val programme = programmes.first()
        assertEquals("CCTV1.cn", programme.channelId)
        assertEquals("新闻联播", programme.title)
        assertNotNull(programme.startMs)
    }

    @Test
    fun `parses all programmes when no filter is given`() {
        val programmes = XmltvParser.parse(ByteArrayInputStream(guide.toByteArray()))
        assertEquals(2, programmes.size)
    }

    @Test
    fun `parses timestamps with and without an offset`() {
        assertEquals(
            XmltvParser.parseTimestamp("20260101200000 +0000"),
            XmltvParser.parseTimestamp("20260101200000"),
        )
        assertNotNull(XmltvParser.parseTimestamp("20260101200000 +0800"))
        assertNull(XmltvParser.parseTimestamp("not a date"))
        assertNull(XmltvParser.parseTimestamp(null))
    }

    @Test
    fun `rejects malformed timestamps instead of guessing`() {
        // Out-of-range fields: month 13 / minute 45, and February 30th.
        assertNull(XmltvParser.parseTimestamp("20261345204561"))
        assertNull(XmltvParser.parseTimestamp("20260230200000"))
        // An unexplained trailing character must not be read as UTC.
        assertNull(XmltvParser.parseTimestamp("20261026200000x"))
        // Offset minutes past 59 are not a valid offset.
        assertNull(XmltvParser.parseTimestamp("20261026200000 +0860"))
        // 11 digits is neither a date nor a date plus offset.
        assertNull(XmltvParser.parseTimestamp("2026102620000"))
    }

    @Test
    fun `accepts the offset forms real guides use`() {
        val utc = XmltvParser.parseTimestamp("20261026200000 +0000")!!
        assertEquals(utc, XmltvParser.parseTimestamp("20261026200000Z")!!)
        // No offset at all is treated as UTC.
        assertEquals(utc, XmltvParser.parseTimestamp("20261026200000")!!)
        // A +0800 wall clock is eight hours earlier in UTC.
        assertEquals(utc - 8 * 60 * 60 * 1000L, XmltvParser.parseTimestamp("20261026200000 +0800")!!)
        // The colon form and the missing space are both accepted.
        assertEquals(utc - 8 * 60 * 60 * 1000L, XmltvParser.parseTimestamp("20261026200000 +08:00")!!)
        assertEquals(utc - 8 * 60 * 60 * 1000L, XmltvParser.parseTimestamp("20261026200000+0800")!!)
        // Seconds may be omitted, in which case they are zero.
        assertEquals(utc, XmltvParser.parseTimestamp("202610262000")!!)
    }
}
