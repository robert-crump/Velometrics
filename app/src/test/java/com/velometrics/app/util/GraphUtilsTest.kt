package com.velometrics.app.util

import org.junit.Assert.*
import org.junit.Test

class GraphUtilsTest {

    @Test
    fun `empty graph returns no components`() {
        val result = GraphUtils.connectedComponents(0, arrayOf<MutableList<Int>>())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single node with no edges is its own component`() {
        val adjacency = arrayOf(mutableListOf<Int>())
        val result = GraphUtils.connectedComponents(1, adjacency)
        assertEquals(listOf(listOf(0)), result)
    }

    @Test
    fun `fully connected graph is one component`() {
        // triangle: 0-1, 1-2, 0-2
        val adjacency = arrayOf(
            mutableListOf(1, 2),
            mutableListOf(0, 2),
            mutableListOf(0, 1)
        )
        val result = GraphUtils.connectedComponents(3, adjacency)
        assertEquals(1, result.size)
        assertEquals(setOf(0, 1, 2), result[0].toSet())
    }

    @Test
    fun `disconnected nodes form separate components`() {
        // 0-1 edge; 2 and 3 isolated
        val adjacency = arrayOf(
            mutableListOf(1),
            mutableListOf(0),
            mutableListOf<Int>(),
            mutableListOf<Int>()
        )
        val result = GraphUtils.connectedComponents(4, adjacency)
        val componentSets = result.map { it.toSet() }.toSet()
        assertEquals(setOf(setOf(0, 1), setOf(2), setOf(3)), componentSets)
    }

    @Test
    fun `two clusters of different sizes are kept separate`() {
        // cluster A: 0-1-2 chain, cluster B: 3-4
        val adjacency = arrayOf(
            mutableListOf(1),
            mutableListOf(0, 2),
            mutableListOf(1),
            mutableListOf(4),
            mutableListOf(3)
        )
        val result = GraphUtils.connectedComponents(5, adjacency)
        val componentSets = result.map { it.toSet() }.toSet()
        assertEquals(setOf(setOf(0, 1, 2), setOf(3, 4)), componentSets)
    }

    @Test
    fun `duplicate edges and self-loops do not affect grouping`() {
        // 0-1 edge duplicated, plus a self-loop on 0; node 2 isolated
        val adjacency = arrayOf(
            mutableListOf(1, 1, 0),
            mutableListOf(0),
            mutableListOf<Int>()
        )
        val result = GraphUtils.connectedComponents(3, adjacency)
        val componentSets = result.map { it.toSet() }.toSet()
        assertEquals(setOf(setOf(0, 1), setOf(2)), componentSets)
    }

    @Test
    fun `every node appears exactly once across all components`() {
        val adjacency = arrayOf(
            mutableListOf(1),
            mutableListOf(0, 2),
            mutableListOf(1),
            mutableListOf<Int>(),
            mutableListOf(5),
            mutableListOf(4)
        )
        val result = GraphUtils.connectedComponents(6, adjacency)
        val allNodes = result.flatten()
        assertEquals(6, allNodes.size)
        assertEquals((0 until 6).toSet(), allNodes.toSet())
    }
}
