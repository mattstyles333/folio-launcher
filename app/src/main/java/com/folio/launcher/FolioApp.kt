package com.folio.launcher

import android.app.Application
import com.folio.launcher.data.AppRepository
import com.folio.launcher.data.PrefsStore
import com.folio.launcher.data.RingerController
import com.folio.launcher.data.UsageStore
import com.folio.launcher.data.DeviceSignals
import com.folio.launcher.data.SpotifyWidgetBinder
import com.folio.launcher.data.WallpaperRepository

class FolioApp : Application() {
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
    lateinit var spotifyWidget: SpotifyWidgetBinder
        private set

    override fun onCreate() {
        super.onCreate()
        prefsStore = PrefsStore(this)
        usageStore = UsageStore(this)
        appRepository = AppRepository(this)
        ringer = RingerController(this)
        wallpaper = WallpaperRepository(this)
        signals = DeviceSignals(this)
        signals.start()
        spotifyWidget = SpotifyWidgetBinder(this)
    }
}
