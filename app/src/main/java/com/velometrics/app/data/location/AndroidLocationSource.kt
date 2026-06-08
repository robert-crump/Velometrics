package com.velometrics.app.data.location

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import com.velometrics.app.domain.model.LocationFix
import com.velometrics.app.domain.service.LocationException
import com.velometrics.app.domain.service.LocationSource
import com.velometrics.app.util.CyclingConstants
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

        val enabledProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
        if (enabledProviders.isEmpty()) {
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
            enabledProviders.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    CyclingConstants.LOCATION_UPDATE_MIN_TIME_MS,
                    0f,
                    listener,
                    context.mainLooper,
                )
            }
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
