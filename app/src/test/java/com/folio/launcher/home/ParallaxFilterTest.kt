package com.folio.launcher.home

import android.animation.ValueAnimator
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder

/** The print's parallax: still at rest, held while off, never moving for Remove animations or Power saving. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ParallaxFilterTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val gravity = SensorBuilder.newBuilder().setType(Sensor.TYPE_GRAVITY).build()
    private var enabled by mutableStateOf(true)

    /** A phone held to read, about 40° back: gravity mostly along y, some along z. */
    private val readingGz = 6.3f

    @Test
    fun filter_atRest_stopsDrawing() {
        val filter = ParallaxFilter(maxPx = 48f)
        val noise = Random(7)
        repeat(300) { filter.next(gx = jitter(noise), gz = readingGz + jitter(noise)) }
        // Ten seconds at 50 Hz. Noise alone used to move the offset, and redraw the screen, on every
        // sample; now at most one late correction can land as the offset settles.
        val draws = (1..500).count { filter.next(gx = jitter(noise), gz = readingGz + jitter(noise)) }
        assertTrue("$draws redraws at rest", draws <= 1)
        assertEquals(readingGz / SensorManager.GRAVITY_EARTH * 48f, filter.y, 0.5f)
    }

    @Test
    fun filter_aTenDegreeTilt_draws() {
        val filter = ParallaxFilter(maxPx = 48f)
        val noise = Random(7)
        repeat(300) { filter.next(gx = jitter(noise), gz = readingGz + jitter(noise)) }
        val rolled = SensorManager.GRAVITY_EARTH * sin(Math.toRadians(10.0)).toFloat()
        val draws = (1..300).count { filter.next(gx = rolled + jitter(noise), gz = readingGz + jitter(noise)) }
        assertTrue(draws > 0)
        assertEquals(-rolled / SensorManager.GRAVITY_EARTH * 48f, filter.x, 0.5f)
    }

    @Test
    fun filter_seeded_carriesOnWithoutAJump() {
        val filter = ParallaxFilter(maxPx = 48f)
        filter.seed(x = 12f, y = 30f)
        val g = SensorManager.GRAVITY_EARTH
        assertFalse(filter.next(gx = -12f / 48f * g, gz = 30f / 48f * g))
        assertEquals(12f, filter.x, 0f)
        assertEquals(30f, filter.y, 0f)
    }

    @Test
    fun printShift_staysInsideTheOverhang() {
        // S23: 1080 px wide at 1.08× leaves 43.2 px each side; 16 dp at density 3 is 48 px.
        assertEquals(43.2f, printShift(48f, 1080f, 1.08f), 0.01f)
        assertEquals(-43.2f, printShift(-48f, 1080f, 1.08f), 0.01f)
        assertEquals(30f, printShift(30f, 1080f, 1.08f), 0f)
        // 2340 px tall leaves 93.6 px, so the whole shift fits.
        assertEquals(48f, printShift(48f, 2340f, 1.08f), 0f)
        assertEquals(0f, printShift(10f, 0f, 1.08f), 0f)
    }

    @Test
    fun listensWhileResumed() {
        show()
        assertTrue(listening())
    }

    @Test
    fun turnedOff_holdsTheOffset() {
        val parallax = show()
        repeat(60) { tilt(gx = -4.9f, gz = 0f) }
        val held = parallax.x
        assertTrue(held > 1f)
        // A develop starts: the print stays put rather than snapping to centre.
        enabled = false
        compose.waitForIdle()
        assertFalse(listening())
        assertEquals(held, parallax.x, 0f)
        // And picks up from there, not from centre.
        enabled = true
        compose.waitForIdle()
        tilt(gx = -4.9f, gz = 0f)
        assertEquals(held, parallax.x, 0.5f)
    }

    @Test
    fun removeAnimations_holdsThePrintStill() {
        // Accessibility › Remove animations sets the animator duration scale to 0 in every app.
        setAnimatorScale(0f)
        try {
            val parallax = show()
            assertFalse(listening())
            tilt(gx = -4.9f, gz = readingGz)
            assertEquals(0f, parallax.x, 0f)
            assertEquals(0f, parallax.y, 0f)
        } finally {
            setAnimatorScale(1f)
        }
    }

    @Test
    fun powerSaving_holdsThePrintStill() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        val parallax = show()
        assertFalse(listening())
        tilt(gx = -4.9f, gz = readingGz)
        assertEquals(0f, parallax.x, 0f)
        assertEquals(0f, parallax.y, 0f)
    }

    private fun show(): ParallaxState {
        shadowOf(sensors).addSensor(gravity)
        lateinit var parallax: ParallaxState
        compose.setContent { parallax = rememberParallax(enabled = enabled) }
        compose.waitForIdle()
        return parallax
    }

    private fun tilt(gx: Float, gz: Float) {
        shadowOf(sensors).sendSensorEventToListeners(
            SensorEventBuilder.newBuilder(gravity, floatArrayOf(gx, 7.5f, gz)).build(),
        )
    }

    private fun listening() = shadowOf(sensors).listeners.isNotEmpty()

    /** About ±0.05 m/s², a resting phone's sensor noise. */
    private fun jitter(noise: Random) = (noise.nextFloat() - 0.5f) * 0.1f

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }
}
