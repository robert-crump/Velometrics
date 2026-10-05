package com.velometrics.app.data.preferences

import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.testSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OnboardingGateTest {

    private val flag = object : OnboardingFlagStore {
        var done = false
        override suspend fun isOnboardingDone() = done
        override suspend fun markOnboardingDone() {
            done = true
        }
    }
    private val sessions = FakeCyclingSessionRepository()
    private val gate = OnboardingGate(flag, sessions)

    @Test
    fun `fresh install without rides shows onboarding until it's completed`() = runBlocking {
        assertTrue(gate.shouldShow())
        assertTrue(gate.shouldShow())

        gate.complete()
        assertFalse(gate.shouldShow())
    }

    @Test
    fun `upgraded install with rides skips onboarding and stays skipped once its rides are deleted`() = runBlocking {
        sessions.sessions += testSession(1, Instant.parse("2026-09-01T08:00:00Z"))
        assertFalse(gate.shouldShow())
        assertTrue(flag.done)

        sessions.sessions.clear()
        assertFalse(gate.shouldShow())
    }
}
