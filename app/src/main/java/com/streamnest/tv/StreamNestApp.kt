package com.streamnest.tv

import android.app.Application
import android.content.Context
import com.streamnest.tv.data.health.HealthRepository
import com.streamnest.tv.data.health.HttpStreamProbe
import com.streamnest.tv.data.health.StreamProbe
import com.streamnest.tv.data.repo.ChannelRepository
import com.streamnest.tv.data.repo.EpgRepository
import com.streamnest.tv.data.settings.SettingsStore
import com.streamnest.tv.data.source.HttpTextFetcher
import com.streamnest.tv.data.source.OkHttpTextFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container.
 *
 * A DI framework would add a build-time annotation processor for a graph this
 * small; plain constructor wiring keeps the build fast and the data flow
 * obvious. Everything below is an interface, so tests can swap in fakes.
 */
class AppContainer(context: Context) {

    /** Outlives any screen; used only for fire-and-forget persistence writes. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val fetcher: HttpTextFetcher = OkHttpTextFetcher()
    val probe: StreamProbe = HttpStreamProbe()

    val settings = SettingsStore(context)
    val health = HealthRepository(settings, probe, scope)
    val channels = ChannelRepository(context, fetcher, health, scope)
    val epg = EpgRepository(context, fetcher, settings)
}

class StreamNestApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Warm the on-disk channel cache immediately so the first frame has data.
        container.scope.launch { container.channels.ensureLoaded() }
    }
}
