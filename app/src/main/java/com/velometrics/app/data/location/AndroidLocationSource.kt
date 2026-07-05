package com.velometrics.app.data.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import com.velometrics.app.domain.model.LocationFix
import com.velometrics.app.domain.service.LocationException
import com.velometrics.app.domain.service.LocationSource
import com.velometrics.app.util.CyclingConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import javax.inject.Inject

class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient,
) : LocationSource {

    override fun fixes(): Flow<LocationFix> = callbackFlow {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val enabledProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
        if (enabledProviders.isEmpty()) {
            throw LocationException.NoProvider
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location -> trySend(location.toLocationFix()) }
            }
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            CyclingConstants.LOCATION_UPDATE_MIN_TIME_MS,
        ).build()

        try {
            @Suppress("MissingPermission")
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            throw LocationException.PermissionDenied
        }

        awaitClose { fusedLocationClient.removeLocationUpdates(callback) }
    }

    override suspend fun lastKnownFix(maxAccuracyM: Float): LocationFix? {
        return try {
            @Suppress("MissingPermission")
            val location = fusedLocationClient.lastLocation.await() ?: return null
            val ageMs = System.currentTimeMillis() - location.time
            if (location.accuracy > maxAccuracyM || ageMs > CyclingConstants.LOCATION_CACHE_MAX_AGE_MS) {
                null
            } else {
                location.toLocationFix()
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun Location.toLocationFix() = LocationFix(
        lat = latitude,
        lon = longitude,
        accuracyM = accuracy,
        timestamp = Instant.ofEpochMilli(time),
    )

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resumeWith(Result.success(result)) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.success(null)) }
    }
}
