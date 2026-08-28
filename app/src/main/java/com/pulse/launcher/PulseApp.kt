package com.pulse.launcher

import android.app.Application
import com.pulse.launcher.data.AppRepository
import com.pulse.launcher.data.PrefsStore
import com.pulse.launcher.data.RingerController
import com.pulse.launcher.data.UsageStore
import com.pulse.launcher.data.DeviceSignals
import com.pulse.launcher.data.WallpaperRepository

class PulseApp : Application() {
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
        prefsStore = PrefsStore(this)
        usageStore = UsageStore(this)
        appRepository = AppRepository(this)
        ringer = RingerController(this)
        wallpaper = WallpaperRepository(this)
        signals = DeviceSignals(this)
        signals.start()
    }
}
