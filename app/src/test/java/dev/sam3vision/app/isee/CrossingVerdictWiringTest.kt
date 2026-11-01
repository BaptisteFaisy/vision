package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.crossing.CrossingPost
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingVerdict
import dev.sam3vision.core.crossing.CrossingVerdictLight
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingVerdictWiringTest {

    private fun car(label: String = "car", box: List<Float>): SegObject =
        SegObject(label, 0.9f, box, "")

    private fun frame(vararg objects: SegObject): LiveFrame =
        LiveFrame(640, 480, 0L, objects.toList())

    private fun engine(
        light: CrossingLight,
        posted: MutableList<CrossingPost> = mutableListOf(),
    ): CrossingVerdictEngine = CrossingVerdictEngine(
        readLight = { _, _ -> light },
        poster = CrossingVerdictPoster { level, text, clipId ->
            posted.add(CrossingPost(level, text, clipId))
        },
    )

    @Test
    fun `le rouge publie don't cross niveau 100 avec overlay rouge`() {
        val posted = mutableListOf<CrossingPost>()
        val out = engine(CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Red), posted)
            .onFrame(frame(), emptyList(), "", 0L)
        assertEquals(CrossingVerdict.DontCross, out.verdict)
        assertEquals(CrossingVerdictLight.Red, out.light)
        assertEquals(1, out.posts.size)
        assertEquals(CrossingLevels.VERDICT, out.posts[0].level)
        assertEquals("Don't cross.", out.posts[0].text)
        assertEquals("verdict_dont_cross", out.posts[0].clipId)
        assertEquals(out.posts, posted)
        assertEquals(CrossingState.Red, out.overlay)
        assertEquals(CrossingState.Red, out.haptic)
    }

    @Test
    fun `le vert sans voiture publie cross avec overlay vert`() {
        val out = engine(CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Green))
            .onFrame(frame(), emptyList(), "", 0L)
        assertEquals(CrossingVerdict.Cross, out.verdict)
        assertEquals("Cross.", out.posts.single().text)
        assertEquals(CrossingState.Green, out.overlay)
        assertEquals(CrossingState.Green, out.haptic)
        assertFalse(out.carMoving)
    }

    @Test
    fun `sans feu et sans voiture on traverse`() {
        val out = engine(CrossingLight(null, CrossingState.Red))
            .onFrame(frame(), emptyList(), "", 0L)
        assertEquals(CrossingVerdictLight.Absent, out.light)
        assertEquals(CrossingVerdict.Cross, out.verdict)
    }

    @Test
    fun `une voiture qui approche sur vert fait attendre aussitot`() {
        val green = CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Green)
        val engine = engine(green)
        val first = engine.onFrame(frame(car(box = listOf(100f, 100f, 200f, 200f))), emptyList(), "", 0L)
        assertEquals(CrossingVerdict.Cross, first.verdict)
        val second = engine.onFrame(
            frame(car(box = listOf(50f, 50f, 300f, 300f))), emptyList(), "", 200L,
        )
        assertTrue(second.carMoving)
        assertEquals(CrossingVerdict.DontCross, second.verdict)
        assertEquals("Don't cross.", second.posts.single().text)
        assertEquals(CrossingState.Red, second.haptic)
    }

    @Test
    fun `une voiture immobile sur vert laisse traverser`() {
        val green = CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Green)
        val engine = engine(green)
        val box = listOf(100f, 100f, 200f, 200f)
        engine.onFrame(frame(car(box = box)), emptyList(), "", 0L)
        val second = engine.onFrame(frame(car(box = box)), emptyList(), "", 200L)
        assertFalse(second.carMoving)
        assertEquals(CrossingVerdict.Cross, second.verdict)
    }

    @Test
    fun `un bus qui approche ne tranche pas, voiture uniquement`() {
        val green = CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Green)
        val engine = engine(green)
        engine.onFrame(frame(car("bus", listOf(100f, 100f, 200f, 200f))), emptyList(), "", 0L)
        val second = engine.onFrame(
            frame(car("bus", listOf(50f, 50f, 300f, 300f))), emptyList(), "", 200L,
        )
        assertFalse(second.carMoving)
        assertEquals(CrossingVerdict.Cross, second.verdict)
    }

    @Test
    fun `la repetition des 3s ne vibre pas`() {
        val red = CrossingLight(listOf(0.1f, 0.1f, 0.2f, 0.3f), CrossingState.Red)
        val engine = engine(red)
        val first = engine.onFrame(frame(), emptyList(), "", 0L)
        assertEquals(CrossingState.Red, first.haptic)
        assertTrue(engine.onFrame(frame(), emptyList(), "", 1_000L).posts.isEmpty())
        val repeat = engine.onFrame(frame(), emptyList(), "", 3_000L)
        assertEquals(1, repeat.posts.size)
        assertNull(repeat.haptic)
        assertEquals(CrossingState.Red, repeat.overlay)
    }

    @Test
    fun `humains et bordures suivent le verdict dans le meme appel`() {
        val posted = mutableListOf<CrossingPost>()
        val human = CrossingPost(CrossingLevels.HUMAN, "Person at 12.", null)
        val curb = CrossingPost(CrossingLevels.CURB, "Curb ahead.", null)
        val out = engine(CrossingLight(null, CrossingState.Red), posted)
            .onFrame(frame(), emptyList(), "", 0L, human = human, curb = curb)
        assertEquals(3, out.posts.size)
        assertEquals(CrossingLevels.VERDICT, out.posts[0].level)
        assertEquals(CrossingLevels.HUMAN, out.posts[1].level)
        assertEquals(CrossingLevels.CURB, out.posts[2].level)
        assertEquals(out.posts, posted)
    }

    @Test
    fun `verdictLightOf distingue l'absence du rouge`() {
        assertEquals(CrossingVerdictLight.Absent, verdictLightOf(CrossingLight(null, CrossingState.Red)))
        assertEquals(
            CrossingVerdictLight.Green,
            verdictLightOf(CrossingLight(listOf(0f, 0f, 0.1f, 0.1f), CrossingState.Green)),
        )
        assertEquals(
            CrossingVerdictLight.Red,
            verdictLightOf(CrossingLight(listOf(0f, 0f, 0.1f, 0.1f), CrossingState.Red)),
        )
    }

    @Test
    fun `overlayOf garde le rouge-vert`() {
        assertEquals(CrossingState.Green, overlayOf(CrossingVerdict.Cross))
        assertEquals(CrossingState.Red, overlayOf(CrossingVerdict.DontCross))
    }
}
