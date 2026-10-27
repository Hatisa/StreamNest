package com.streamnest.tv.data.parser

import com.streamnest.tv.data.model.EpgProgramme
import java.io.InputStream
import java.util.GregorianCalendar
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Streaming XMLTV parser.
 *
 * A full `guide.xml` is routinely 20–100 MB, so this uses SAX and (optionally)
 * filters to specific channel ids while parsing — the caller can request only
 * the programmes it is about to display instead of materialising the whole file.
 *
 * External entity resolution is disabled to avoid XXE (the XMLTV DTD is
 * referenced by real-world guides, so a blanket "no doctype" ban is not an
 * option — entity loading is turned off instead).
 */
object XmltvParser {

    fun parse(input: InputStream, channelFilter: Set<String>? = null): List<EpgProgramme> {
        val result = ArrayList<EpgProgramme>(512)
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        disableExternalEntities(factory)
        val parser = factory.newSAXParser()
        parser.parse(input, ProgrammeHandler(result, channelFilter))
        return result
    }

    private fun disableExternalEntities(factory: SAXParserFactory) {
        val features = listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        )
        for (feature in features) {
            try {
                factory.setFeature(feature, false)
            } catch (_: Exception) {
                // Not every SAX implementation supports every feature.
            }
        }
    }

    /**
     * Parses an XMLTV timestamp such as `20261026200000 +0800`.
     *
     * Hand-rolled instead of `SimpleDateFormat` on purpose: a guide is easily
     * hundreds of thousands of programmes × 2 timestamps, and the old version
     * constructed a fresh `SimpleDateFormat` (then up to four of them, one per
     * candidate pattern) for every single timestamp. That allocation churn — not
     * the SAX parse — was what made loading EPG slow.
     *
     * Accepted shapes (per the XMLTV DTD the seconds and the offset are optional):
     *
     *     YYYYMMDDhhmmss ±hhmm
     *     YYYYMMDDhhmmss
     *     YYYYMMDDhhmm    ±hhmm   (seconds default to 0)
     *     YYYYMMDDhhmm
     *
     * A missing offset means UTC. Any field outside the calendar's range makes
     * [GregorianCalendar] throw, so nonsense such as `20261345204561` is rejected
     * rather than silently normalised (the old `isLenient = false` config was
     * there for exactly this reason). Returns null when the value is absent or
     * unusable.
     */
    fun parseTimestamp(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.length < 12) return null

        // ASCII-only digits: Char.isDigit() also accepts non-ASCII digits (e.g.
        // '٤'), which would slip past this guard and then make the `toInt()`
        // calls below throw NumberFormatException out of the parser — which is
        // exactly the "never throws on malformed input" contract this class is
        // built around.
        for (i in 0 until 12) {
            if (raw[i] !in '0'..'9') return null
        }
        val year = raw.substring(0, 4).toInt()
        val month = raw.substring(4, 6).toInt() - 1
        val day = raw.substring(6, 8).toInt()
        val hour = raw.substring(8, 10).toInt()
        val minute = raw.substring(10, 12).toInt()

        var seconds = 0
        var rest = raw.substring(12)
        if (rest.length >= 2 && rest[0] in '0'..'9' && rest[1] in '0'..'9') {
            seconds = rest.substring(0, 2).toInt()
            rest = rest.substring(2)
        }

        // Anything left over must be a timezone designator. Silently treating an
        // unexplained tail as UTC — the obvious alternative — would turn a
        // malformed stamp into a plausible-looking wrong time, so the whole value
        // is rejected instead (matching the previous pattern-based behaviour).
        val offsetMinutes = parseOffsetMinutes(rest) ?: return null

        return try {
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            calendar.isLenient = false
            calendar.clear()
            calendar.set(year, month, day, hour, minute, seconds)
            // The calendar interpreted the fields as UTC wall-clock time; the real
            // instant is that minus the offset (so `+0800` moves it earlier).
            calendar.timeInMillis - offsetMinutes * 60_000L
        } catch (_: RuntimeException) {
            // IllegalArgumentException from set() on an out-of-range field.
            null
        }
    }

    /**
     * `+0800` / `-05:00` / `Z` / empty → minutes east of UTC; null when the text
     * cannot be read as an offset.
     *
     * The leading whitespace in `20261026200000 +0800` is significant: XMLTV
     * separates the offset from the time with a space, so it has to be trimmed
     * before the sign is read or every offset would silently collapse to UTC.
     */
    private fun parseOffsetMinutes(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        val sign = when (text[0]) {
            '+' -> 1
            '-' -> -1
            'Z', 'z' -> return 0
            else -> return null
        }
        val digits = text.substring(1).replace(":", "")
        if (digits.length < 4) return null
        val hours = digits.substring(0, 2).toIntOrNull() ?: return null
        val minutes = digits.substring(2, 4).toIntOrNull() ?: return null
        if (minutes > 59) return null
        return sign * (hours * 60 + minutes)
    }

    private class ProgrammeHandler(
        private val out: MutableList<EpgProgramme>,
        private val filter: Set<String>?,
    ) : DefaultHandler() {

        private var channelId: String? = null
        private var startMs: Long = 0
        private var stopMs: Long = 0
        private val text = StringBuilder()
        private var inTitle = false
        private var inDesc = false
        private var pendingTitle = ""
        private var pendingDescription = ""

        /** True when this whole <programme> is filtered out by channel id. */
        private var dropProgramme = false

        /**
         * True between the start and end of a nested element whose text we do
         * not want (<icon>, <category>, ...). Deliberately distinct from
         * [dropProgramme]: conflating the two made any programme carrying an
         * <icon> — i.e. most real XMLTV guides — get discarded entirely.
         */
        private var ignoreText = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (qName) {
                "programme" -> {
                    channelId = attributes?.getValue("channel")
                    startMs = parseTimestamp(attributes?.getValue("start")) ?: 0L
                    stopMs = parseTimestamp(attributes?.getValue("stop")) ?: 0L
                    dropProgramme = filter != null && channelId != null && channelId !in filter
                    ignoreText = false
                    text.setLength(0)
                }
                "title" -> {
                    inTitle = true
                    text.setLength(0)
                }
                "desc" -> {
                    inDesc = true
                    text.setLength(0)
                }
                "sub-title", "category", "icon", "credits", "actor" -> ignoreText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (dropProgramme || ignoreText) return
            if (inTitle || inDesc) text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (qName) {
                "title" -> {
                    inTitle = false
                    if (!dropProgramme) pendingTitle = text.toString().trim()
                    text.setLength(0)
                }
                "desc" -> {
                    inDesc = false
                    // Collect the synopsis too — it was being parsed and thrown
                    // away, which made the "description" field permanently null.
                    if (!dropProgramme) pendingDescription = text.toString().trim()
                    text.setLength(0)
                }
                "sub-title", "category", "icon", "credits", "actor" -> ignoreText = false
                "programme" -> {
                    val id = channelId
                    if (!dropProgramme && id != null && pendingTitle.isNotEmpty()) {
                        out += EpgProgramme(
                            channelId = id,
                            startMs = startMs,
                            stopMs = stopMs,
                            title = pendingTitle,
                            description = pendingDescription.ifEmpty { null },
                        )
                    }
                    pendingTitle = ""
                    pendingDescription = ""
                    channelId = null
                    dropProgramme = false
                    ignoreText = false
                    text.setLength(0)
                }
            }
        }
    }
}
