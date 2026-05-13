package com.dwm3000.tracker.tracking

import android.os.SystemClock
import com.dwm3000.tracker.uwb.UwbRangingManager

data class TimedUwbSample(
    val receivedTimeNs: Long,
    val distanceMeters: Float,
    val azimuthDegrees: Float,
    val elevationDegrees: Float,
    val peerAddress: String
)

data class TrackingSignals(
    val latestUwb: TimedUwbSample?,
    val cameraRollRad: Float
)

class TrackingSignalStore {
    private val lock = Any()
    private var latestUwb: TimedUwbSample? = null
    private var cameraRollRad: Float = 0f

    fun updateUwb(
        data: UwbRangingManager.RangingData,
        receivedTimeNs: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        synchronized(lock) {
            latestUwb = TimedUwbSample(
                receivedTimeNs = receivedTimeNs,
                distanceMeters = data.distanceMeters,
                azimuthDegrees = data.azimuthDegrees,
                elevationDegrees = data.elevationDegrees,
                peerAddress = data.peerAddress
            )
        }
    }

    fun updateCameraRoll(rollRad: Float) {
        synchronized(lock) {
            cameraRollRad = rollRad
        }
    }

    fun snapshot(): TrackingSignals {
        return synchronized(lock) {
            TrackingSignals(
                latestUwb = latestUwb,
                cameraRollRad = cameraRollRad
            )
        }
    }

    fun clearUwb() {
        synchronized(lock) {
            latestUwb = null
        }
    }
}
