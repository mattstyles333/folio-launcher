package com.folio.launcher.data

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder

/** System opens from activity resumes: one per arrival in an app, not one per screen inside it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UsageOpensTest {
    private val folio = "com.folio.launcher"
    private val whatsapp = "com.whatsapp"

    @Test
    fun screensInsideOneApp_areOneOpen_andHomeInBetweenStartsAnother() {
        val opens = RingerController.foldOpens(
            listOf(whatsapp to 1L, whatsapp to 2L, folio to 3L, whatsapp to 4L),
            self = folio,
        )
        assertEquals(mapOf(whatsapp to listOf(1L, 4L)), opens)
    }

    @Test
    fun goingBetweenApps_countsEachArrival() {
        // Gmail opens a link in Chrome, then Back returns to Gmail.
        val gmail = "com.google.android.gm"
        val chrome = "com.android.chrome"
        val opens = RingerController.foldOpens(
            listOf(gmail to 1L, chrome to 2L, gmail to 3L),
            self = folio,
        )
        assertEquals(mapOf(gmail to listOf(1L, 3L), chrome to listOf(2L)), opens)
    }

    @Test
    fun folio_isNeverAnOpen() {
        assertEquals(
            emptyMap<String, List<Long>>(),
            RingerController.foldOpens(listOf(folio to 1L, folio to 2L), self = folio),
        )
    }

    @Test
    fun theSystemsResumes_areReadAsOpens() {
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            app.packageName,
            AppOpsManager.MODE_ALLOWED,
        )
        val now = 1_700_000_000_000L
        val usage = shadowOf(app.getSystemService(UsageStatsManager::class.java))
        fun event(pkg: String, ago: Long, type: Int) = usage.addEvent(
            EventBuilder.buildEvent().setPackage(pkg).setTimeStamp(now - ago).setEventType(type).build(),
        )
        event(whatsapp, 5_000, UsageEvents.Event.ACTIVITY_RESUMED)
        event(whatsapp, 4_500, UsageEvents.Event.ACTIVITY_PAUSED)
        // A second WhatsApp screen.
        event(whatsapp, 4_000, UsageEvents.Event.ACTIVITY_RESUMED)
        // Home, then WhatsApp again.
        event(app.packageName, 3_000, UsageEvents.Event.ACTIVITY_RESUMED)
        event(whatsapp, 2_000, UsageEvents.Event.ACTIVITY_RESUMED)

        assertEquals(
            mapOf(whatsapp to listOf(now - 5_000, now - 2_000)),
            RingerController.queryUsageTimestamps(app, windowMs = Ranking.DAY_MS, now = now),
        )
    }
}
