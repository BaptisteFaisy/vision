package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.crossing.CrossingPost
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingVerdict
import dev.sam3vision.core.model.LiveFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Niveau du verdict sur le passage piéton : hors passage le verdict parle à
 * 100 ; sur le passage, un verdict changé coupe à 99 (front, rouge comme
 * vert — le premier verdict compte comme changé : jamais entendu, il doit
 * couper), un verdict maintenu recule à 101 (l'évitement des occupants, 100,
 * domine la répétition). Le veto trafic fait changer dans les 2 sens.
 */
class CrossingVerdictOnPathTest {

    private fun frame(): LiveFrame = LiveFrame(640, 480, 0L, emptyList())

    private fun engine(
        light: CrossingLight,
        posted: MutableList<CrossingPost> = mutableListOf(),
    ): CrossingVerdictEngine = CrossingVerdictEngine(
        readLight = { _, _ -> light },
        poster = CrossingVerdictPoster { level, text, clipId ->
            posted.add(CrossingPost(level, text, clipId))
        },
    )

    /** Chauffe le vote vert (3 preuves sur 5) sans rien prononcer. */
    private fun CrossingVerdictEngine.warm(count: Int) {
        repeat(count) { onFrame(frame(), emptyList(), "", -1_000L + it * 100L) }
    }

    @Test
    fun `front rouge sur passage = 99`() {
        val posted = mutableListOf<CrossingPost>()
        val red = CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Red)
        val out = engine(red, posted).onFrame(frame(), emptyList(), "", 0L, onCrosswalk = true)

        assertEquals(CrossingVerdict.DontCross, out.verdict)
        assertEquals(CrossingLevels.ALERT, out.posts.single().level)
        assertEquals(listOf(99), posted.map { it.level })
        assertEquals(CrossingState.Red, out.haptic)
    }

    @Test
    fun `repetition sur passage = 101, hors passage = 100`() {
        val red = CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Red)
        val onPath = engine(red)
        onPath.onFrame(frame(), emptyList(), "", 0L, onCrosswalk = true)
        val repeat = onPath.onFrame(frame(), emptyList(), "", 2_000L, onCrosswalk = true)
        assertEquals("maintenu sur passage : 101", CrossingLevels.HUMAN, repeat.posts.single().level)

        val offPath = engine(red)
        val first = offPath.onFrame(frame(), emptyList(), "", 0L)
        val second = offPath.onFrame(frame(), emptyList(), "", 2_000L)
        assertEquals(CrossingLevels.VERDICT, first.posts.single().level)
        assertEquals("hors passage : 100 meme en repetition", CrossingLevels.VERDICT, second.posts.single().level)
    }

    @Test
    fun `veto bascule le verdict a 99 dans les 2 sens`() {
        val posted = mutableListOf<CrossingPost>()
        val green = CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Green)
        val engine = engine(green, posted)
        engine.warm(2)

        val cross = engine.onFrame(frame(), emptyList(), "", 0L, onCrosswalk = true)
        assertEquals(CrossingVerdict.Cross, cross.verdict)
        val stop = engine.onFrame(frame(), emptyList(), "", 200L, carVeto = true, onCrosswalk = true)
        assertEquals(CrossingVerdict.DontCross, stop.verdict)
        val go = engine.onFrame(frame(), emptyList(), "", 400L, onCrosswalk = true)
        assertEquals(CrossingVerdict.Cross, go.verdict)

        assertEquals(listOf(99, 99, 99), posted.map { it.level })
    }
}
