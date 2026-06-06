package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.LocationFix
import kotlinx.coroutines.flow.Flow

sealed class LocationException : Exception() {
    object NoProvider : LocationException()
    object PermissionDenied : LocationException()
}

interface LocationSource {
    /** Cold Flow. Each subscription registers an OS LocationListener;
     *  cancelling unregisters it. Throws on subscribe if the GPS
     *  provider is disabled or location permission is denied. */
    fun fixes(): Flow<LocationFix>

    /** Best last-known fix across GPS + NETWORK providers,
     *  only returned if accuracy <= maxAccuracyM. null otherwise. */
    suspend fun lastKnownFix(maxAccuracyM: Float): LocationFix?
}
