package dev.sam3vision.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boîte et vitesse : le socle géométrique de toute la prédiction. */
class BoxTest {

    @Test
    fun `fromProto accepte des coins inverses`() {
        assertEquals(Box(10f, 20f, 30f, 40f), Box.fromProto(listOf(30f, 40f, 10f, 20f)))
    }

    @Test
    fun `fromProto rejette liste courte, NaN et boite degeneree`() {
        assertNull(Box.fromProto(listOf(1f, 2f)))
        assertNull(Box.fromProto(listOf(0f, 0f, Float.NaN, 5f)))
        assertNull(Box.fromProto(listOf(5f, 5f, 5f, 9f)))
    }

    @Test
    fun `iou vaut 1 sur la meme boite et 0 sur des disjointes`() {
        val a = Box(0f, 0f, 10f, 10f)
        assertEquals(1f, a.iou(a), 1e-4f)
        assertEquals(0f, a.iou(Box(20f, 20f, 30f, 30f)), 1e-4f)
    }

    @Test
    fun `iou demi-recouvrement vaut un tiers`() {
        assertEquals(1f / 3f, Box(0f, 0f, 10f, 10f).iou(Box(5f, 0f, 15f, 10f)), 1e-4f)
    }

    @Test
    fun `lerp et translated`() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(20f, 0f, 30f, 10f)
        assertEquals(Box(10f, 0f, 20f, 10f), a.lerp(b, 0.5f))
        assertEquals(b, a.translated(20f, 0f))
    }

    @Test
    fun `clampedTo garde la taille et reste dans l'image`() {
        val clamped = Box(400f, 10f, 500f, 110f).clampedTo(512, 288)
        assertEquals(100f, clamped.width, 1e-4f)
        assertTrue(clamped.x2 <= 512.01f)
        assertTrue(clamped.y2 <= 288.01f)
    }

    @Test
    fun `clampedTo ne plante pas sur une boite plus grande que l'image`() {
        assertTrue(Box(-50f, -50f, 900f, 900f).clampedTo(512, 288).isSane)
    }

    @Test
    fun `grown et scaled agissent autour du centre`() {
        val box = Box(10f, 10f, 20f, 20f)
        assertEquals(Box(5f, 5f, 25f, 25f), box.grown(10f, 10f))
        assertEquals(Box(5f, 5f, 25f, 25f), box.scaled(2f, 2f))
    }

    @Test
    fun `limited plafonne la norme en gardant la direction`() {
        val v = Velocity(300f, -400f).limited(100f)
        assertEquals(100f, v.speed, 0.5f)
        assertTrue(v.x > 0f && v.y < 0f)
    }

    @Test
    fun `over projette une vitesse sur une duree`() {
        assertEquals(Velocity(25f, 0f), Velocity(100f, 0f).over(0.25f))
    }
}