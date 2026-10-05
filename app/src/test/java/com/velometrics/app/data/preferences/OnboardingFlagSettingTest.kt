package com.velometrics.app.data.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OnboardingFlagSettingTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `unset until marked done, then kept for a new repository instance`() = runBlocking {
        assertFalse(UserSettingsRepository(context).isOnboardingDone())

        UserSettingsRepository(context).markOnboardingDone()
        assertTrue(UserSettingsRepository(context).isOnboardingDone())
    }
}
