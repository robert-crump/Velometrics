package com.velometrics.app.data.location

import android.content.Context
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.velometrics.app.util.CyclingConstants
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidLocationSourceTest {

    private fun fusedClientReturning(location: Location?): FusedLocationProviderClient {
        val task = mockk<Task<Location>>()
        every { task.addOnSuccessListener(any()) } answers {
            firstArg<OnSuccessListener<Location>>().onSuccess(location)
            task
        }
        every { task.addOnFailureListener(any()) } answers {
            // success already delivered; failure listener is simply attached
            task
        }
        val client = mockk<FusedLocationProviderClient>()
        every { client.lastLocation } returns task
        return client
    }

    private fun locationAt(ageMs: Long, accuracyM: Float = 20f): Location {
        val location = mockk<Location>()
        every { location.time } returns System.currentTimeMillis() - ageMs
        every { location.accuracy } returns accuracyM
        every { location.latitude } returns 50.0
        every { location.longitude } returns 6.0
        return location
    }

    @Test
    fun `stale cached fix is not surfaced as current location`() = runTest {
        val staleLocation = locationAt(ageMs = CyclingConstants.LOCATION_CACHE_MAX_AGE_MS + 1_000L)
        val source = AndroidLocationSource(mockk<Context>(relaxed = true), fusedClientReturning(staleLocation))

        val fix = source.lastKnownFix(CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M)

        assertNull(fix)
    }

    @Test
    fun `fresh cached fix within 2 minutes is surfaced`() = runTest {
        val freshLocation = locationAt(ageMs = 30_000L)
        val source = AndroidLocationSource(mockk<Context>(relaxed = true), fusedClientReturning(freshLocation))

        val fix = source.lastKnownFix(CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M)

        assertEquals(50.0, fix!!.lat, 0.0001)
        assertEquals(6.0, fix.lon, 0.0001)
    }

    @Test
    fun `fix exceeding accuracy threshold is not surfaced even when fresh`() = runTest {
        val inaccurateLocation = locationAt(ageMs = 1_000L, accuracyM = 999f)
        val source = AndroidLocationSource(mockk<Context>(relaxed = true), fusedClientReturning(inaccurateLocation))

        val fix = source.lastKnownFix(CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M)

        assertNull(fix)
    }

    @Test
    fun `no cached location returns null`() = runTest {
        val source = AndroidLocationSource(mockk<Context>(relaxed = true), fusedClientReturning(null))

        val fix = source.lastKnownFix(CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M)

        assertNull(fix)
    }
}
