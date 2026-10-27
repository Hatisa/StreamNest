package com.streamnest.tv.data.repo

import android.content.Context
import com.streamnest.tv.data.health.HealthRepository
import com.streamnest.tv.data.model.Channel
import com.streamnest.tv.data.model.PlaylistSource
import com.streamnest.tv.data.model.SourceKind
import com.streamnest.tv.data.model.SourceStatus
import com.streamnest.tv.data.parser.M3uParser
import com.streamnest.tv.data.parser.XtreamClient
import com.streamnest.tv.data.source.HttpTextFetcher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Aggregates every enabled [PlaylistSource] into one channel list, deduped and
 * decorated with health scores, and caches the result on disk so a cold start
 * shows channels instantly instead of waiting on the network.
 */
class ChannelRepository(
    private val context: Context,
    private val fetcher: HttpTextFetcher,
    private val health: HealthRepository,
    scope: CoroutineScope,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val channelListSerializer = ListSerializer(Channel.serializer())
    private val xtream = XtreamClient(fetcher)

    private val cacheFile = File(context.filesDir, "cache/channels.json")

    private val _raw = MutableStateFlow<List<Channel>>(emptyList())
    private val _statuses = MutableStateFlow<List<SourceStatus>>(emptyList())

    /** Serialises the first disk load: StreamNestApp and AppViewModel both call it. */
    private val loadMutex = Mutex()
    private var loaded = false

    /** Serialises refreshes: the UI and RefreshWorker can fire concurrently. */
    private val refreshMutex = Mutex()

    /**
     * Channels ordered best-first: health score descending, then name. Health is
     * merged in from [HealthRepository] on every change, so the list reorders
     * itself after each background pass without re-fetching anything.
     */
    val channels: StateFlow<List<Channel>> =
        combine(_raw, health.metrics) { list, metrics ->
            val merged = list.map { channel ->
                val record = channel.primaryStream?.url?.let { metrics[it] }
                if (record == null) channel else channel.copy(health = record.score)
            }
            merged.sortedWith(
                compareByDescending<Channel> { it.health }.thenBy { it.name.lowercase() },
            )
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val statuses: StateFlow<List<SourceStatus>> = _statuses.asStateFlow()

    /** In-memory snapshot without the health decoration. */
    fun snapshot(): List<Channel> = _raw.value

    suspend fun ensureLoaded() {
        val firstLoad = loadMutex.withLock {
            if (loaded) false else { loaded = true; true }
        }
        if (!firstLoad) return
        withContext(Dispatchers.IO) {
            if (!cacheFile.exists()) return@withContext
            runCatching {
                val text = cacheFile.readText()
                if (text.isNotBlank()) {
                    _raw.value = json.decodeFromString(channelListSerializer, text)
                }
            }
        }
    }

    /**
     * Fetches every enabled source in parallel (bounded), merges the results,
     * then writes the cache. Returns a per-source status so the UI can show
     * which source failed instead of silently dropping channels.
     *
     * Serialised by [refreshMutex] — a user-triggered refresh racing the periodic
     * worker would otherwise interleave their cache writes.
     */
    suspend fun refresh(sources: List<PlaylistSource>): List<SourceStatus> = refreshMutex.withLock {
        val enabled = sources.filter { it.enabled }
        val now = System.currentTimeMillis()
        val semaphore = Semaphore(MAX_PARALLEL_SOURCES)

        // Fetch in parallel, but merge in a fixed order afterwards. Merging as
        // results arrive would make "which mirror wins" depend on network
        // timing, so the same playlists could yield a different channel list on
        // every refresh.
        val results: List<Result<List<Channel>>> = coroutineScope {
            enabled.map { source ->
                async { semaphore.withPermit { runCatching { loadSource(source) } } }
            }.awaitAll()
        }

        val merged = LinkedHashMap<String, Channel>()
        val statuses = ArrayList<SourceStatus>(enabled.size)

        enabled.forEachIndexed { index, source ->
            results[index]
                .onSuccess { list ->
                    if (list.isEmpty() && source.kind == SourceKind.XTREAM) {
                        statuses += SourceStatus(
                            source.id, false, 0,
                            "没有返回任何频道，请检查服务器地址与账号密码", now,
                        )
                    } else {
                        for (channel in list) mergeInto(merged, channel)
                        statuses += SourceStatus(source.id, true, list.size, null, now)
                    }
                }
                .onFailure { error ->
                    statuses += SourceStatus(
                        source.id, false, 0,
                        error.message ?: error.javaClass.simpleName, now,
                    )
                }
        }

        val list = merged.values.toList()
        if (list.isNotEmpty()) {
            _raw.value = list
            persist(list)
        }
        _statuses.value = statuses
        statuses
    }

    private suspend fun loadSource(source: PlaylistSource): List<Channel> = when (source.kind) {
        SourceKind.REMOTE_M3U -> {
            val url = source.url ?: return emptyList()
            val headers = buildMap {
                if (source.userAgent != null) put("User-Agent", source.userAgent)
                if (source.referrer != null) put("Referer", source.referrer)
            }
            // Parsing a full iptv-org index (tens of thousands of lines) must not
            // run on the caller's thread — that would be the main thread.
            val text = fetcher.getText(url, headers)
            withContext(Dispatchers.Default) { M3uParser.parse(text, source.name) }
        }

        SourceKind.XTREAM -> xtream.loadLiveChannels(source)

        SourceKind.LOCAL_M3U -> withContext(Dispatchers.IO) {
            val path = source.filePath
            if (path.isNullOrBlank()) {
                throw IOException("未设置本地文件路径")
            }
            val file = File(path)
            if (!file.exists()) {
                throw IOException("找不到文件：$path")
            }
            if (!file.canRead()) {
                throw IOException("无权读取：$path（Android 10+ 请把文件放到应用可访问的目录）")
            }
            M3uParser.parse(file.readText(), source.name)
        }
    }

    private fun mergeInto(target: LinkedHashMap<String, Channel>, channel: Channel) {
        val existing = target[channel.key]
        if (existing == null) {
            target[channel.key] = channel
            return
        }
        val knownUrls = existing.streams.map { it.url }.toHashSet()
        val extra = channel.streams.filterNot { it.url in knownUrls }
        if (extra.isNotEmpty()) {
            target[channel.key] = existing.copy(streams = existing.streams + extra)
        }
    }

    private suspend fun persist(channels: List<Channel>) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = cacheFile.parentFile ?: return@withContext
            dir.mkdirs()
            // Write to a temp file and rename over the target: a reader never
            // sees a half-written cache, and the rename is atomic on the same
            // filesystem. java.nio.file is deliberately avoided here — it needs
            // API 26, and this app supports API 24 (where touching it throws
            // NoClassDefFoundError, which no `catch (Exception)` would catch).
            val temp = File(dir, TEMP_CACHE_NAME)
            temp.writeText(json.encodeToString(channelListSerializer, channels))
            if (!temp.renameTo(cacheFile)) {
                // renameTo rarely fails (e.g. the target is locked); copy instead.
                cacheFile.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    companion object {
        /** Public indexes are on GitHub Pages and rate-limit; be a good citizen. */
        private const val MAX_PARALLEL_SOURCES = 3

        /** Safe to fix because refreshes are serialised by [refreshMutex]. */
        private const val TEMP_CACHE_NAME = "channels.json.tmp"
    }
}
