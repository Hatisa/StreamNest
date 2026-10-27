package com.streamnest.tv.data.repo

import android.content.Context
import com.streamnest.tv.data.model.EpgProgramme
import com.streamnest.tv.data.parser.XmltvParser
import com.streamnest.tv.data.settings.SettingsStore
import com.streamnest.tv.data.source.HttpTextFetcher
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Optional EPG support.
 *
 * Inert until the user sets a guide URL: XMLTV files are commonly tens of
 * megabytes, and downloading one on first launch for a feature most users have
 * not asked for would be rude. Parsing is streamed (SAX) and filtered to the
 * channels we actually care about, so memory stays bounded regardless of how
 * large the upstream guide is.
 *
 * The programme table is exposed as a StateFlow so screens recompose when it
 * changes; do not read the table through a plain function, or the UI will keep
 * showing an empty guide after a successful refresh.
 */
class EpgRepository(
    private val context: Context,
    private val fetcher: HttpTextFetcher,
    private val settings: SettingsStore,
) {

    private val _programmes = MutableStateFlow<Map<String, List<EpgProgramme>>>(emptyMap())
    val programmes: StateFlow<Map<String, List<EpgProgramme>>> = _programmes.asStateFlow()

    private val _lastUpdated = MutableStateFlow(0L)
    val lastUpdated: StateFlow<Long> = _lastUpdated.asStateFlow()

    private val cacheFile = File(context.filesDir, "cache/epg.txt")

    /**
     * Rehydrates the last downloaded guide so EPG survives a restart.
     *
     * Lines are `channelId \t start \t stop \t title [\t description]`. The
     * description column is optional so that caches written by older builds (four
     * columns) still load.
     */
    suspend fun loadFromCache() = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext
        runCatching {
            val grouped = LinkedHashMap<String, MutableList<EpgProgramme>>()
            cacheFile.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 4) {
                    val start = parts[1].toLongOrNull() ?: return@forEachLine
                    val stop = parts[2].toLongOrNull() ?: return@forEachLine
                    grouped.getOrPut(parts[0]) { mutableListOf() }
                        .add(
                            EpgProgramme(
                                channelId = parts[0],
                                startMs = start,
                                stopMs = stop,
                                title = parts[3],
                                description = parts.getOrNull(4)?.takeIf { it.isNotEmpty() },
                            ),
                        )
                }
            }
            if (grouped.isNotEmpty()) _programmes.value = grouped
        }
    }

    /**
     * Fetches and parses the configured guide for [channelIds]. Returns the
     * number of programme entries kept.
     */
    suspend fun refresh(channelIds: Set<String>): Int {
        if (channelIds.isEmpty()) return 0
        val url = settings.epgUrl() ?: return 0

        return withContext(Dispatchers.IO) {
            runCatching {
                val stream = fetcher.openStream(url)
                val list = stream.use { XmltvParser.parse(it, channelIds) }
                val grouped = list.groupBy { it.channelId }
                    .mapValues { (_, value) -> value.sortedBy { it.startMs } }
                _programmes.value = grouped
                _lastUpdated.value = System.currentTimeMillis()
                writeCache(grouped)
                list.size
            }.getOrDefault(0)
        }
    }

    private fun writeCache(data: Map<String, List<EpgProgramme>>) {
        // A compact TSV rather than JSON: the guide is the largest thing we
        // store and it only ever needs to round-trip through our own parser.
        // The synopsis is written as a fifth column — without it the "current
        // programme" row could only ever show a title after a restart, because
        // the cache was the only thing that survived and it dropped the field.
        runCatching {
            val dir = cacheFile.parentFile ?: return
            dir.mkdirs()
            val temp = File(dir, "epg.txt.tmp")
            temp.bufferedWriter().use { out ->
                for ((channelId, programmes) in data) {
                    for (p in programmes) {
                        out.append(channelId).append('\t')
                        out.append(p.startMs.toString()).append('\t')
                        out.append(p.stopMs.toString()).append('\t')
                        out.append(p.title.sanitiseForTsv())
                        out.append('\t')
                        out.append(p.description.orEmpty().sanitiseForTsv())
                        out.append('\n')
                    }
                }
            }
            // Same temp-then-rename trick as the channel cache: loadFromCache
            // drops malformed lines, so a truncated guide would be lost silently.
            if (!temp.renameTo(cacheFile)) {
                cacheFile.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    /** Tabs and newlines would break the one-record-per-line format. */
    private fun String.sanitiseForTsv(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
