package com.streamnest.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamnest.tv.StreamNestApp
import com.streamnest.tv.data.model.Channel
import com.streamnest.tv.data.model.EpgProgramme
import com.streamnest.tv.data.model.EpgQuery
import com.streamnest.tv.data.model.HealthRecord
import com.streamnest.tv.data.model.PlaylistSource
import com.streamnest.tv.data.model.SourceKind
import com.streamnest.tv.data.model.SourceStatus
import com.streamnest.tv.data.settings.SettingsStore
import com.streamnest.tv.data.source.BuiltinSources
import com.streamnest.tv.work.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single view-model for the whole app.
 *
 * The app is small enough that splitting per screen would only add plumbing;
 * the state that matters (channels, sources, settings) is shared and lives
 * here. Player state is the one exception and stays screen-local.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as StreamNestApp).container
    private val settings = container.settings
    private val channelRepo = container.channels

    val channels: StateFlow<List<Channel>> = channelRepo.channels
    val statuses: StateFlow<List<SourceStatus>> = channelRepo.statuses
    internal val healthMetrics: StateFlow<Map<String, HealthRecord>> = container.health.metrics

    val sources: StateFlow<List<PlaylistSource>> = settings.sourcesFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val autoRefresh: StateFlow<Boolean> = settings.autoRefreshFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val healthCheck: StateFlow<Boolean> = settings.healthCheckFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val refreshHours: StateFlow<Int> = settings.refreshHoursFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_REFRESH_HOURS)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _showDisclaimer = MutableStateFlow(false)
    val showDisclaimer: StateFlow<Boolean> = _showDisclaimer.asStateFlow()

    private val _epgUrl = MutableStateFlow("")
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    /** Latest EPG URL for internal sequencing; the UI field is [_epgUrl]. */
    private var latestEpgUrl: String = ""

    /** Bumped when the programme table changes, so screens re-read it. */
    val epgProgrammes: StateFlow<Map<String, List<EpgProgramme>>> = container.epg.programmes

    init {
        viewModelScope.launch {
            channelRepo.ensureLoaded()
            container.health.load()
            settings.ensureBuiltinsPresent()
            val firstRun = !settings.onboardingDone()
            latestEpgUrl = settings.epgUrl().orEmpty()
            _epgUrl.value = latestEpgUrl
            container.epg.loadFromCache()
            _showDisclaimer.value = firstRun
            // Out of the box the built-in sources are shipped disabled (they are
            // a convenience, not a claim of content rights). On a genuinely
            // fresh install with nothing enabled that would mean staring at an
            // empty screen, so turn them on once — the user can still switch
            // every one of them off in Settings.
            if (firstRun && settings.sources().none { it.enabled }) {
                enableBuiltinSources()
            }
        }
    }

    fun dismissDisclaimer() {
        _showDisclaimer.value = false
        viewModelScope.launch { settings.setOnboardingDone(true) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---- sources -----------------------------------------------------------

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val list = settings.sources()
                val result = channelRepo.refresh(list)
                val okCount = result.count { it.ok }
                val total = channelRepo.snapshot().size
                _message.value = when {
                    result.isEmpty() -> "没有启用的源，请先在「源管理」里启用或添加一个"
                    okCount == 0 -> "刷新失败：所有源都无法访问，已保留本地缓存"
                    else -> "已更新 $okCount 个源，共 $total 个频道"
                }
                if (settings.healthCheck() && channelRepo.snapshot().isNotEmpty()) {
                    container.health.check(channelRepo.snapshot(), maxTargets = 120)
                }
            } catch (t: Throwable) {
                _message.value = "刷新出错：${t.message ?: t.javaClass.simpleName}"
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun enableBuiltinSources() {
        viewModelScope.launch {
            val current = settings.sources()
            settings.saveSources(current.map { if (it.builtin) it.copy(enabled = true) else it })
            scheduleWorkIfNeeded()
            refresh()
        }
    }

    fun disableAllBuiltinSources() {
        viewModelScope.launch {
            val current = settings.sources()
            settings.saveSources(current.map { if (it.builtin) it.copy(enabled = false) else it })
        }
    }

    fun toggleSource(source: PlaylistSource, enabled: Boolean) {
        updateSource(source.copy(enabled = enabled))
    }

    fun updateSource(source: PlaylistSource) {
        viewModelScope.launch {
            val current = settings.sources()
            settings.saveSources(current.map { if (it.id == source.id) source else it })
            scheduleWorkIfNeeded()
        }
    }

    fun deleteSource(source: PlaylistSource) {
        if (source.builtin) {
            _message.value = "内置源不能删除，可以在列表里关闭它"
            return
        }
        viewModelScope.launch {
            val current = settings.sources()
            settings.saveSources(current.filterNot { it.id == source.id })
            scheduleWorkIfNeeded()
        }
    }

    fun addSource(
        name: String,
        kind: SourceKind,
        url: String?,
        username: String?,
        password: String?,
        filePath: String?,
    ) {
        val displayName = name.trim().ifBlank { defaultNameFor(kind) }
        viewModelScope.launch {
            val source = PlaylistSource(
                id = BuiltinSources.newUserSourceId(),
                name = displayName,
                kind = kind,
                url = url?.trim()?.takeIf { it.isNotEmpty() },
                username = username?.trim()?.takeIf { it.isNotEmpty() },
                password = password?.trim()?.takeIf { it.isNotEmpty() },
                filePath = filePath?.trim()?.takeIf { it.isNotEmpty() },
                builtin = false,
                enabled = true,
                addedAt = System.currentTimeMillis(),
            )
            settings.saveSources(settings.sources() + source)
            scheduleWorkIfNeeded()
            refresh()
        }
    }

    private fun defaultNameFor(kind: SourceKind): String = when (kind) {
        SourceKind.REMOTE_M3U -> "自定义订阅"
        SourceKind.XTREAM -> "Xtream 账号"
        SourceKind.LOCAL_M3U -> "本地 M3U 文件"
    }

    // ---- settings ----------------------------------------------------------

    fun setAutoRefresh(enabled: Boolean) {
        viewModelScope.launch {
            settings.saveAutoRefresh(enabled)
            scheduleWorkIfNeeded()
        }
    }

    fun setHealthCheck(enabled: Boolean) {
        viewModelScope.launch { settings.saveHealthCheck(enabled) }
    }

    fun setRefreshHours(hours: Int) {
        viewModelScope.launch {
            settings.saveRefreshHours(hours)
            scheduleWorkIfNeeded()
        }
    }

    fun setEpgUrl(url: String) {
        viewModelScope.launch { applyEpgUrl(url) }
    }

    /** Saves the URL and immediately loads the guide, in one ordered coroutine. */
    fun setEpgUrlAndRefresh(url: String) {
        viewModelScope.launch {
            applyEpgUrl(url)
            refreshEpg()
        }
    }

    private suspend fun applyEpgUrl(url: String) {
        val trimmed = url.trim()
        settings.saveEpgUrl(trimmed)
        latestEpgUrl = trimmed
        _epgUrl.value = trimmed
    }

    fun refreshEpg() {
        viewModelScope.launch {
            if (latestEpgUrl.isBlank()) {
                _message.value = "请先填写 EPG（XMLTV）地址"
                return@launch
            }
            val ids = channelRepo.snapshot().mapNotNull { it.tvgId }.toSet()
            if (ids.isEmpty()) {
                _message.value = "当前频道没有 EPG 标识（tvg-id），无法匹配节目单"
                return@launch
            }
            val count = container.epg.refresh(ids)
            _message.value = if (count > 0) "已载入 $count 条节目信息" else "节目单为空或暂时无法获取"
        }
    }

    fun nowPlaying(tvgId: String?): EpgProgramme? =
        EpgQuery.nowPlaying(epgProgrammes.value, tvgId)

    fun upNext(tvgId: String?): EpgProgramme? =
        EpgQuery.upNext(epgProgrammes.value, tvgId)

    private fun scheduleWorkIfNeeded() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            if (settings.autoRefresh()) {
                WorkScheduler.schedule(context, settings.refreshHours())
            } else {
                WorkScheduler.cancel(context)
            }
        }
    }
}
