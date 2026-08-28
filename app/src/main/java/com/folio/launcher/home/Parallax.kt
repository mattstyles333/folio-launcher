package com.folio.launcher.home

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
        if (!enabled) {
            state.x = 0f
            state.y = 0f
            return@DisposableEffect onDispose { }
        }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            return@DisposableEffect onDispose { }
        }
        var sx = 0f
        var sy = 0f
        var registered = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gz = event.values.getOrNull(2) ?: 0f
                val tx = (-gx / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) * maxPx
                val ty = (gz / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) * maxPx
                sx = sx * 0.84f + tx * 0.16f
                sy = sy * 0.84f + ty * 0.16f
                state.x = sx
                state.y = sy
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun register() {
            if (!registered) {
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                registered = true
            }
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
