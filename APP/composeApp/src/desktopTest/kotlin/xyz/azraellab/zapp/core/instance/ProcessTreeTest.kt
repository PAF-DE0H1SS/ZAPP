package xyz.azraellab.zapp.core.instance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessTreeTest {

    /** Настоящий дочерний процесс: дерево из sh + sleep, как пара box-демонов. */
    private fun spawnTree(): Process =
        ProcessBuilder("sh", "-c", "sleep 60 & sleep 60").start()

    @Test
    fun killsRootAndDescendants() {
        val proc = spawnTree()
        val root = proc.toHandle()
        killProcessTree(root, graceMs = 200)
        assertFalse(root.isAlive, "корневой процесс должен умереть")
        assertTrue(root.descendants().toList().none { it.isAlive }, "потомки обязаны умереть")
    }

    @Test
    fun includeRootFalseLeavesRootAlive() {
        // После гибели детей sh переходит в exec sleep: корень обязан
        // пережить потерю потомков.
        val proc = ProcessBuilder("sh", "-c", "sleep 60 & sleep 60; exec sleep 60").start()
        try {
            val root = proc.toHandle()
            killProcessTree(root, graceMs = 200, includeRoot = false)
            assertTrue(root.isAlive, "без includeRoot корень жив")
            assertTrue(root.descendants().toList().none { it.isAlive }, "а потомки умерли")
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun stubbornChildGetsKilled() {
        // Игнорирует TERM: остаётся только KILL.
        val proc = ProcessBuilder("sh", "-c", "trap '' TERM; while :; do sleep 1; done").start()
        killProcessTree(proc.toHandle(), graceMs = 100)
        assertTrue(
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS),
            "упрямый процесс должен умереть по KILL"
        )
    }
}
