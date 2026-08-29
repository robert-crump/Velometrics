package com.velometrics.app.util

/** Graph algorithms shared by the domain layer's clustering services. */
object GraphUtils {

    /**
     * Connected components of an undirected graph on nodes `0 until n`, given as a bidirectional
     * adjacency list (`adjacency[i]` lists every node reachable from `i` in one hop; every edge
     * is expected to appear in both directions). Computed via BFS from each unvisited node in
     * ascending index order; components are returned in that discovery order.
     */
    fun connectedComponents(n: Int, adjacency: Array<out MutableList<Int>>): List<List<Int>> {
        val componentId = IntArray(n) { -1 }
        var nextComponent = 0
        for (start in 0 until n) {
            if (componentId[start] != -1) continue
            val queue = ArrayDeque<Int>()
            queue.add(start)
            componentId[start] = nextComponent
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for (neighbor in adjacency[node]) {
                    if (componentId[neighbor] == -1) {
                        componentId[neighbor] = nextComponent
                        queue.add(neighbor)
                    }
                }
            }
            nextComponent++
        }

        val components = Array(nextComponent) { mutableListOf<Int>() }
        for (i in 0 until n) components[componentId[i]].add(i)
        return components.map { it.toList() }
    }
}
