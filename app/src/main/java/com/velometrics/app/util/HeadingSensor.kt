package com.velometrics.app.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Reports the device's compass heading (degrees, 0-360, 0 = North) for a phone held
 * roughly flat with the screen facing up, treating the top edge of the device as "forward".
 * Uses [Sensor.TYPE_ROTATION_VECTOR], which requires no runtime permissions.
 *
 * Heading updates are smoothed with an exponential low-pass filter and only reported when
 * they change by at least [MIN_CHANGE_DEG], to avoid jittery, excessive map updates.
 */
class HeadingSensor(
    context: Context,
    private val onHeadingChanged: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var smoothedHeading: Float? = null

    val isAvailable: Boolean get() = rotationSensor != null

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // Phone held flat, screen facing up: remap so the top edge of the device is "forward".
        SensorManager.remapCoordinateSystem(
            rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix
        )
        SensorManager.getOrientation(remappedMatrix, orientation)

        val azimuthDeg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f

        val previous = smoothedHeading
        val newSmoothed = if (previous == null) {
            azimuthDeg
        } else {
            (previous + ALPHA * angleDiff(azimuthDeg, previous) + 360f) % 360f
        }
        smoothedHeading = newSmoothed

        if (previous == null || abs(angleDiff(newSmoothed, previous)) >= MIN_CHANGE_DEG) {
            onHeadingChanged(newSmoothed)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Smallest signed difference (to - from) in degrees, handling 0/360 wraparound. */
    private fun angleDiff(to: Float, from: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private companion object {
        const val ALPHA = 0.15f
        const val MIN_CHANGE_DEG = 1f
    }
}
