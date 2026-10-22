package com.folio.launcher.home

import android.animation.ValueAnimator
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs

@Stable
class ParallaxState {
    var x by mutableFloatStateOf(0f)
        internal set
    var y by mutableFloatStateOf(0f)
        internal set
}

@Composable
fun rememberParallax(maxShift: Dp = 16.dp, enabled: Boolean = true): ParallaxState {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember { ParallaxState() }
    val maxPx = with(density) { maxShift.toPx() }

    DisposableEffect(enabled, maxPx, lifecycle) {
        // Off (a develop, an overlay) holds the print where it is: snapping to centre jolted the develop.
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            return@DisposableEffect onDispose { }
        }
        val power = context.getSystemService(PowerManager::class.java)
        val filter = ParallaxFilter(maxPx)
        var registered = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (filter.next(gx = event.values[0], gz = event.values.getOrNull(2) ?: 0f)) {
                    state.x = filter.x
                    state.y = filter.y
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun register() {
            if (registered) return
            // Remove animations or Power saving: a still, centred print. Asked again on every resume.
            if (!ValueAnimator.areAnimatorsEnabled() || power.isPowerSaveMode) {
                state.x = 0f
                state.y = 0f
                return
            }
            filter.seed(state.x, state.y)
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            registered = true
        }
        fun unregister() {
            if (registered) {
                manager.unregisterListener(listener)
                registered = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose {
            lifecycle.removeObserver(observer)
            unregister()
        }
    }
    return state
}

/**
 * Tilt to print offset: a low-pass on gravity that only reports a move of half a pixel or more
 * from the offset last drawn. Sensor noise alone changed the offset on every sample, so a phone
 * at rest redrew the whole screen about 50 times a second.
 */
internal class ParallaxFilter(private val maxPx: Float) {
    /** The offset last reported, i.e. what's on screen. */
    var x = 0f
        private set
    var y = 0f
        private set
    private var sx = 0f
    private var sy = 0f

    /** Carry on from what's on screen, so restarting the sensor never jumps the print. */
    fun seed(x: Float, y: Float) {
        this.x = x
        this.y = y
        sx = x
        sy = y
    }

    /** One gravity sample (m/s²); true when [x] / [y] moved enough to draw. */
    fun next(gx: Float, gz: Float): Boolean {
        val tx = (-gx / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) * maxPx
        val ty = (gz / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) * maxPx
        sx = sx * 0.84f + tx * 0.16f
        sy = sy * 0.84f + ty * 0.16f
        if (abs(sx - x) < DEAD_BAND_PX && abs(sy - y) < DEAD_BAND_PX) return false
        x = sx
        y = sy
        return true
    }

    private companion object {
        /** Less than this can't be seen. */
        const val DEAD_BAND_PX = 0.5f
    }
}
