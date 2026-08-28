package com.folio.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()

    private var reloadJob: Job? = null
    private val ownPackage = context.packageName

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = reload()

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = reload()
    }

    fun start() {
        runCatching {
            launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        }
        reload()
    }

    fun stop() {
        runCatching { launcherApps.unregisterCallback(callback) }
    }

    fun reload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(40)
            _apps.value = load()
        }
    }

    private fun load(): List<LaunchableApp> {
        return runCatching {
            val user = Process.myUserHandle()
            val infos = launcherApps.getActivityList(null, user)
            val density = context.resources.displayMetrics.densityDpi
            val size = (56 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
            infos.mapNotNull { info ->
                val cn = info.componentName
                if (cn.packageName == ownPackage) return@mapNotNull null
                val label = info.label?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val icon = runCatching {
                    info.getBadgedIcon(density).toBitmap(size, size).asImageBitmap()
                }.getOrNull() ?: return@mapNotNull null
                LaunchableApp(
                    packageName = cn.packageName,
                    activityName = cn.className,
                    user = info.user,
                    label = label,
                    icon = icon,
                )
            }.distinctBy { it.key }
                .sortedBy { it.label.lowercase() }
        }.getOrElse { emptyList() }
    }

    fun launch(app: LaunchableApp, bounds: Rect? = null): Boolean {
        val component = ComponentName(app.packageName, app.activityName)
        val started = runCatching {
            launcherApps.startMainActivity(component, app.user, bounds, null)
            true
        }.getOrDefault(false)
        if (started) return true
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return false
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun find(packageName: String, activityName: String?, apps: List<LaunchableApp> = _apps.value): LaunchableApp? {
        if (activityName != null) {
            apps.find { it.packageName == packageName && it.activityName == activityName }?.let { return it }
        }
        return apps.find { it.packageName == packageName }
    }
}
