package com.folio.launcher

import android.app.Application
import com.folio.hermes.telemetry.DiagnosticsEndpoint
import com.folio.hermes.telemetry.Telemetry
import com.folio.launcher.data.AppRepository
import com.folio.launcher.data.PrefsStore
import com.folio.launcher.data.RingerController
import com.folio.launcher.data.UsageStore
import com.folio.launcher.data.DeviceSignals
import com.folio.launcher.data.WallpaperRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class FolioApp : Application() {
    /** The endpoint in force (telemetry thread only: it may read DataStore). */
    private fun diagnosticsEndpoint(): String? =
        diagnosticsEndpoint(BuildConfig.DIAGNOSTICS_URL) { runBlocking { prefsStore.data.first().diagnostics } }

    lateinit var prefsStore: PrefsStore
        private set
    lateinit var usageStore: UsageStore
        private set
    lateinit var appRepository: AppRepository
        private set
    lateinit var ringer: RingerController
        private set
    lateinit var wallpaper: WallpaperRepository
        private set
    lateinit var signals: DeviceSignals
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere below is caught. The endpoint is asked on the telemetry
        // thread: none in debug/local builds; in release, off when Settings › Diagnostics is off.
        Telemetry.install(this, "folio", BuildConfig.VERSION_NAME) { diagnosticsEndpoint() }
        prefsStore = PrefsStore(this)
        usageStore = UsageStore(this)
        appRepository = AppRepository(this)
        ringer = RingerController(this)
        wallpaper = WallpaperRepository(this)
        signals = DeviceSignals(this)
        signals.start()
    }

    companion object {
        /**
         * Folio's diagnostics endpoint: the build's default ([builtIn], from release CI; "" in
         * local and debug builds, so off) unless Settings › Diagnostics is off. No override in
         * Folio; [enabled] is read only when there is a URL.
         */
        fun diagnosticsEndpoint(builtIn: String, enabled: () -> Boolean): String? {
            if (builtIn.isBlank()) return null
            return DiagnosticsEndpoint.effective(enabled = enabled(), override = null, buildDefault = builtIn)
        }
    }
}
