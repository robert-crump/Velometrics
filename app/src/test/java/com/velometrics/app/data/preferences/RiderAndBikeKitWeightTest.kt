package com.velometrics.app.data.preferences

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RiderAndBikeKitWeightTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val system = intPreferencesKey("system_weight_kg")
    private val rider = intPreferencesKey("rider_weight_kg")
    private val bikeKit = intPreferencesKey("bike_kit_weight_kg")

    @Test
    fun `an existing system weight becomes rider weight minus 10 kg and bike + kit 10 kg`() = runBlocking {
        val old = preferencesOf(system to 82)
        assertTrue(SplitSystemWeightMigration.shouldMigrate(old))

        val migrated = SplitSystemWeightMigration.migrate(old)

        assertEquals(72, migrated[rider])
        assertEquals(10, migrated[bikeKit])
        assertNull(migrated[system])
    }

    @Test
    fun `an unset system weight stays unset`() = runBlocking {
        assertFalse(SplitSystemWeightMigration.shouldMigrate(preferencesOf()))
    }

    @Test
    fun `system weight is unset until the rider weight is set, then rider plus bike + kit`() = runBlocking {
        val repo = UserSettingsRepository(context)
        assertNull(repo.systemWeightKg.first())
        assertEquals(10, repo.bikeKitWeightKg.first())

        repo.saveRiderWeightKg(70)
        assertEquals(80, repo.systemWeightKg.first())

        repo.saveBikeKitWeightKg(12)
        assertEquals(70, UserSettingsRepository(context).riderWeightKg.first())
        assertEquals(82, UserSettingsRepository(context).systemWeightKg.first())
    }
}
