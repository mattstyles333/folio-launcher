package com.folio.launcher.data

object HomeApps {
    val known = setOf(
        "com.sec.android.app.launcher",
        "com.sec.android.app.easylauncher",
        "com.sec.android.app.twlauncher",
        "com.sec.android.app.desktoplauncher",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.apps.pixel.launcher",
        "com.teslacoilsw.launcher",
        "com.teslacoilsw.launcher.prime",
        "bitpit.launcher",
        "app.lawnchair",
        "app.lawnchair.nightly",
        "app.lawnchair.play",
        "com.actionlauncher.playstore",
        "com.microsoft.launcher",
        "com.huawei.android.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "net.oneplus.launcher",
        "com.android.plus.launcher",
        "com.nothing.launcher",
        "com.motorola.launcher3",
        "com.bbk.launcher2",
        "com.vivo.launcher",
        "com.sonymobile.home",
        "org.lineageos.trebuchet",
        "foundation.e.blisslauncher",
        "app.olauncher",
        "com.before.launcher",
        "com.smart.launcher",
        "ginlemon.flowerfree",
        "ginlemon.flowerpro",
        "com.fede.launcher",
        "com.kvaesitso",
    )

    fun packageLooksLikeLauncher(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        if (pkg in known) return true
        if (pkg.endsWith(".launcher") || pkg.contains(".launcher.")) return true
        if (pkg.endsWith(".launcher3")) return true
        return false
    }

    fun isHome(packageName: String, resolvedHomes: Set<String>): Boolean {
        return packageName in resolvedHomes || packageLooksLikeLauncher(packageName)
    }

    /**
     * Home screen packages from HOME matches (package to filter priority). Settings declares
     * one too, FallbackHome at -1000, only for "Phone is starting…"; like the system's Home
     * picker, leave the Settings app out, or its icon vanishes from the grid and the rail.
     */
    fun homeSet(found: List<Pair<String, Int>>, settingsPackage: String?, ownPackage: String): Set<String> {
        val out = found.filter { it.second >= 0 }.mapTo(HashSet()) { it.first }
        out.remove(ownPackage)
        settingsPackage?.let(out::remove)
        return out
    }
}
