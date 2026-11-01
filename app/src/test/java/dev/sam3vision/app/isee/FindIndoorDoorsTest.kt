package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lecture de distance (boîte en pixels, frame 100×100). */
private fun reading(label: String, meters: Float, x1: Float, x2: Float): DistanceReading =
    DistanceReading(
        trackId = 1, label = label, meters = meters, rawMeters = meters,
        box = listOf(x1, 0f, x2, 10f),
    )

class FindIndoorDoorsTest {

    @Test
    fun `la plus proche porte ou mur gagne avec son cadran`() {
        val cue = IndoorDoorWall.cueOf(
            listOf(
                reading("door", 3f, 60f, 80f),
                reading("wall", 1f, 60f, 80f),
            ),
            100, 100,
        )!!
        assertEquals(IndoorDoorWall.Kind.WALL, cue.kind)
        assertEquals(1, cue.clock)
        assertEquals("Wall at 1 o'clock.", cue.text)
        assertEquals("indoor_wall_1", cue.clipId)
    }

    @Test
    fun `porte a gauche en francais`() {
        val cue = IndoorDoorWall.cueOf(listOf(reading("porte", 2f, 0f, 20f)), 100, 100)!!
        assertEquals(IndoorDoorWall.Kind.DOOR, cue.kind)
        assertEquals(9, cue.clock)
        assertEquals("Door at 9 o'clock.", cue.text)
        assertEquals("indoor_door_9", cue.clipId)
    }

    @Test
    fun `ni porte ni mur ni constat`() {
        assertNull(IndoorDoorWall.cueOf(listOf(reading("chair", 1f, 5f, 25f)), 100, 100))
        assertNull(IndoorDoorWall.cueOf(emptyList(), 100, 100))
        assertNull(IndoorDoorWall.cueOf(listOf(reading("door", 1f, 5f, 25f)), 0, 100))
        val short = DistanceReading(trackId = 1, label = "door", meters = 1f, rawMeters = 1f, box = listOf(1f, 2f))
        assertNull(IndoorDoorWall.cueOf(listOf(short), 100, 100))
    }

    @Test
    fun `changement immediat meme cadran gate`() {
        val watcher = IndoorDoorWatcher()
        val wall = IndoorDoorWall.Cue(IndoorDoorWall.Kind.WALL, 1, "Wall at 1 o'clock.", "indoor_wall_1")
        assertEquals(wall, watcher.onCue(wall, 1_000L, { _, _ -> false }))
        assertNull(watcher.onCue(wall, 2_000L, { _, _ -> false }))
        assertEquals(wall, watcher.onCue(wall, 5_000L, { _, _ -> true }))
        val moved = wall.copy(clock = 3, text = "Wall at 3 o'clock.", clipId = "indoor_wall_3")
        assertEquals(moved, watcher.onCue(moved, 6_000L, { _, _ -> false }))
        val door = IndoorDoorWall.Cue(IndoorDoorWall.Kind.DOOR, 3, "Door at 3 o'clock.", "indoor_door_3")
        assertEquals(door, watcher.onCue(door, 6_000L, { _, _ -> false }))
    }

    @Test
    fun `plus rien oublie, le retour reparle aussitot`() {
        val watcher = IndoorDoorWatcher()
        val wall = IndoorDoorWall.Cue(IndoorDoorWall.Kind.WALL, 1, "Wall at 1 o'clock.", "indoor_wall_1")
        assertEquals(wall, watcher.onCue(wall, 1_000L, { _, _ -> false }))
        assertNull(watcher.onCue(null, 2_000L, { _, _ -> false }))
        assertEquals(wall, watcher.onCue(wall, 3_000L, { _, _ -> false }))
        watcher.reset()
        assertEquals(wall, watcher.onCue(wall, 4_000L, { _, _ -> false }))
    }
}
