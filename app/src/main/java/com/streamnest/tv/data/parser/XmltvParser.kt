package com.streamnest.tv.data.parser

import com.streamnest.tv.data.model.EpgProgramme
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
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

    val OFFSET_FORMATS = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss", "yyyyMMddHHmm")

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

    /** Parses an XMLTV timestamp such as `20261026200000 +0800` (offset optional, UTC assumed). */
    fun parseTimestamp(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (pattern in OFFSET_FORMATS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                // Strict parsing: a lenient formatter silently normalises
                // nonsense such as "20261345204561" into a real date.
                fmt.isLenient = false
                if (!pattern.contains("Z")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(raw)?.time
            } catch (_: Exception) {
                // try the next pattern
            }
        }
        return null
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
