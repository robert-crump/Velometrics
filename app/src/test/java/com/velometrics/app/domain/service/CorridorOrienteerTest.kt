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
        // The nearer corridor loses the Q1 anchor to the farther one, but with a 12km target the
        // anchor-only skeleton falls short, so fill inserts it into the home->anchor gap. Its
        // position (before the anchor it fills toward) confirms it was a fill, not the anchor.
        assertTrue("Nearer NE corridor is added only as fill", skeleton.contains(2L))
        assertTrue("Fill sits before the anchor it reaches toward", skeleton.indexOf(2L) < skeleton.indexOf(3L))
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

    // --- Fill toward target length ---

    @Test
    fun `fill raises the skeleton length into the target band`() = runTest {
        // Home + a far Q1 anchor whose out-and-back falls short of the 0.9x band; a chain of Q1
        // corridors between home and the anchor is available as fill.
        val corridors = listOf(
            corridor(id = 1, lat = 50.0, lon = 6.0),
            corridor(id = 2, lat = 50.06, lon = 6.0),   // farthest north -> Q1 anchor
            corridor(id = 3, lat = 50.01, lon = 6.0),
            corridor(id = 4, lat = 50.02, lon = 6.0),
            corridor(id = 5, lat = 50.03, lon = 6.0),
            corridor(id = 6, lat = 50.04, lon = 6.0),
            corridor(id = 7, lat = 50.05, lon = 6.0),
        )

        val result = CorridorOrienteer.search(
            corridors, homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 24000.0, // reach = 8000m, band floor = 21600m
            direction = RideDirection.NORTH,
        ).single()

        assertTrue("Fill must add corridors beyond the anchor", result.corridors.size > 2)
        assertTrue(
            "Filled length must reach the 0.9x band floor (was ${result.totalDistanceM})",
            result.totalDistanceM >= 24000.0 * CorridorOrienteer.FILL_TARGET_FRACTION,
        )
    }

    @Test
    fun `fill picks the higher-reward candidate and separation blocks its near twin`() = runTest {
        // Q1 anchor id=2. Two fill candidates between home and the anchor: id=3 carries top reward,
        // id=4 is the default-reward near-twin ~65m east of id=3. The largest gap fills with id=3
        // (higher reward); id=4 is then rejected by the 2km separation rule against id=3.
        val corridors = listOf(
            corridor(id = 1, lat = 50.0, lon = 6.0),
            corridor(id = 2, lat = 50.05, lon = 6.0),                                  // Q1 anchor
            corridor(id = 3, lat = 50.025, lon = 6.0, pedalReward = 30.0, gravityReward = 30.0),
            corridor(id = 4, lat = 50.025, lon = 6.0009),                              // near-twin of 3
        )
        val coords = mapOf(
            30L to (50.020 to 6.0), 31L to (50.030 to 6.0),         // id=3 entry/exit, heading north
            40L to (50.020 to 6.0009), 41L to (50.030 to 6.0009),  // id=4 ~65m east of id=3
        )

        val skeleton = CorridorOrienteer.search(
            corridors, homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 30000.0, // reach = 10km, sep = 2km
            direction = RideDirection.NORTH,
            nodeResolver = { ids -> coords.filterKeys { it in ids } },
        ).single().corridors

        assertTrue("Higher-reward fill chosen", skeleton.contains(3L))
        assertFalse("Near-twin rejected by separation", skeleton.contains(4L))
    }

    @Test
    fun `fill terminates when no valid candidate remains`() = runTest {
        // The only fill candidate (id=3) sits within 2km of the anchor it would fill toward, so the
        // separation rule rejects it and the fill loop terminates with the bare anchor skeleton.
        val corridors = listOf(
            corridor(id = 1, lat = 50.0, lon = 6.0),
            corridor(id = 2, lat = 50.05, lon = 6.0),     // Q1 anchor
            corridor(id = 3, lat = 50.045, lon = 6.0),    // between home and anchor, but hugs it
        )
        val coords = mapOf(
            20L to (50.05 to 6.0), 21L to (50.051 to 6.0),
            30L to (50.045 to 6.0), 31L to (50.046 to 6.0), // id=3 ~556m from the anchor
        )

        val skeleton = CorridorOrienteer.search(
            corridors, homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 30000.0,
            direction = RideDirection.NORTH,
            nodeResolver = { ids -> coords.filterKeys { it in ids } },
        ).single().corridors

        assertEquals("No valid fill -> bare anchor skeleton", listOf(1L, 2L), skeleton)
    }

    @Test
    fun `connectorEstimate is haversine times the road factor`() {
        val a = corridor(id = 1, lat = 50.0, lon = 6.0)        // exit node 11
        val b = corridor(id = 2, lat = 50.0, lon = 6.0)        // entry node 20
        val coords = mapOf(11L to (50.0 to 6.0), 20L to (50.01 to 6.0)) // ~1113m apart
        val est = CorridorOrienteer.connectorEstimate(a, b, coords)
        assertEquals(1113.2 * 1.3, est, 5.0)
    }

    @Test
    fun `connectorEstimate falls back to centroids when nodes unresolved`() {
        val a = corridor(id = 1, lat = 50.0, lon = 6.0)
        val b = corridor(id = 2, lat = 50.01, lon = 6.0) // ~1113m north
        val est = CorridorOrienteer.connectorEstimate(a, b, emptyMap())
        assertEquals(1113.2 * 1.3, est, 5.0)
    }

    @Test
    fun `isBetween accepts a mid corridor and rejects one beyond the endpoints`() {
        val before = corridor(id = 1, lat = 50.0, lon = 6.0)
        val after = corridor(id = 2, lat = 50.04, lon = 6.0)
        val mid = corridor(id = 3, lat = 50.02, lon = 6.0)
        val beyond = corridor(id = 4, lat = 49.99, lon = 6.0) // south of `before`
        assertTrue(CorridorOrienteer.isBetween(mid, before, after))
        assertFalse(CorridorOrienteer.isBetween(beyond, before, after))
    }

    @Test
    fun `headingConsistent rejects a corridor traversed against travel`() {
        val before = corridor(id = 1, lat = 50.0, lon = 6.0)
        val after = corridor(id = 2, lat = 50.04, lon = 6.0) // travel heads north
        val c = corridor(id = 3, lat = 50.02, lon = 6.0)     // entry 30, exit 31
        val northbound = mapOf(30L to (50.015 to 6.0), 31L to (50.025 to 6.0))
        val southbound = mapOf(30L to (50.025 to 6.0), 31L to (50.015 to 6.0))
        assertTrue(CorridorOrienteer.headingConsistent(c, before, after, northbound))
        assertFalse(CorridorOrienteer.headingConsistent(c, before, after, southbound))
    }

    // --- 2km any-node separation rule ---

    @Test
    fun `search rejects an anchor candidate within separation of a chosen anchor`() = runTest {
        // Q1 anchor id=2 (NE). Q2 has two candidates: id=3 scores highest (max reward) but has a
        // node ~70m from the Q1 anchor, so the separation rule must reject it in favour of the
        // well-separated id=4. id=3 is a long corridor, so the centroid prefilter cannot clear it.
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val q1 = corridor(id = 2, lat = 50.05, lon = 6.05)                       // NE -> Q1
        val q2Overlap = corridor(
            id = 3, lat = 50.05, lon = 5.95,                                     // NW -> Q2
            pedalReward = 20.0, gravityReward = 20.0, lengthM = 12000.0,         // top reward, long
        )
        val q2Clean = corridor(id = 4, lat = 50.05, lon = 5.96)                  // NW -> Q2

        val coords = mapOf(
            10L to (50.0 to 6.0), 11L to (50.001 to 6.0),
            20L to (50.05 to 6.05), 21L to (50.051 to 6.05),
            30L to (50.05 to 5.95), 31L to (50.05 to 6.049),  // id=3 exit node hugs the Q1 anchor
            40L to (50.05 to 5.96), 41L to (50.051 to 5.96),
        )

        val results = CorridorOrienteer.search(
            listOf(home, q1, q2Overlap, q2Clean),
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 30000.0, // reach = 10km, sep = 2km
            direction = RideDirection.NORTH,
            nodeResolver = { ids -> coords.filterKeys { it in ids } },
        )

        val skeleton = results.single().corridors
        assertTrue("Q1 anchor present", skeleton.contains(2L))
        assertFalse("Overlapping high-reward Q2 candidate must be rejected", skeleton.contains(3L))
        assertTrue("Well-separated Q2 candidate chosen instead", skeleton.contains(4L))
    }

    @Test
    fun `corridorsSeparated rejects overlapping parallel corridors`() {
        val a = corridor(id = 1, lat = 50.0, lon = 6.0, lengthM = 1000.0)      // nodes 10, 11
        val b = corridor(id = 2, lat = 50.0, lon = 6.0003, lengthM = 1000.0)   // nodes 20, 21
        val coords = mapOf(
            10L to (50.0 to 6.0), 11L to (50.005 to 6.0),
            20L to (50.0 to 6.0003), 21L to (50.005 to 6.0003), // ~21m east of a's nodes
        )
        assertFalse(CorridorOrienteer.corridorsSeparated(a, b, sep = 2000.0, nodeCoords = coords))
    }

    @Test
    fun `corridorsSeparated accepts well-separated corridors`() {
        val a = corridor(id = 1, lat = 50.0, lon = 6.0, lengthM = 1000.0)
        val b = corridor(id = 2, lat = 50.1, lon = 6.0, lengthM = 1000.0) // ~11km north
        val coords = mapOf(
            10L to (50.0 to 6.0), 11L to (50.005 to 6.0),
            20L to (50.1 to 6.0), 21L to (50.105 to 6.0),
        )
        assertTrue(CorridorOrienteer.corridorsSeparated(a, b, sep = 2000.0, nodeCoords = coords))
    }

    @Test
    fun `corridorsSeparated prefilter short-circuits far pairs without node coords`() {
        val a = corridor(id = 1, lat = 50.0, lon = 6.0, lengthM = 1000.0)
        val b = corridor(id = 2, lat = 50.1, lon = 6.0, lengthM = 1000.0) // ~11km north
        // No node coordinates supplied: the centroid+half-length prefilter alone proves separation.
        assertTrue(CorridorOrienteer.corridorsSeparated(a, b, sep = 2000.0, nodeCoords = emptyMap()))
    }

    @Test
    fun `corridorsSeparated treats unresolvable near pairs as separated`() {
        val a = corridor(id = 1, lat = 50.0, lon = 6.0, lengthM = 1000.0)
        val b = corridor(id = 2, lat = 50.0, lon = 6.0003, lengthM = 1000.0) // close centroids
        // Near pair survives the prefilter but no coordinates exist: overlap cannot be proven.
        assertTrue(CorridorOrienteer.corridorsSeparated(a, b, sep = 2000.0, nodeCoords = emptyMap()))
    }

    @Test
    fun `separationDistance is adaptive on short reach`() {
        assertEquals(2000.0, CorridorOrienteer.separationDistance(10000.0), 1e-9) // min(2km, 5km)
        assertEquals(1000.0, CorridorOrienteer.separationDistance(2000.0), 1e-9)  // min(2km, 1km)
    }

    @Test
    fun `corridorNodeIds collects entry exit and edge nodes`() {
        val c = com.velometrics.app.domain.model.Corridor(
            id = 1, entryNode = 7, exitNode = 8, lengthM = 100.0,
            pedalReward = 0.0, gravityReward = 0.0, exitHazardScore = 0.0,
            centroidLat = 50.0, centroidLon = 6.0,
            edgeList = listOf(1L to 2L, 2L to 3L), popularity = 0, groupId = 1,
        )
        assertEquals(setOf(7L, 8L, 1L, 2L, 3L), CorridorOrienteer.corridorNodeIds(c))
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
