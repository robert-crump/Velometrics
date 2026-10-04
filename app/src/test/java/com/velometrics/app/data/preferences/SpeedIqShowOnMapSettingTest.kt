package com.velometrics.app.data.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpeedIqShowOnMapSettingTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `off until turned on, then kept for a new repository instance`() = runBlocking {
        assertFalse(UserSettingsRepository(context).speedIqShowOnMap.first())

        UserSettingsRepository(context).saveSpeedIqShowOnMap(true)
        assertTrue(UserSettingsRepository(context).speedIqShowOnMap.first())

        UserSettingsRepository(context).saveSpeedIqShowOnMap(false)
        assertFalse(UserSettingsRepository(context).speedIqShowOnMap.first())
    }
}
