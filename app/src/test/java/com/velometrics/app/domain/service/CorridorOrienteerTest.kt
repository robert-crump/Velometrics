package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CorridorOrienteerTest {

    // --- Quadrant classification end-to-end ---

    @Test
    fun `search classifies reachable corridors into four ordered quadrants`() = runTest {
        // With direction NORTH the ride axis u points north, so along=north, lateral=east:
        //   Q1 NE, Q2 NW, Q3 SW, Q4 SE.
        val results = CorridorOrienteer.search(
            quadrantFixture(),
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 9000.0,
            direction = RideDirection.NORTH,
        )

        val skeleton = results.single().corridors
        // Home corridor first, then one anchor per quadrant in order Q1->Q2->Q3->Q4.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), skeleton)
    }

    @Test
    fun `search picks the best-scoring anchor within a crowded quadrant`() = runTest {
        // Two corridors in the NE (Q1) quadrant; the farther-along one should win.
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val nearNe = corridor(id = 2, lat = 50.005, lon = 6.005)
        val farNe = corridor(id = 3, lat = 50.02, lon = 6.01)

        val results = CorridorOrienteer.search(
            listOf(home, nearNe, farNe),
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 12000.0,
            direction = RideDirection.NORTH,
        )

        val skeleton = results.single().corridors
        assertTrue("Farther NE corridor should be the Q1 anchor", skeleton.contains(3L))
        assertFalse("Nearer NE corridor should not win Q1", skeleton.contains(2L))
    }

    // --- Exit-plan start corridor honored as Q1 ---

    @Test
    fun `search uses the start corridor as the Q1 anchor`() = runTest {
        val results = CorridorOrienteer.search(
            quadrantFixture(),
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 9000.0,
            direction = RideDirection.NORTH,
            startCorridorId = 5L,
        )

        val skeleton = results.single().corridors
        assertEquals("Start corridor must be the first (Q1) anchor", 5L, skeleton.first())
        assertFalse("Home corridor is not prepended when starting from an exit corridor", skeleton.contains(1L))
        assertTrue(skeleton.size >= 2)
    }

    // --- Reach radius (target / 3) ---

    @Test
    fun `search excludes corridors beyond reach of target over three`() = runTest {
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val near = corridor(id = 2, lat = 50.01, lon = 6.0)   // ~1113m north, within 3000m reach
        val far = corridor(id = 3, lat = 50.05, lon = 6.0)    // ~5566m north, beyond 3000m reach

        val results = CorridorOrienteer.search(
            listOf(home, near, far),
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 9000.0, // reach = 3000m
            direction = RideDirection.NORTH,
        )

        val skeleton = results.single().corridors
        assertTrue(skeleton.contains(2L))
        assertFalse("Corridor beyond reach must be excluded", skeleton.contains(3L))
    }

    @Test
    fun `search is deterministic`() = runTest {
        val run1 = CorridorOrienteer.search(
            quadrantFixture(), homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 9000.0, direction = RideDirection.NORTH,
        )
        val run2 = CorridorOrienteer.search(
            quadrantFixture(), homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 9000.0, direction = RideDirection.NORTH,
        )
        assertEquals(run1.single().corridors, run2.single().corridors)
    }

    @Test
    fun `search returns empty for empty corridors`() = runTest {
        val results = CorridorOrienteer.search(
            emptyList(), homeLat = 50.0, homeLon = 6.0, targetDistanceM = 9000.0,
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search returns empty when fewer than two distinct corridors are reachable`() = runTest {
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val results = CorridorOrienteer.search(
            listOf(home), homeLat = 50.0, homeLon = 6.0, targetDistanceM = 9000.0,
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `candidate carries flow and discovery sub-scores`() = runTest {
        val results = CorridorOrienteer.search(
            quadrantFixture(), homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 9000.0, direction = RideDirection.NORTH,
        )
        val candidate = results.single()
        assertTrue(candidate.flowScore >= 0.0)
        assertTrue(candidate.discoveryScore >= 0.0)
        assertTrue(candidate.totalDistanceM > 0.0)
    }

    // --- Ride-aligned frame helpers ---

    @Test
    fun `toLocalMeters returns north offset for a point due north`() {
        val (east, north) = CorridorOrienteer.toLocalMeters(50.0, 6.0, 50.01, 6.0)
        assertEquals(0.0, east, 1e-6)
        assertEquals(1113.2, north, 1.0)
    }

    @Test
    fun `alongLateral aligns along with the ride bearing`() {
        // Bearing NORTH (0): along = north, lateral = east.
        val (along, lateral) = CorridorOrienteer.alongLateral(east = 500.0, north = 800.0, rideBearingDeg = 0.0)
        assertEquals(800.0, along, 1e-6)
        assertEquals(500.0, lateral, 1e-6)
    }

    @Test
    fun `alongLateral rotates with the ride bearing`() {
        // Bearing EAST (90): u points east, so a point due east is fully along.
        val (along, lateral) = CorridorOrienteer.alongLateral(east = 800.0, north = 0.0, rideBearingDeg = 90.0)
        assertEquals(800.0, along, 1e-6)
        assertEquals(0.0, lateral, 1e-6)
    }

    @Test
    fun `quadrantOf classifies by along and lateral signs`() {
        assertEquals(0, CorridorOrienteer.quadrantOf(along = 1.0, lateral = 1.0))   // Q1 front-right
        assertEquals(1, CorridorOrienteer.quadrantOf(along = 1.0, lateral = -1.0))  // Q2 front-left
        assertEquals(2, CorridorOrienteer.quadrantOf(along = -1.0, lateral = -1.0)) // Q3 back-left
        assertEquals(3, CorridorOrienteer.quadrantOf(along = -1.0, lateral = 1.0))  // Q4 back-right
    }

    @Test
    fun `arcTangentBearing returns the quadrant bisector`() {
        assertEquals(45.0, CorridorOrienteer.arcTangentBearing(0.0, 0), 1e-9)
        assertEquals(315.0, CorridorOrienteer.arcTangentBearing(0.0, 1), 1e-9)  // -45 wrapped
        assertEquals(225.0, CorridorOrienteer.arcTangentBearing(0.0, 2), 1e-9)  // -135 wrapped
        assertEquals(135.0, CorridorOrienteer.arcTangentBearing(0.0, 3), 1e-9)
    }

    @Test
    fun `cosineSimilarity is 1 for same bearing and minus 1 for opposite`() {
        assertEquals(1.0, CorridorOrienteer.cosineSimilarity(45.0, 45.0), 1e-9)
        assertEquals(-1.0, CorridorOrienteer.cosineSimilarity(0.0, 180.0), 1e-9)
        assertEquals(0.0, CorridorOrienteer.cosineSimilarity(90.0, 0.0), 1e-6)
    }

    @Test
    fun `anchorScore favors corridors farther along the ride axis`() {
        val config = OrienteerConfig(farWeight = 1.0, headingWeight = 0.0, rewardWeight = 0.0)
        val far = CorridorOrienteer.anchorScore(
            along = 3000.0, reach = 3000.0, corridorBearing = 0.0,
            rideBearingDeg = 0.0, quadrant = 0, rewardNorm = 0.0, config = config,
        )
        val near = CorridorOrienteer.anchorScore(
            along = 1500.0, reach = 3000.0, corridorBearing = 0.0,
            rideBearingDeg = 0.0, quadrant = 0, rewardNorm = 0.0, config = config,
        )
        assertEquals(1.0, far, 1e-9)
        assertEquals(0.5, near, 1e-9)
        assertTrue(far > near)
    }

    @Test
    fun `anchorScore uses reward only as a tiebreak`() {
        val config = OrienteerConfig(farWeight = 1.0, headingWeight = 0.0, rewardWeight = 0.1)
        val highReward = CorridorOrienteer.anchorScore(
            along = 1500.0, reach = 3000.0, corridorBearing = 0.0,
            rideBearingDeg = 0.0, quadrant = 0, rewardNorm = 1.0, config = config,
        )
        val lowReward = CorridorOrienteer.anchorScore(
            along = 1500.0, reach = 3000.0, corridorBearing = 0.0,
            rideBearingDeg = 0.0, quadrant = 0, rewardNorm = 0.0, config = config,
        )
        assertEquals(0.1, highReward - lowReward, 1e-9)
    }

    // --- Radius filtering ---

    @Test
    fun `filterByRadius excludes corridors beyond max radius`() {
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val near = corridor(id = 2, lat = 50.01, lon = 6.0)
        val far = corridor(id = 3, lat = 50.2, lon = 6.0)

        val filtered = CorridorOrienteer.filterByRadius(
            listOf(home, near, far), homeLat = 50.0, homeLon = 6.0, maxRadiusM = 5000.0,
        )

        assertTrue(filtered.any { it.id == 1L })
        assertTrue(filtered.any { it.id == 2L })
        assertFalse(filtered.any { it.id == 3L })
    }

    // --- findNearestCorridor ---

    @Test
    fun `findNearestCorridor picks closest by haversine`() {
        val near = corridor(id = 1, lat = 50.001, lon = 6.001)
        val far = corridor(id = 2, lat = 51.0, lon = 7.0)
        assertEquals(near.id, CorridorOrienteer.findNearestCorridor(listOf(near, far), 50.0, 6.0)?.id)
    }

    @Test
    fun `findNearestCorridor returns null for empty list`() {
        assertNull(CorridorOrienteer.findNearestCorridor(emptyList(), 50.0, 6.0))
    }

    // --- Helpers ---

    private fun corridor(
        id: Long = 1,
        lat: Double = 50.0,
        lon: Double = 6.0,
        pedalReward: Double = 3.0,
        gravityReward: Double = 2.0,
        lengthM: Double = 1000.0,
    ) = Corridor(
        id = id,
        entryNode = id * 10,
        exitNode = id * 10 + 1,
        lengthM = lengthM,
        pedalReward = pedalReward,
        gravityReward = gravityReward,
        exitHazardScore = 0.0,
        centroidLat = lat,
        centroidLon = lon,
        edgeList = emptyList(),
        popularity = 0,
        groupId = id,
    )

    /**
     * Home corridor at origin plus one corridor in each compass quadrant, all within reach.
     * With direction NORTH these map to Q1 (NE), Q2 (NW), Q3 (SW), Q4 (SE).
     */
    private fun quadrantFixture(): List<Corridor> = listOf(
        corridor(id = 1, lat = 50.0, lon = 6.0),     // home
        corridor(id = 2, lat = 50.01, lon = 6.01),   // NE -> Q1
        corridor(id = 3, lat = 50.01, lon = 5.99),   // NW -> Q2
        corridor(id = 4, lat = 49.99, lon = 5.99),   // SW -> Q3
        corridor(id = 5, lat = 49.99, lon = 6.01),   // SE -> Q4
    )
}
