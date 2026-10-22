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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        /** What the last Bing fetch was told to stay away from, in order. */
        var lastAvoid: Set<String>? = null

        /** Your photos' files by name: a picked photo's URI's last segment and a count ("bad…" can't be read). */
        val roll = mutableListOf<String>()
        private var copies = 0

        /** Runs as each picked photo is read, so a test can hold one there. */
        var reading: suspend (Uri) -> Unit = {}

        override fun exists() = current.isNotEmpty()

        override fun prevExists() = prev.isNotEmpty()

        override suspend fun load(): WallpaperRepository.LoadedWallpaper? = null

        override suspend fun importBing(avoid: Set<String>): WallpaperRepository.BingShot? {
            bingFetches++
            lastAvoid = avoid
            return bing()?.also { promote(it.id) }
        }

        override suspend fun importFromUri(uri: Uri) = photoReadable && promote("photo")

        override suspend fun importSystem() = promote("system")

        override suspend fun swapWithPrev(): Boolean {
            if (prev.isEmpty()) return false
            current = prev.also { prev = current }
            return true
        }

        override suspend fun addToRoll(uri: Uri): String? {
            reading(uri)
            val photo = uri.lastPathSegment.orEmpty()
            if (photo.startsWith("bad")) return null
            return "$photo#${++copies}".also { roll += it }
        }

        override suspend fun showFromRoll(name: String) = name in roll && promote(name)

        override suspend fun pruneRoll(keep: Collection<String>) {
            roll.retainAll(keep.toSet())
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

    private fun TestScope.controller(
        files: FakeFiles,
        today: () -> Int = { day },
        fitQuote: suspend (WallpaperRepository.BingShot, Int) -> Int? = { _, _ -> null },
    ) = PrintController(
        scope = backgroundScope,
        repo = files,
        prefsStore = prefsStore,
        fitQuote = fitQuote,
        today = today,
    )

    /** What the photo picker hands back for Settings › Your photos. */
    private fun picked(vararg names: String) =
        names.map { Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/$it") }

    /** The picked photo each roll file holds, in the roll's order. */
    private fun List<String>.photos() = map { it.substringBefore('#') }

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

    @Test
    fun nextPrint_staysAwayFromTheRecentOnes_newestFirst() = runTest {
        prefsStore.update {
            Prefs(wallpaperSet = true, bingId = "A", bingPrevId = "Z", bingDay = day, recentBingIds = listOf("A", "Z", "Y"))
        }
        val files = FakeFiles(current = "A", prev = "Z")
        files.bing = { WallpaperRepository.BingShot("B", "Title B", "Credit") }
        val print = controller(files)

        print.next()
        settle()

        assertEquals(listOf("A", "Z", "Y"), files.lastAvoid?.toList())
        assertEquals(listOf("B", "A", "Z", "Y"), prefsStore.data.first().recentBingIds)
    }

    @Test
    fun nextPrint_withNoPrintYet_isTodays() = runTest {
        // Onboarding's "Today's Bing print" after the first fetch failed: nothing to avoid, so today's.
        val files = FakeFiles(current = "")
        files.bing = { WallpaperRepository.BingShot("Today", "Title", "Credit") }
        val print = controller(files)

        print.next()
        settle()

        assertEquals(emptySet<String>(), files.lastAvoid)
        assertEquals("Today", files.current)
    }

    @Test
    fun yourPhotos_areThePrint_inTheOrderPicked() = runTest {
        prefsStore.update { Prefs(wallpaperSet = true, bingId = "A", bingDay = day, quoteFit = 7) }
        val files = FakeFiles(current = "A")
        val print = controller(files)

        print.setRoll(picked("p1", "p2", "p3"))
        settle()

        val prefs = prefsStore.data.first()
        assertEquals(listOf("p1", "p2", "p3"), prefs.rollFiles.photos())
        assertEquals(prefs.rollFiles, files.roll)
        assertEquals(prefs.rollFiles.first(), files.current)
        assertEquals(0, prefs.rollAt)
        assertEquals(day, prefs.rollDay)
        // Previous print still goes back to the Bing; the photos have the plain daily quote.
        assertEquals("A", files.prev)
        assertEquals("", prefs.bingId)
        assertEquals("A", prefs.bingPrevId)
        assertEquals(-1, prefs.quoteFit)
        assertEquals(7, prefs.quotePrevFit)
        assertEquals("Added 3 photos", print.state.value.rollNote)
    }

    @Test
    fun yourPhotos_sayHowFarTheyAre_whileTheyComeIn() = runTest {
        val files = FakeFiles(current = "A")
        val second = CompletableDeferred<Unit>()
        files.reading = { uri -> if (uri.lastPathSegment == "p2") second.await() }
        val print = controller(files)

        print.setRoll(picked("p1", "p2", "p3"))
        val partWay = print.state.first { "2 of 3" in it.rollNote }
        assertEquals("Adding 2 of 3…", partWay.rollNote)
        assertTrue(partWay.busy)
        second.complete(Unit)
        settle()

        assertEquals("Added 3 photos", print.state.value.rollNote)
        assertFalse(print.state.value.busy)
    }

    @Test
    fun yourPhotos_oneThatCannotBeRead_isSkippedAndCounted() = runTest {
        val files = FakeFiles(current = "A")
        val print = controller(files)

        print.setRoll(picked("p1", "bad", "p3"))
        settle()

        assertEquals(listOf("p1", "p3"), prefsStore.data.first().rollFiles.photos())
        assertEquals("Added 2 photos, 1 couldn't be read", print.state.value.rollNote)
    }

    @Test
    fun yourPhotos_noneReadable_leaveThePrintAndTheRollAsTheyWere() = runTest {
        prefsStore.update { Prefs(wallpaperSet = true, rollFiles = listOf("r1", "r2"), rollAt = 1, rollDay = day) }
        val files = FakeFiles(current = "r2", prev = "r1").apply { roll += listOf("r1", "r2") }
        val print = controller(files)

        print.setRoll(picked("bad1", "bad2"))
        settle()

        assertEquals("r2", files.current)
        assertEquals(listOf("r1", "r2"), files.roll)
        val prefs = prefsStore.data.first()
        assertEquals(listOf("r1", "r2"), prefs.rollFiles)
        assertEquals(1, prefs.rollAt)
        assertEquals("Couldn't open those photos", print.state.value.rollNote)
    }

    @Test
    fun yourPhotos_firstUnlockAfterMidnight_showsTheNext_neverABing() = runTest {
        prefsStore.update {
            Prefs(wallpaperSet = true, rollFiles = listOf("r1", "r2", "r3"), rollAt = 0, rollDay = day - 1)
        }
        val files = FakeFiles(current = "r1").apply { roll += listOf("r1", "r2", "r3") }
        files.bing = { WallpaperRepository.BingShot("B", "Title B", "Credit") }
        val print = controller(files)

        print.ensureToday()
        settle()
        // Later the same day, nothing more.
        print.ensureToday()
        settle()

        assertEquals(0, files.bingFetches)
        assertEquals("r2", files.current)
        assertEquals("r1", files.prev)
        val prefs = prefsStore.data.first()
        assertEquals(1, prefs.rollAt)
        assertEquals(0, prefs.rollPrevAt)
        assertEquals(day, prefs.rollDay)
    }

    @Test
    fun yourPhotos_doubleTap_showsTheNextOfThem_roundAgainAfterTheLast() = runTest {
        prefsStore.update {
            Prefs(wallpaperSet = true, rollFiles = listOf("r1", "r2", "r3"), rollAt = 2, rollDay = day)
        }
        val files = FakeFiles(current = "r3").apply { roll += listOf("r1", "r2", "r3") }
        files.bing = { WallpaperRepository.BingShot("B", "Title B", "Credit") }
        val print = controller(files)

        print.next()
        settle()

        assertEquals(0, files.bingFetches)
        assertEquals("r1", files.current)
        assertEquals(0, prefsStore.data.first().rollAt)
        // Nothing was on its way, so nothing says so.
        assertEquals("", print.state.value.caption)
    }

    @Test
    fun previousBackToYourPhotos_isTheRollAgain_andTodaysPrint() = runTest {
        // Settings › Bing print two days ago left the roll at r2; today Previous print brings it back.
        prefsStore.update {
            Prefs(
                wallpaperSet = true,
                bingId = "B",
                bingDay = day - 2,
                rollFiles = listOf("r1", "r2", "r3"),
                rollPrevAt = 1,
                rollDay = day - 2,
            )
        }
        val files = FakeFiles(current = "B", prev = "r2").apply { roll += listOf("r1", "r2", "r3") }
        files.bing = { WallpaperRepository.BingShot("C", "Title C", "Credit") }
        val print = controller(files)

        print.previous()
        settle()
        // The next resume neither moves it on nor fetches a Bing: it's today's print.
        print.ensureToday()
        settle()

        assertEquals("r2", files.current)
        assertEquals(0, files.bingFetches)
        val prefs = prefsStore.data.first()
        assertEquals(1, prefs.rollAt)
        assertEquals(day, prefs.rollDay)
        assertEquals("", prefs.bingId)
        assertEquals("B", prefs.bingPrevId)
        // Double-tap is the roll's again, going on from r2.
        print.next()
        settle()
        assertEquals("r3", files.current)
    }

    @Test
    fun settingsBingPrint_leavesYourPhotos_andTheDailyBingForgetsThem() = runTest {
        var today = day
        prefsStore.update {
            Prefs(wallpaperSet = true, rollFiles = listOf("r1", "r2", "r3"), rollAt = 1, rollPrevAt = 0, rollDay = day)
        }
        val files = FakeFiles(current = "r2", prev = "r1").apply { roll += listOf("r1", "r2", "r3") }
        var shots = 0
        files.bing = { WallpaperRepository.BingShot("B${++shots}", "Title", "Credit") }
        val print = controller(files, today = { today })

        print.nextBing()
        settle()

        assertEquals("B1", files.current)
        assertEquals("r2", files.prev)
        var prefs = prefsStore.data.first()
        assertEquals("B1", prefs.bingId)
        assertFalse(prefs.isRollPrint)
        assertEquals(1, prefs.rollPrevAt)
        // Previous print can still go back to r2, so the roll stays.
        assertEquals(listOf("r1", "r2", "r3"), files.roll)

        // Tomorrow's Bing pushes r2 out of Previous print: the roll is forgotten, files and all.
        today = day + 1
        print.ensureToday()
        settle()

        assertEquals("B2", files.current)
        prefs = prefsStore.data.first()
        assertEquals(emptyList<String>(), prefs.rollFiles)
        assertEquals(-1, prefs.rollPrevAt)
        assertEquals(emptyList<String>(), files.roll)
    }

    @Test
    fun aPhotoOfItsOwn_replacesYourPhotos() = runTest {
        prefsStore.update { Prefs(wallpaperSet = true, rollFiles = listOf("r1", "r2"), rollAt = 0, rollDay = day) }
        val files = FakeFiles(current = "r1").apply { roll += listOf("r1", "r2") }
        files.bing = { WallpaperRepository.BingShot("B", "Title B", "Credit") }
        val print = controller(files)

        print.setFromUri(photo)
        settle()

        assertEquals("photo", files.current)
        assertEquals(emptyList<String>(), files.roll)
        val prefs = prefsStore.data.first()
        assertEquals(emptyList<String>(), prefs.rollFiles)
        assertEquals(-1, prefs.rollAt)
        assertEquals(-1, prefs.rollPrevAt)
        // Double-tap on a photo of its own is a Bing, as before.
        print.next()
        settle()
        assertEquals(1, files.bingFetches)
        assertEquals("B", files.current)
    }

    @Test
    fun aNewRoll_replacesTheOldOne_filesAndAll() = runTest {
        val files = FakeFiles(current = "A")
        val print = controller(files)

        print.setRoll(picked("p1", "p2"))
        settle()
        val first = prefsStore.data.first().rollFiles
        print.setRoll(picked("p3", "p4"))
        settle()

        val prefs = prefsStore.data.first()
        assertEquals(listOf("p3", "p4"), prefs.rollFiles.photos())
        assertEquals(prefs.rollFiles, files.roll)
        assertTrue(first.none { it in files.roll })
        // The old roll's photo is still the previous print, but only as a photo: that roll is gone.
        assertEquals(first.first(), files.prev)
        assertEquals(-1, prefs.rollPrevAt)
    }

    @Test
    fun oneReadablePhoto_isAPrintOfItsOwn() = runTest {
        val files = FakeFiles(current = "A")
        files.bing = { WallpaperRepository.BingShot("B", "Title B", "Credit") }
        val print = controller(files)

        print.setRoll(picked("p1", "bad"))
        settle()

        assertEquals("p1", files.current.substringBefore('#'))
        // The print keeps its own copy: there's no roll to go round.
        assertEquals(emptyList<String>(), files.roll)
        val prefs = prefsStore.data.first()
        assertEquals(emptyList<String>(), prefs.rollFiles)
        assertFalse(prefs.isRollPrint)
        assertEquals("Added 1 photo, 1 couldn't be read", print.state.value.rollNote)
        print.next()
        settle()
        assertEquals(1, files.bingFetches)
    }

    @Test
    fun yourPhotos_neverAskJevForAQuote() = runTest {
        var fits = 0
        prefsStore.update { Prefs(wallpaperSet = true, bingId = "A", bingDay = day, quoteSalt = 4) }
        val files = FakeFiles(current = "A")
        val print = controller(files, fitQuote = { _, _ -> fits++; 3 })

        print.setRoll(picked("p1", "p2", "p3"))
        settle()
        print.next()
        settle()

        assertEquals(0, fits)
        val prefs = prefsStore.data.first()
        assertEquals(-1, prefs.quoteFit)
        // The plain daily pick: one of your photos doesn't turn the page as a new Bing does.
        assertEquals(4, prefs.quoteSalt)
    }

    @Test
    fun anImportAKillCutShort_isTidiedOnStart() = runTest {
        prefsStore.update { Prefs(wallpaperSet = true, rollFiles = listOf("r1", "r2"), rollAt = 0, rollDay = day) }
        val files = FakeFiles(current = "r1").apply { roll += listOf("r1", "r2", "stray") }
        val print = controller(files)

        print.start()
        settle()

        assertEquals(listOf("r1", "r2"), files.roll)
        assertEquals("r1", files.current)
    }

    @Test
    fun rollNext_goesRound_onlyForTwoOrMore() {
        assertEquals(1, PrintController.rollNext(at = 0, size = 3))
        assertEquals(0, PrintController.rollNext(at = 2, size = 3))
        assertNull(PrintController.rollNext(at = 0, size = 1))
        assertNull(PrintController.rollNext(at = -1, size = 3))
        assertNull(PrintController.rollNext(at = 3, size = 3))
    }

    @Test
    fun rollNote_saysWhatMadeItIn() {
        assertEquals("Added 12 photos", PrintController.rollNote(added = 12, picked = 12))
        assertEquals("Added 12 photos, 1 couldn't be read", PrintController.rollNote(added = 12, picked = 13))
        assertEquals("Added 1 photo, 2 couldn't be read", PrintController.rollNote(added = 1, picked = 3))
        assertEquals("Couldn't open that photo", PrintController.rollNote(added = 0, picked = 1))
        assertEquals("Couldn't open those photos", PrintController.rollNote(added = 0, picked = 4))
    }
}
