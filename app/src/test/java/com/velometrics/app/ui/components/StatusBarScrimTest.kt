package com.velometrics.app.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBarScrimTest {

    @Test
    fun `scrim reaches one and a half status bar heights`() {
        assertEquals(36.dp, statusBarScrimHeight(24.dp))
    }

    @Test
    fun `scrim has no height without a status bar`() {
        assertEquals(0.dp, statusBarScrimHeight(0.dp))
    }

    @Test
    fun `scrim starts at 40 percent black`() {
        assertEquals(0.4f, STATUS_BAR_SCRIM_TOP_ALPHA)
    }
}
