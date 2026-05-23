package com.cyclegraph.app.data.location

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import com.cyclegraph.app.domain.model.LocationFix
import com.cyclegraph.app.domain.service.LocationException
import com.cyclegraph.app.domain.service.LocationSource
import com.cyclegraph.app.util.CyclingConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant
import javax.inject.Inject

class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationSource {

    override fun fixes(): Flow<LocationFix> = callbackFlow {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            throw LocationException.NoProvider
        }

        val listener = LocationListener { location ->
            trySend(
                LocationFix(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracyM = location.accuracy,
                    timestamp = Instant.ofEpochMilli(location.time),
                )
            )
        }

        try {
            @Suppress("MissingPermission")
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                CyclingConstants.LOCATION_UPDATE_MIN_TIME_MS,
                0f,
                listener,
                context.mainLooper,
            )
        } catch (_: SecurityException) {
            throw LocationException.PermissionDenied
        }

        awaitClose { locationManager.removeUpdates(listener) }
    }

    override suspend fun lastKnownFix(maxAccuracyM: Float): LocationFix? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    locationManager.getLastKnownLocation(provider)
                }
                .filter { it.accuracy <= maxAccuracyM }
                .minByOrNull { it.accuracy }
                ?.let { location ->
                    LocationFix(
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyM = location.accuracy,
                        timestamp = Instant.ofEpochMilli(location.time),
                    )
                }
        } catch (_: SecurityException) {
            null
        }
    }
}
