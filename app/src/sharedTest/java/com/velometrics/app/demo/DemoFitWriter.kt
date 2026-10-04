package com.velometrics.app.demo

import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg

/**
 * Encodes a simulated ride as a FIT activity: a file id, `RecordMesg`s (position, speed, power,
 * heart rate, altitude, distance, timestamp) and timer `EventMesg`s — everything the app's FIT
 * import reads. Records and events are interleaved in time order, as a head unit writes them.
 */
object DemoFitWriter {
    private const val FIT_EPOCH_OFFSET_SEC = 631_065_600L // 1989-12-31T00:00:00Z
    private const val SEMICIRCLES_PER_DEG = (1L shl 31) / 180.0

    fun encode(samples: List<DemoSample>, events: List<DemoTimerEvent>): ByteArray {
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)
        encoder.write(FileIdMesg().apply {
            type = File.ACTIVITY
            manufacturer = Manufacturer.DEVELOPMENT
            product = 219
            serialNumber = 219L
            timeCreated = fitTime(samples.first().epochSec)
        })

        var e = 0
        fun flushEventsUpTo(epochSec: Long) {
            while (e < events.size && events[e].epochSec <= epochSec) {
                val event = events[e++]
                encoder.write(EventMesg().apply {
                    timestamp = fitTime(event.epochSec)
                    this.event = Event.TIMER
                    eventType = when (event.type) {
                        DemoTimerEventType.START -> EventType.START
                        DemoTimerEventType.STOP -> EventType.STOP
                        DemoTimerEventType.STOP_ALL -> EventType.STOP_ALL
                    }
                    eventGroup = 0
                })
            }
        }

        for (s in samples) {
            flushEventsUpTo(s.epochSec)
            encoder.write(RecordMesg().apply {
                timestamp = fitTime(s.epochSec)
                positionLat = (s.lat * SEMICIRCLES_PER_DEG).toInt()
                positionLong = (s.lon * SEMICIRCLES_PER_DEG).toInt()
                altitude = s.altitudeM.toFloat()
                speed = s.speedMps.toFloat()
                distance = s.distanceM.toFloat()
                power = s.power
                heartRate = s.heartRate.toShort()
                temperature = s.temperatureC.toByte()
            })
        }
        flushEventsUpTo(Long.MAX_VALUE)
        return encoder.close()
    }

    private fun fitTime(epochSec: Long) = DateTime(epochSec - FIT_EPOCH_OFFSET_SEC)
}
