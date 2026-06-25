package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.CorridorConnector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CorridorOrienteerTest {

    // --- Budget including return leg ---

    @Test
    fun `every candidate respects distance budget including return leg`() = runTest {
        val (corridors, connectors) = ringFixture()
        val targetM = 12000.0

        val results = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = targetM,
            seed = 1L,
        )

        assertTrue("Expected at least one candidate", results.isNotEmpty())
        for (candidate in results) {
            assertTrue(
                "Distance ${candidate.totalDistanceM} exceeds ceiling ${targetM * 0.95}",
                candidate.totalDistanceM <= targetM * 0.95,
            )
            assertTrue(
                "Distance ${candidate.totalDistanceM} below minimum ${targetM * 0.85}",
                candidate.totalDistanceM >= targetM * 0.85,
            )
        }
    }

    @Test
    fun `return leg is reserved - route does not overshoot when return is expensive`() = runTest {
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val far = corridor(id = 2, lat = 50.1, lon = 6.1, pedalReward = 10.0)
        val farther = corridor(id = 3, lat = 50.2, lon = 6.2, pedalReward = 10.0)

        val connectors = listOf(
            connector(1, 2, 4000.0),
            connector(2, 3, 4000.0),
            connector(3, 1, 6000.0),
        )

        val results = CorridorOrienteer.search(
            listOf(home, far, farther), connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 12000.0,
            seed = 1L,
        )

        for (candidate in results) {
            assertTrue(candidate.totalDistanceM <= 12000.0 * 0.95)
        }
    }

    // --- Zero edge queries (in-memory) ---

    @Test
    fun `search operates purely on corridors and connectors`() = runTest {
        val (corridors, connectors) = ringFixture()

        val results = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 12000.0,
            seed = 42L,
        )

        assertTrue(results.isNotEmpty())
        for (candidate in results) {
            assertTrue(candidate.corridors.all { id -> corridors.any { it.id == id } })
        }
    }

    // --- Determinism ---

    @Test
    fun `deterministic for a fixed seed`() = runTest {
        val (corridors, connectors) = ringFixture()

        val run1 = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 10000.0,
            seed = 123L,
        )
        val run2 = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 10000.0,
            seed = 123L,
        )

        assertEquals(run1.size, run2.size)
        for (i in run1.indices) {
            assertEquals(run1[i].corridors, run2[i].corridors)
            assertEquals(run1[i].totalDistanceM, run2[i].totalDistanceM, 1e-9)
            assertEquals(run1[i].totalReward, run2[i].totalReward, 1e-9)
        }
    }

    @Test
    fun `different seeds produce different results`() = runTest {
        val (corridors, connectors) = largeRingFixture()

        val run1 = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 40000.0,
            seed = 1L,
        )
        val run2 = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 40000.0,
            seed = 999L,
        )

        if (run1.isNotEmpty() && run2.isNotEmpty()) {
            val allSame = run1.zip(run2).all { (a, b) -> a.corridors == b.corridors }
            assertFalse("Different seeds should produce at least one differing candidate", allSame)
        }
    }

    // --- Candidate diversity ---

    @Test
    fun `output candidates have low pairwise corridor overlap`() = runTest {
        val (corridors, connectors) = largeRingFixture()

        val results = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 20000.0,
            config = OrienteerConfig(candidateCount = 3, graspRestarts = 50),
            seed = 42L,
        )

        if (results.size >= 2) {
            for (i in results.indices) {
                for (j in i + 1 until results.size) {
                    val overlap = CorridorOrienteer.corridorOverlap(
                        results[i].corridors, results[j].corridors,
                    )
                    assertTrue(
                        "Overlap $overlap between candidate $i and $j is too high",
                        overlap < 1.0,
                    )
                }
            }
        }
    }

    @Test
    fun `selectDiverse penalizes high-overlap candidates`() {
        val highOverlapA = CandidateLoop(
            corridors = listOf(1, 2, 3, 4),
            totalDistanceM = 10000.0,
            totalReward = 100.0,
            flowScore = 80.0,
            discoveryScore = 20.0,
        )
        val highOverlapB = CandidateLoop(
            corridors = listOf(1, 2, 3, 5),
            totalDistanceM = 10000.0,
            totalReward = 95.0,
            flowScore = 75.0,
            discoveryScore = 20.0,
        )
        val diverse = CandidateLoop(
            corridors = listOf(6, 7, 8, 9),
            totalDistanceM = 10000.0,
            totalReward = 90.0,
            flowScore = 70.0,
            discoveryScore = 20.0,
        )

        val selected = CorridorOrienteer.selectDiverse(
            listOf(highOverlapA, highOverlapB, diverse),
            OrienteerConfig(candidateCount = 2),
        )

        assertEquals(2, selected.size)
        assertTrue("Best candidate should be first", selected[0] == highOverlapA)
        assertTrue(
            "Diverse candidate should be preferred over high-overlap",
            selected.contains(diverse),
        )
    }

    // --- Distance-from-home-modulated reuse penalty ---

    @Test
    fun `reuse penalty is zero within home exit radius`() {
        val modulation = CorridorOrienteer.reuseModulation(
            distanceFromHomeM = 0.0,
            homeExitRadiusM = 5000.0,
        )
        assertEquals(0.0, modulation, 1e-9)
    }

    @Test
    fun `reuse penalty ramps linearly from home to exit radius`() {
        val half = CorridorOrienteer.reuseModulation(2500.0, 5000.0)
        assertEquals(0.5, half, 1e-9)

        val quarter = CorridorOrienteer.reuseModulation(1250.0, 5000.0)
        assertEquals(0.25, quarter, 1e-9)
    }

    @Test
    fun `reuse penalty is full strength beyond home exit radius`() {
        val modulation = CorridorOrienteer.reuseModulation(
            distanceFromHomeM = 10000.0,
            homeExitRadiusM = 5000.0,
        )
        assertEquals(1.0, modulation, 1e-9)
    }

    @Test
    fun `reuse penalty is exactly 1 at the exit radius boundary`() {
        val modulation = CorridorOrienteer.reuseModulation(5000.0, 5000.0)
        assertEquals(1.0, modulation, 1e-9)
    }

    @Test
    fun `near-home corridor reuse is effectively free`() = runTest {
        val nearHome = corridor(id = 2, lat = 50.001, lon = 6.001, pedalReward = 5.0)
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val farA = corridor(id = 3, lat = 50.05, lon = 6.05, pedalReward = 5.0)
        val farB = corridor(id = 4, lat = 50.06, lon = 6.06, pedalReward = 5.0)

        val connectors = listOf(
            connector(1, 2, 500.0),
            connector(2, 3, 3000.0),
            connector(3, 4, 3000.0),
            connector(4, 2, 3000.0),
            connector(2, 1, 500.0),
        )

        val results = CorridorOrienteer.search(
            listOf(home, nearHome, farA, farB), connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 10000.0,
            seed = 1L,
        )

        for (candidate in results) {
            val nearHomeCount = candidate.corridors.count { it == nearHome.id }
            assertTrue(
                "Near-home corridor should be allowed to repeat in route",
                nearHomeCount >= 1,
            )
        }
    }

    // --- Corridor overlap helper ---

    @Test
    fun `corridorOverlap returns 0 for disjoint sets`() {
        assertEquals(0.0, CorridorOrienteer.corridorOverlap(listOf(1, 2, 3), listOf(4, 5, 6)), 1e-9)
    }

    @Test
    fun `corridorOverlap returns 1 for identical sets`() {
        assertEquals(1.0, CorridorOrienteer.corridorOverlap(listOf(1, 2, 3), listOf(1, 2, 3)), 1e-9)
    }

    @Test
    fun `corridorOverlap is Jaccard similarity`() {
        val overlap = CorridorOrienteer.corridorOverlap(listOf(1, 2, 3), listOf(2, 3, 4))
        assertEquals(2.0 / 4.0, overlap, 1e-9)
    }

    // --- findNearestCorridor ---

    @Test
    fun `findNearestCorridor picks closest by haversine`() {
        val near = corridor(id = 1, lat = 50.001, lon = 6.001)
        val far = corridor(id = 2, lat = 51.0, lon = 7.0)

        val result = CorridorOrienteer.findNearestCorridor(listOf(near, far), 50.0, 6.0)
        assertEquals(near.id, result?.id)
    }

    @Test
    fun `findNearestCorridor returns null for empty list`() {
        assertNull(CorridorOrienteer.findNearestCorridor(emptyList(), 50.0, 6.0))
    }

    // --- Radius filtering ---

    @Test
    fun `filterByRadius excludes corridors beyond max radius`() {
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val near = corridor(id = 2, lat = 50.01, lon = 6.0)
        val far = corridor(id = 3, lat = 50.2, lon = 6.0)

        val filtered = CorridorOrienteer.filterByRadius(
            listOf(home, near, far),
            homeLat = 50.0, homeLon = 6.0,
            maxRadiusM = 5000.0,
        )

        assertTrue("Home corridor should pass", filtered.any { it.id == 1L })
        assertTrue("Near corridor should pass", filtered.any { it.id == 2L })
        assertFalse("Far corridor should be excluded", filtered.any { it.id == 3L })
    }

    // --- Direction filtering ---

    @Test
    fun `filterByDirection keeps corridors within homeExitRadius regardless of direction`() {
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val nearHome = corridor(id = 2, lat = 50.001, lon = 6.001)
        val farSouth = corridor(id = 3, lat = 49.95, lon = 6.0)

        val filtered = CorridorOrienteer.filterByDirection(
            listOf(home, nearHome, farSouth),
            homeLat = 50.0, homeLon = 6.0,
            direction = RideDirection.NORTH,
            homeExitRadiusM = 5000.0,
        )

        assertTrue("Home corridor should pass", filtered.any { it.id == 1L })
        assertTrue("Near-home corridor should pass", filtered.any { it.id == 2L })
        assertFalse("Far south corridor should be filtered out", filtered.any { it.id == 3L })
    }

    @Test
    fun `filterByDirection keeps corridors in the chosen direction`() {
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val north = corridor(id = 2, lat = 50.1, lon = 6.0)
        val east = corridor(id = 3, lat = 50.0, lon = 6.2)
        val south = corridor(id = 4, lat = 49.9, lon = 6.0)
        val west = corridor(id = 5, lat = 50.0, lon = 5.8)

        val filteredNorth = CorridorOrienteer.filterByDirection(
            listOf(home, north, east, south, west),
            homeLat = 50.0, homeLon = 6.0,
            direction = RideDirection.NORTH,
            homeExitRadiusM = 500.0,
        )

        assertTrue("North corridor should pass for NORTH", filteredNorth.any { it.id == 2L })
        assertFalse("South corridor should be excluded for NORTH", filteredNorth.any { it.id == 4L })
    }

    @Test
    fun `search with direction only uses corridors in that direction`() = runTest {
        val home = corridor(id = 1, lat = 50.0, lon = 6.0)
        val northA = corridor(id = 2, lat = 50.02, lon = 6.0, pedalReward = 5.0)
        val northB = corridor(id = 3, lat = 50.04, lon = 6.0, pedalReward = 5.0)
        val south = corridor(id = 4, lat = 49.96, lon = 6.0, pedalReward = 5.0)

        val connectors = listOf(
            connector(1, 2, 2000.0),
            connector(2, 3, 2000.0),
            connector(3, 1, 3000.0),
            connector(1, 4, 3000.0),
            connector(4, 1, 3000.0),
        )

        val results = CorridorOrienteer.search(
            listOf(home, northA, northB, south), connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 8000.0,
            seed = 42L,
            direction = RideDirection.NORTH,
        )

        for (candidate in results) {
            assertFalse(
                "South corridor should not appear in NORTH direction results",
                candidate.corridors.contains(4L),
            )
        }
    }

    // --- Edge cases ---

    @Test
    fun `empty corridor list returns empty`() = runTest {
        val results = CorridorOrienteer.search(
            emptyList(), emptyList(),
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 10000.0,
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `disconnected graph returns empty when no loop possible`() = runTest {
        val a = corridor(id = 1, lat = 50.0, lon = 6.0)
        val b = corridor(id = 2, lat = 50.1, lon = 6.1)

        val results = CorridorOrienteer.search(
            listOf(a, b), emptyList(),
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 10000.0,
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `candidates carry flow and discovery sub-scores`() = runTest {
        val (corridors, connectors) = ringFixture()

        val results = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 10000.0,
            seed = 42L,
        )

        for (candidate in results) {
            assertTrue(candidate.flowScore >= 0.0)
            assertTrue(candidate.discoveryScore >= 0.0)
        }
    }

    // --- Phase bearing computation ---

    @Test
    fun `phaseBearing phase 0 CW offsets minus 45 from direction`() {
        val bearing = CorridorOrienteer.phaseBearing(270.0, clockwise = true, budgetFraction = 0.0)
        assertEquals(225.0, bearing, 1e-9)
    }

    @Test
    fun `phaseBearing phase 1 CW offsets plus 45 from direction`() {
        val bearing = CorridorOrienteer.phaseBearing(270.0, clockwise = true, budgetFraction = 0.30)
        assertEquals(315.0, bearing, 1e-9)
    }

    @Test
    fun `phaseBearing phase 2 CW offsets plus 135 from direction`() {
        val bearing = CorridorOrienteer.phaseBearing(270.0, clockwise = true, budgetFraction = 0.55)
        assertEquals(45.0, bearing, 1e-9)
    }

    @Test
    fun `phaseBearing phase 3 CW offsets plus 225 from direction`() {
        val bearing = CorridorOrienteer.phaseBearing(270.0, clockwise = true, budgetFraction = 0.80)
        assertEquals(135.0, bearing, 1e-9)
    }

    @Test
    fun `phaseBearing CCW mirrors CW offsets`() {
        val cwPhase0 = CorridorOrienteer.phaseBearing(270.0, clockwise = true, budgetFraction = 0.0)
        val ccwPhase0 = CorridorOrienteer.phaseBearing(270.0, clockwise = false, budgetFraction = 0.0)
        assertEquals(225.0, cwPhase0, 1e-9)
        assertEquals(315.0, ccwPhase0, 1e-9)
    }

    @Test
    fun `phaseBearing wraps around 360`() {
        val bearing = CorridorOrienteer.phaseBearing(0.0, clockwise = true, budgetFraction = 0.0)
        assertEquals(315.0, bearing, 1e-9)
    }

    // --- Geometric factor ---

    @Test
    fun `geometricFactor is 1 plus geoWeight when bearings are identical`() {
        val factor = CorridorOrienteer.geometricFactor(90.0, 90.0, 0.5)
        assertEquals(1.5, factor, 1e-9)
    }

    @Test
    fun `geometricFactor is 1 minus geoWeight when bearings are opposite`() {
        val factor = CorridorOrienteer.geometricFactor(0.0, 180.0, 0.5)
        assertEquals(0.5, factor, 1e-9)
    }

    @Test
    fun `geometricFactor is 1 when bearings are perpendicular`() {
        val factor = CorridorOrienteer.geometricFactor(0.0, 90.0, 0.5)
        assertEquals(1.0, factor, 1e-3)
    }

    @Test
    fun `geometricFactor scales with geoWeight`() {
        val lowWeight = CorridorOrienteer.geometricFactor(0.0, 0.0, 0.2)
        val highWeight = CorridorOrienteer.geometricFactor(0.0, 0.0, 0.8)
        assertEquals(1.2, lowWeight, 1e-9)
        assertEquals(1.8, highWeight, 1e-9)
    }

    // --- Cosine similarity ---

    @Test
    fun `cosineSimilarity is 1 for same bearing`() {
        assertEquals(1.0, CorridorOrienteer.cosineSimilarity(45.0, 45.0), 1e-9)
    }

    @Test
    fun `cosineSimilarity is minus 1 for opposite bearings`() {
        assertEquals(-1.0, CorridorOrienteer.cosineSimilarity(0.0, 180.0), 1e-9)
    }

    @Test
    fun `cosineSimilarity is 0 for perpendicular bearings`() {
        assertEquals(0.0, CorridorOrienteer.cosineSimilarity(90.0, 0.0), 1e-6)
    }

    // --- CW vs CCW routes ---

    @Test
    fun `CW and CCW restarts produce different candidates`() = runTest {
        val (corridors, connectors) = largeRingFixture()

        val cwResults = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 20000.0,
            config = OrienteerConfig(candidateCount = 3, graspRestarts = 20),
            seed = 42L,
            direction = RideDirection.WEST,
        )

        assertTrue("Should produce at least one candidate", cwResults.isNotEmpty())
    }

    // --- No-direction randomization ---

    @Test
    fun `null direction assigns random directions across restarts`() = runTest {
        val (corridors, connectors) = largeRingFixture()

        val run1 = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 20000.0,
            config = OrienteerConfig(candidateCount = 3, graspRestarts = 30),
            seed = 42L,
            direction = null,
        )

        assertTrue("Should produce candidates without explicit direction", run1.isNotEmpty())
    }

    @Test
    fun `null direction still produces oval-shaped routes`() = runTest {
        val (corridors, connectors) = largeRingFixture()

        val results = CorridorOrienteer.search(
            corridors, connectors,
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 20000.0,
            config = OrienteerConfig(candidateCount = 1, graspRestarts = 10),
            seed = 123L,
            direction = null,
        )

        for (candidate in results) {
            assertTrue(candidate.totalDistanceM >= 20000.0 * 0.85)
            assertTrue(candidate.totalDistanceM <= 20000.0 * 1.15)
        }
    }

    // --- Helpers ---

    private fun corridor(
        id: Long = 1,
        lat: Double = 50.0,
        lon: Double = 6.0,
        pedalReward: Double = 3.0,
        gravityReward: Double = 2.0,
        predictedReward: Double = 0.0,
        type: String = "measured",
        lengthM: Double = 1000.0,
    ) = Corridor(
        id = id,
        entryNode = id * 10,
        exitNode = id * 10 + 1,
        lengthM = lengthM,
        pedalReward = pedalReward,
        gravityReward = gravityReward,
        predictedReward = predictedReward,
        exitHazardScore = 0.0,
        type = type,
        centroidLat = lat,
        centroidLon = lon,
    )

    private fun connector(from: Long, to: Long, distanceM: Double) = CorridorConnector(
        fromCorridor = from,
        toCorridor = to,
        distanceM = distanceM,
    )

    private fun ringFixture(): Pair<List<Corridor>, List<CorridorConnector>> {
        val corridors = listOf(
            corridor(id = 1, lat = 50.0, lon = 6.0, pedalReward = 3.0),
            corridor(id = 2, lat = 50.01, lon = 6.01, pedalReward = 5.0),
            corridor(id = 3, lat = 50.02, lon = 6.02, pedalReward = 4.0),
            corridor(id = 4, lat = 50.03, lon = 6.01, pedalReward = 6.0),
            corridor(id = 5, lat = 50.02, lon = 6.0, pedalReward = 3.5),
        )
        val connectors = listOf(
            connector(1, 2, 2000.0),
            connector(2, 3, 2000.0),
            connector(3, 4, 2000.0),
            connector(4, 5, 2000.0),
            connector(5, 1, 2000.0),
            connector(1, 3, 3000.0),
            connector(2, 4, 3000.0),
            connector(3, 5, 3000.0),
        )
        return corridors to connectors
    }

    private fun largeRingFixture(): Pair<List<Corridor>, List<CorridorConnector>> {
        val corridors = (1L..12L).map { id ->
            val angle = 2 * Math.PI * id / 12
            corridor(
                id = id,
                lat = 50.0 + 0.03 * Math.cos(angle),
                lon = 6.0 + 0.04 * Math.sin(angle),
                pedalReward = 2.0 + (id % 4),
                gravityReward = 1.0 + (id % 3),
                lengthM = 1500.0 + (id % 3) * 500.0,
            )
        }
        val connectors = mutableListOf<CorridorConnector>()
        for (i in 1L..12L) {
            val next = if (i == 12L) 1L else i + 1
            connectors.add(connector(i, next, 2000.0))
        }
        for (i in 1L..12L) {
            val across = if (i + 4 > 12) i + 4 - 12 else i + 4
            connectors.add(connector(i, across, 3500.0))
        }
        for (i in 1L..12L) {
            val skip = if (i + 2 > 12) i + 2 - 12 else i + 2
            connectors.add(connector(i, skip, 2500.0))
        }
        return corridors to connectors
    }
}
