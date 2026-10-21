package com.folio.launcher.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Print changes against fake print files and the real prefs store. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PrintControllerTest {
    private val prefsStore = PrefsStore(ApplicationProvider.getApplicationContext())
    private val day = 20_000
    private val photo = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1")

    /** wallpaper.jpg and wallpaper.prev.jpg, by name. Bing answers with whatever [bing] returns, when it returns. */
    private class FakeFiles(var current: String, var prev: String = "") : PrintFiles {
        var bing: suspend () -> WallpaperRepository.BingShot? = { null }
        var bingFetches = 0
        var photoReadable = true

        override fun exists() = current.isNotEmpty()

        override fun prevExists() = prev.isNotEmpty()

        override suspend fun load(): WallpaperRepository.LoadedWallpaper? = null

        override suspend fun importBing(avoid: Set<String>): WallpaperRepository.BingShot? {
            bingFetches++
            return bing()?.also { promote(it.id) }
        }

        override suspend fun importFromUri(uri: Uri) = photoReadable && promote("photo")

        override suspend fun importSystem() = promote("system")

        override suspend fun swapWithPrev(): Boolean {
            if (prev.isEmpty()) return false
            current = prev.also { prev = current }
            return true
        }

        private fun promote(next: String): Boolean {
            if (current.isNotEmpty()) prev = current
            current = next
            return true
        }
    }

    /** The prefs DataStore is one per process: leave it as a fresh install for the next test class. */
    @After
    fun forgetPrefs() = runBlocking { prefsStore.update { Prefs() } }

    private fun TestScope.controller(files: FakeFiles) = PrintController(
        scope = backgroundScope,
        repo = files,
        prefsStore = prefsStore,
        finishOnboarding = { it },
        today = { day },
    )

    /** Lets every print job started so far, and any they start, finish (prefs writes are real I/O). */
    private suspend fun TestScope.settle() {
        while (true) {
            val running = backgroundScope.coroutineContext.job.children.filter { it.isActive }.toList()
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    @Test
    fun photoPickedDuringTheDailyBing_landsAfterIt() = runTest {
        prefsStore.update { Prefs(wallpaperSet = true, bingId = "A", bingDay = day - 1) }
        val files = FakeFiles(current = "A")
        val fetching = CompletableDeferred<Unit>()
        val landing = CompletableDeferred<WallpaperRepository.BingShot?>()
        files.bing = {
            fetching.complete(Unit)
            landing.await()
        }
        val print = controller(files)

        print.ensureToday()
        fetching.await()
        // The quiet daily fetch holds the print: the picked photo used to be dropped here.
        print.setFromUri(photo)
        landing.complete(WallpaperRepository.BingShot("B", "Title B", "Credit"))
        settle()

        assertEquals("photo", files.current)
        assertEquals("B", files.prev)
        val prefs = prefsStore.data.first()
        assertEquals("", prefs.bingId)
        assertEquals("B", prefs.bingPrevId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unreadablePhoto_saysSo() = runTest {
        prefsStore.update { Prefs(wallpaperSet = true, bingId = "A", bingDay = day) }
        val files = FakeFiles(current = "A").apply { photoReadable = false }
        val print = controller(files)

        print.setFromUri(photo)
        runCurrent()

        assertEquals("Couldn't open that photo", print.state.value.caption)
        assertEquals("A", files.current)
    }

    @Test
    fun previousRightAfterANewPrint_clearsItsTitle() = runTest {
        prefsStore.update { Prefs(wallpaperSet = true, bingId = "A", bingDay = day) }
        val files = FakeFiles(current = "A")
        files.bing = { WallpaperRepository.BingShot("B", "Title B", "Credit") }
        val print = controller(files)

        print.next()
        assertEquals("Title B", print.state.first { !it.busy }.caption)
        // Within the title's two seconds, Settings › Previous print: B's title must not stay behind.
        print.previous()
        settle()

        assertEquals("A", files.current)
        assertEquals("", print.state.value.caption)
    }

    @Test
    fun previousTappedTwiceQuickly_swapsOnce() = runTest {
        prefsStore.update { Prefs(wallpaperSet = true, bingId = "A", bingPrevId = "B", bingDay = day) }
        val files = FakeFiles(current = "A", prev = "B")
        val print = controller(files)

        // A double tap on Settings › Previous print: the second must not swap straight back.
        print.previous()
        print.previous()
        settle()

        assertEquals("B", files.current)
        assertEquals("B", prefsStore.data.first().bingId)
        // Once that swap is done, another tap is another swap.
        print.previous()
        settle()
        assertEquals("A", files.current)
    }

    @Test
    fun previousBackToABing_isTodaysPrint() = runTest {
        // A Bing three days ago, then a photo the same day; today Previous print brings the Bing back.
        prefsStore.update { Prefs(wallpaperSet = true, bingId = "", bingPrevId = "A", bingDay = day - 3) }
        val files = FakeFiles(current = "photo", prev = "A")
        files.bing = { WallpaperRepository.BingShot("B", "Title B", "Credit") }
        val print = controller(files)

        print.previous()
        settle()
        // The next resume must not swap it for a random Bing and push the photo out of history.
        print.ensureToday()
        settle()

        assertEquals(0, files.bingFetches)
        assertEquals("A", files.current)
        assertEquals("photo", files.prev)
        val prefs = prefsStore.data.first()
        assertEquals("A", prefs.bingId)
        assertEquals(day, prefs.bingDay)
    }
}
