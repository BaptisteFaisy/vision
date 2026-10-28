package dev.sam3vision.core.segment

import org.junit.Assert.assertEquals
import org.junit.Test

/** Direction de marche conseillée depuis le masque praticable (moitié basse). */
class WalkGuideTest {

    private fun maskOf(width: Int, height: Int, fill: (x: Int, y: Int) -> Boolean): SegmentMask {
        val bytes = ByteArray(width * height) { i -> if (fill(i % width, i / width)) 1 else 0 }
        return SegmentMask(width, height, bytes)
    }

    @Test
    fun `plein champ degage on continue tout droit`() {
        val advice = WalkGuide.directionFor(maskOf(90, 60) { _, _ -> true })
        assertEquals(WalkDirection.STRAIGHT, advice.direction)
    }

    @Test
    fun `seul le tiers gauche degage on va a gauche`() {
        val advice = WalkGuide.directionFor(maskOf(90, 60) { x, y -> y >= 30 && x < 30 })
        assertEquals(WalkDirection.LEFT, advice.direction)
    }

    @Test
    fun `seul le tiers droit degage on va a droite`() {
        val advice = WalkGuide.directionFor(maskOf(90, 60) { x, y -> y >= 30 && x >= 60 })
        assertEquals(WalkDirection.RIGHT, advice.direction)
    }

    @Test
    fun `masque vide on s'arrete`() {
        val advice = WalkGuide.directionFor(maskOf(90, 60) { _, _ -> false })
        assertEquals(WalkDirection.STOP, advice.direction)
    }

    @Test
    fun `sous le seuil partout on s'arrete`() {
        // ~11 % de pixels épars : du bruit, pas un chemin.
        val advice = WalkGuide.directionFor(maskOf(90, 60) { x, y -> y >= 30 && (x + y) % 9 == 0 })
        assertEquals(WalkDirection.STOP, advice.direction)
    }

    @Test
    fun `centre bouche cotes ouverts on prefere la gauche`() {
        val advice = WalkGuide.directionFor(maskOf(90, 60) { x, y -> y >= 30 && (x < 30 || x >= 60) })
        assertEquals(WalkDirection.LEFT, advice.direction)
    }

    @Test
    fun `le haut du masque ne compte pas (loin devant)`() {
        val advice = WalkGuide.directionFor(maskOf(90, 60) { _, y -> y < 30 })
        assertEquals(WalkDirection.STOP, advice.direction)
    }

    @Test
    fun `les fractions par tiers sont exposees`() {
        val advice = WalkGuide.directionFor(maskOf(90, 60) { x, y -> y >= 30 && x < 30 })
        assertEquals(1f, advice.left, 0.001f)
        assertEquals(0f, advice.center, 0.001f)
        assertEquals(0f, advice.right, 0.001f)
    }
}
