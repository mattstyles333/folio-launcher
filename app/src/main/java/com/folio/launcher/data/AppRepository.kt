package com.folio.launcher.data

import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.graphics.Rect
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()

    private var reloadJob: Job? = null
    private val ownPackage = context.packageName

    /** Rendered icons by component + user; only the packages that change are redrawn. */
    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    @Volatile
    private var iconPx = 0

    /** Packages changed since the last load began; that load's icons for them may be stale. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** One load at a time, so an older load can't finish last and publish a stale list. */
    private val loading = Mutex()

    /** Language and density of the last load: labels are read in one, icons drawn at the other. */
    private var loadedFor: Pair<LocaleList, Int>? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload(packageName)
        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = reload(*packageNames)

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = reload(*packageNames)

        // An app timer or Focus mode pausing an app, or ending: only the paused flag changes, icons stay cached.
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = reload()

        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = reload()
    }

    /** A new phone language (or display size) changes no package, so nothing else reloads. */
    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val now = newConfig.locales to newConfig.densityDpi
            if (now == loadedFor) return
            loadedFor = now
            reload()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }

    fun start() {
        runCatching {
            launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        }
        val config = context.resources.configuration
        loadedFor = config.locales to config.densityDpi
        runCatching { context.registerComponentCallbacks(configCallback) }
        reload()
    }

    fun stop() {
        runCatching { launcherApps.unregisterCallback(callback) }
        runCatching { context.unregisterComponentCallbacks(configCallback) }
    }

    fun reload(vararg changed: String) {
        pending.addAll(changed)
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(40)
            loading.withLock {
                // Drop changed icons as this load starts, not when the event came: a load
                // already running then could draw them again from the old package.
                val done = pending.toList()
                pending.removeAll(done)
                if (done.isNotEmpty()) {
                    icons.keys.removeAll { key -> done.any { key.startsWith("$it/") } }
                }
                val list = load()
                // Cancelled while loading: a newer reload is coming with a fresher list.
                ensureActive()
                _apps.value = list
            }
        }
    }

    private fun load(): List<LaunchableApp> {
        return runCatching {
            val user = Process.myUserHandle()
            val infos = launcherApps.getActivityList(null, user)
            val homes = homePackages()
            val density = context.resources.displayMetrics.densityDpi
            val size = (56 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
            if (size != iconPx) {
                icons.clear()
                iconPx = size
            }
            val live = HashSet<String>(infos.size * 2)
            infos.mapNotNull { info ->
                val cn = info.componentName
                if (cn.packageName == ownPackage) return@mapNotNull null
                val label = info.label?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val iconKey = "${cn.packageName}/${cn.className}@${info.user.hashCode()}"
                live += iconKey
                val icon = icons[iconKey] ?: runCatching {
                    info.getBadgedIcon(density).toBitmap(size, size).asImageBitmap()
                }.getOrNull()?.also { icons[iconKey] = it } ?: return@mapNotNull null
                LaunchableApp(
                    packageName = cn.packageName,
                    activityName = cn.className,
                    user = info.user,
                    label = label,
                    icon = icon,
                    isHome = HomeApps.isHome(cn.packageName, homes),
                    paused = (info.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
                )
            }.distinctBy { it.key }
                .sortedWith(compareBy(Ranking.labelOrder()) { it.label })
                .also { icons.keys.retainAll(live) }
        }.getOrElse { emptyList() }
    }

    private fun homePackages(): Set<String> {
        val found = ArrayList<Pair<String, Int>>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        fun query(flags: Int) {
            @Suppress("DEPRECATION")
            runCatching {
                context.packageManager.queryIntentActivities(intent, flags)
            }.getOrNull().orEmpty().forEach { info ->
                info.activityInfo?.packageName?.let { found += it to info.priority }
            }
        }
        query(PackageManager.MATCH_DEFAULT_ONLY)
        query(0)
        query(PackageManager.MATCH_ALL)
        runCatching {
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.let { info -> info.activityInfo?.packageName?.let { found += it to info.priority } }
        }
        val settings = runCatching {
            context.packageManager
                .resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        }.getOrNull()
        return HomeApps.homeSet(found, settings, ownPackage)
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

    /** Paused right now (an app timer, Focus mode, an admin): opening it only shows the system's "App paused". */
    fun isPaused(packageName: String): Boolean =
        runCatching { context.packageManager.isPackageSuspended(packageName) }.getOrDefault(false)

    fun openAppInfo(app: LaunchableApp, bounds: Rect? = null): Boolean {
        val component = ComponentName(app.packageName, app.activityName)
        val started = runCatching {
            launcherApps.startAppDetailsActivity(component, app.user, bounds, null)
            true
        }.getOrDefault(false)
        if (started) return true
        return runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
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

/** O(1) lookups over one app list snapshot; same answers as [AppRepository.find]. */
class AppIndex(apps: List<LaunchableApp>) {
    private val byKey = HashMap<String, LaunchableApp>(apps.size * 2)
    private val byPackage = HashMap<String, LaunchableApp>(apps.size * 2)

    init {
        for (app in apps) {
            byKey.putIfAbsent(app.key, app)
            byPackage.putIfAbsent(app.packageName, app)
        }
    }

    fun find(packageName: String, activityName: String?): LaunchableApp? {
        if (activityName != null) byKey["$packageName/$activityName"]?.let { return it }
        return byPackage[packageName]
    }
}
