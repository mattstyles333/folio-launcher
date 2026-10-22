package com.folio.launcher.data

import android.app.Application
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GoogleSearchTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun handles(activity: ComponentName, filter: IntentFilter) {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(activity)
        pm.addIntentFilterForActivity(activity, filter)
    }

    @Test
    fun search_typesTheQueryIntoTheGoogleApp() {
        handles(
            ComponentName(GoogleSearch.PACKAGE, "com.google.android.googlequicksearchbox.SearchActivity"),
            IntentFilter(Intent.ACTION_WEB_SEARCH).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )

        assertTrue(GoogleSearch.search(app, "a b"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_WEB_SEARCH, started.action)
        assertEquals(GoogleSearch.PACKAGE, started.`package`)
        assertEquals("a b", started.getStringExtra(SearchManager.QUERY))
    }

    @Test
    fun search_withoutASearchApp_opensGoogleInTheBrowser() {
        handles(
            ComponentName("com.android.chrome", "com.android.chrome.Main"),
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            },
        )

        assertTrue(GoogleSearch.search(app, "a b"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://www.google.com/search?q=a%20b", started.dataString)
    }
}
