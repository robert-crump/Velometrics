package com.velometrics.app.domain.service

import org.junit.Assert.*
import org.junit.Test

class LoopHullTest {

    // A ~2.1km x 2.2km rectangle, well above the ~1 km^2 degenerate-area floor.
    private val rectangle = listOf(
        50.0000 to 6.0000,
        50.0000 to 6.0300,
        50.0200 to 6.0300,
        50.0200 to 6.0000,
    )

    @Test
    fun `build returns null for fewer than 3 points`() {
        assertNull(LoopHull.build(emptyList()))
        assertNull(LoopHull.build(listOf(50.0 to 6.0)))
        assertNull(LoopHull.build(listOf(50.0 to 6.0, 50.001 to 6.001)))
    }

    @Test
    fun `build returns null for collinear points regardless of extent`() {
        val collinear = listOf(
            50.0000 to 6.0000,
            50.0100 to 6.0000,
            50.0200 to 6.0000,
            50.0300 to 6.0000,
        )
        assertNull(LoopHull.build(collinear))
    }

    @Test
    fun `build returns null for a hull under the 1 km^2 area floor`() {
        // A tiny ~14m square - real hull geometry, area far under the 1 km^2 floor.
        val tiny = listOf(
            50.00000 to 6.00000,
            50.00000 to 6.00020,
            50.00013 to 6.00020,
            50.00013 to 6.00000,
        )
        assertNull(LoopHull.build(tiny))
    }

    @Test
    fun `build returns a hull for a well-separated loop above the area floor`() {
        assertNotNull(LoopHull.build(rectangle))
    }

    @Test
    fun `distanceToRingM is zero on the boundary`() {
        val hull = LoopHull.build(rectangle)!!
        // Midpoint of the south edge (50.0000,6.0000)-(50.0000,6.0300).
        assertEquals(0.0, hull.distanceToRingM(50.0000, 6.0150), 1.0)
    }

    @Test
    fun `distanceToRingM grows toward the interior centroid`() {
        val hull = LoopHull.build(rectangle)!!
        val onRing = hull.distanceToRingM(50.0000, 6.0150)
        val centroid = hull.distanceToRingM(50.0100, 6.0150)
        assertTrue("Centroid should be farther from the ring than a boundary point", centroid > onRing)
        assertTrue("Centroid distance should be substantial for a ~2km rectangle", centroid > 900.0)
    }

    @Test
    fun `hullFactor is 1 exactly on the ring`() {
        val hull = LoopHull.build(rectangle)!!
        assertEquals(1.0, hull.hullFactor(50.0000, 6.0150), 1e-6)
    }

    @Test
    fun `hullFactor grows quadratically away from the ring and caps at 3 band multiples`() {
        val hull = LoopHull.build(rectangle)!!
        // Points due south of the rectangle's south edge at its midpoint longitude: distance-to-ring
        // there is exactly the southward offset (perpendicular distance to that edge).
        fun south(distanceM: Double) = (50.0000 - distanceM / 111_320.0) to 6.0150

        val onRing = hull.hullFactor(50.0000, 6.0150)
        val oneBand = south(hull.bandM).let { hull.hullFactor(it.first, it.second) }
        val threeBands = south(hull.bandM * 3).let { hull.hullFactor(it.first, it.second) }
        val wayBeyond = south(hull.bandM * 50).let { hull.hullFactor(it.first, it.second) }

        assertEquals(1.0, onRing, 1e-6)
        assertEquals(1.4, oneBand, 1e-3)
        assertEquals(4.6, threeBands, 1e-2)
        assertEquals("Factor must not grow past 3 band multiples", threeBands, wayBeyond, 1e-6)
    }

    @Test
    fun `convexHull collapses collinear input to fewer than 3 vertices`() {
        val collinear = listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        assertTrue(LoopHull.convexHull(collinear).size < 3)
    }

    @Test
    fun `polygonArea computes shoelace area of a unit square`() {
        val square = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
        assertEquals(1.0, LoopHull.polygonArea(square), 1e-9)
    }
}
