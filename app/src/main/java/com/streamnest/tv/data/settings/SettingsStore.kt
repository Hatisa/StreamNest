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

    // ---- sources -----------------------------------------------------------

    fun sourcesFlow(): Flow<List<PlaylistSource>> = context.dataStore.data.map { prefs ->
        decodeSources(prefs[SOURCES])
    }

    suspend fun sources(): List<PlaylistSource> = sourcesFlow().first()

    /** Adds any built-in source that is missing, without touching user entries. */
    suspend fun ensureBuiltinsPresent() {
        val current = sources()
        val existingIds = current.map { it.id }.toSet()
        val missing = BuiltinSources.defaultSources().filter { it.id !in existingIds }
        if (missing.isNotEmpty()) saveSources(current + missing)
    }

    suspend fun saveSources(sources: List<PlaylistSource>) {
        context.dataStore.edit { it[SOURCES] = json.encodeToString(sourceListSerializer, sources) }
    }

    private fun decodeSources(raw: String?): List<PlaylistSource> {
        if (raw.isNullOrBlank()) return BuiltinSources.defaultSources()
        return runCatching { json.decodeFromString(sourceListSerializer, raw) }
            .getOrElse { BuiltinSources.defaultSources() }
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
