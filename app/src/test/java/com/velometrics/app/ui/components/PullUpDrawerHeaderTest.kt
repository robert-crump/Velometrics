package com.velometrics.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullUpDrawerHeaderTest {

    @Test
    fun `docked controls are hidden below the fade window`() {
        assertEquals(0f, dockedHeaderAlpha(0.5f, coverFraction = 0.93f), 0f)
        assertEquals(0f, dockedHeaderAlpha(0.95f, coverFraction = 0.93f), 0f)
    }

    @Test
    fun `docked controls fade in over the last 5 percent and are opaque at full height`() {
        assertEquals(0.5f, dockedHeaderAlpha(0.975f, coverFraction = 0.93f), 1e-4f)
        assertEquals(1f, dockedHeaderAlpha(1f, coverFraction = 0.93f), 0f)
    }

    @Test
    fun `fade waits until the sheet covers the floating controls`() {
        // Floating controls sit lower than 5 % from the top: the sheet covers them only at 97 %.
        assertEquals(0.97f, dockedHeaderFadeStart(coverFraction = 0.97f), 1e-6f)
        assertEquals(0f, dockedHeaderAlpha(0.96f, coverFraction = 0.97f), 0f)
        assertEquals(0.5f, dockedHeaderAlpha(0.985f, coverFraction = 0.97f), 1e-4f)
    }

    @Test
    fun `fade window never collapses to zero width`() {
        val start = dockedHeaderFadeStart(coverFraction = 1f)
        assertTrue(start < 1f)
        assertEquals(1f, dockedHeaderAlpha(1f, coverFraction = 1f), 0f)
    }

    @Test
    fun `floating controls are composed exactly while the docked ones are hidden`() {
        val cover = 0.93f
        val start = dockedHeaderFadeStart(cover)
        // Below the fade start the floating ones show and docked alpha is 0; above it docked is > 0.
        assertEquals(0f, dockedHeaderAlpha(start - 0.001f, cover), 0f)
        assertTrue(dockedHeaderAlpha(start + 0.001f, cover) > 0f)
    }
}
