package com.streamnest.tv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamnest.tv.data.model.HealthRecord
import com.streamnest.tv.data.model.PlaylistSource
import com.streamnest.tv.data.source.BuiltinSources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "streamnest")

/**
 * Durable app state. Deliberately excludes the channel cache (thousands of
 * entries) — that lives in a file, see ChannelRepository.
 */
class SettingsStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val sourceListSerializer = ListSerializer(PlaylistSource.serializer())
    private val metricListSerializer = ListSerializer(HealthRecord.serializer())

    private val _sourcesCorrupt = MutableStateFlow(false)

    /**
     * True when the stored source JSON exists but could not be decoded.
     *
     * Exposed so the UI can say so out loud. The alternative — quietly showing
     * the built-ins — reads exactly like "the user never added anything", which
     * is how a corrupt preference file turns into a silent data loss report.
     */
    val sourcesCorrupt: StateFlow<Boolean> = _sourcesCorrupt.asStateFlow()

    // ---- sources -----------------------------------------------------------

    fun sourcesFlow(): Flow<List<PlaylistSource>> = context.dataStore.data.map { prefs ->
        decodeSources(prefs[SOURCES])
    }

    suspend fun sources(): List<PlaylistSource> = sourcesFlow().first()

    /**
     * Adds any built-in source that is missing, without touching user entries.
     *
     * Deliberately a no-op when the stored JSON is corrupt: this runs
     * automatically at startup, so persisting here would overwrite the user's
     * real sources with the built-in list without anyone asking. Leaving the bad
     * value untouched keeps it recoverable.
     */
    suspend fun ensureBuiltinsPresent() {
        val current = sources()
        if (_sourcesCorrupt.value) return
        val existingIds = current.map { it.id }.toSet()
        val missing = BuiltinSources.defaultSources().filter { it.id !in existingIds }
        if (missing.isNotEmpty()) saveSources(current + missing)
    }

    suspend fun saveSources(sources: List<PlaylistSource>) {
        context.dataStore.edit { it[SOURCES] = json.encodeToString(sourceListSerializer, sources) }
    }

    /**
     * Three outcomes, and conflating them is what made this lossy:
     *  * nothing stored yet (fresh install) -> the built-ins; they are the
     *    intended default and a caller may persist them;
     *  * valid JSON -> the stored list;
     *  * unreadable JSON -> the built-ins as a *display* fallback, with
     *    [sourcesCorrupt] raised so [ensureBuiltinsPresent] will not write them
     *    over the user's data and the UI can tell the user what happened.
     *
     * Note the contrast with [metrics], which also falls back on a decode error:
     * losing the health table costs a re-probe, losing the source list costs the
     * user their subscriptions.
     */
    private fun decodeSources(raw: String?): List<PlaylistSource> {
        if (raw.isNullOrBlank()) {
            _sourcesCorrupt.value = false
            return BuiltinSources.defaultSources()
        }
        return runCatching { json.decodeFromString(sourceListSerializer, raw) }
            .onSuccess { _sourcesCorrupt.value = false }
            .getOrElse {
                _sourcesCorrupt.value = true
                BuiltinSources.defaultSources()
            }
    }

    // ---- health metrics ----------------------------------------------------

    suspend fun metrics(): List<HealthRecord> {
        val raw = context.dataStore.data.first()[METRICS]
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(metricListSerializer, raw) }.getOrDefault(emptyList())
    }

    suspend fun saveMetrics(metrics: List<HealthRecord>) {
        context.dataStore.edit { it[METRICS] = json.encodeToString(metricListSerializer, metrics) }
    }

    // ---- settings ----------------------------------------------------------

    suspend fun epgUrl(): String? = context.dataStore.data.first()[EPG_URL]?.takeIf { it.isNotBlank() }

    suspend fun saveEpgUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(EPG_URL) else prefs[EPG_URL] = url.trim()
        }
    }

    fun autoRefreshFlow(): Flow<Boolean> = context.dataStore.data.map { it[AUTO_REFRESH] ?: true }

    suspend fun autoRefresh(): Boolean = autoRefreshFlow().first()

    suspend fun saveAutoRefresh(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_REFRESH] = enabled }
    }

    fun healthCheckFlow(): Flow<Boolean> = context.dataStore.data.map { it[HEALTH_CHECK] ?: true }

    suspend fun healthCheck(): Boolean = healthCheckFlow().first()

    suspend fun saveHealthCheck(enabled: Boolean) {
        context.dataStore.edit { it[HEALTH_CHECK] = enabled }
    }

    fun refreshHoursFlow(): Flow<Int> = context.dataStore.data.map { it[REFRESH_HOURS] ?: DEFAULT_REFRESH_HOURS }

    suspend fun refreshHours(): Int = refreshHoursFlow().first()

    suspend fun saveRefreshHours(hours: Int) {
        context.dataStore.edit { it[REFRESH_HOURS] = hours.coerceIn(1, 168) }
    }

    suspend fun onboardingDone(): Boolean = context.dataStore.data.first()[ONBOARDED] ?: false

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[ONBOARDED] = done }
    }

    companion object {
        const val DEFAULT_REFRESH_HOURS = 12
        private val SOURCES = stringPreferencesKey("sources_json")
        private val METRICS = stringPreferencesKey("health_metrics_json")
        private val EPG_URL = stringPreferencesKey("epg_url")
        private val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        private val HEALTH_CHECK = booleanPreferencesKey("health_check")
        private val REFRESH_HOURS = intPreferencesKey("refresh_hours")
        private val ONBOARDED = booleanPreferencesKey("onboarded")
    }
}
