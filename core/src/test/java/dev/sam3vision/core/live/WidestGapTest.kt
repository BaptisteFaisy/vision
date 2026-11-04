package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le plus grand espace libre décide du côté (ordre user du 04/11) : chaque cas vient
 * de la frame qui tirait à pile ou face sur le Samsung, une personne droit devant.
 */
class WidestGapTest {

    private val hfov = 52.8f

    private fun box(label: String, x0: Float, x1: Float, meters: Float?, cutBottom: Boolean = false) =
        PlacedLabel(label, (x0 + x1) / 2f, 0.5f, meters, x0, x1, cutBottom = cutBottom)

    private fun sideOf(vararg labels: PlacedLabel): GuidanceSide? = ClearPath.view(labels.toList(), hfov).side

    @Test
    fun `personne plein axe a droite malgre le balancement du telephone`() {
        for (x in listOf(0.43f, 0.47f, 0.50f, 0.53f, 0.57f)) {
            assertEquals("x=$x", GuidanceSide.Right, sideOf(box("person", x - 0.08f, x + 0.08f, 3f)))
        }
    }

    @Test
    fun `personne nettement a droite on passe a gauche, la ou il y a la place`() {
        assertEquals(GuidanceSide.Left, sideOf(box("person", 0.55f, 0.75f, 3f)))
    }

    @Test
    fun `va vers le plus grand trou, meme s il n est pas le plus proche`() {
        val person = box("person", 0.45f, 0.60f, 3f)
        val leftCrowd = box("person", 0.05f, 0.30f, 3f)
        assertEquals(GuidanceSide.Right, sideOf(person, leftCrowd))
    }

    @Test
    fun `dans une foule le plus grand trou gagne a gauche`() {
        val person = box("person", 0.50f, 0.62f, 2f)
        val rightCrowd = box("person", 0.70f, 0.95f, 2.5f)
        assertEquals(GuidanceSide.Left, sideOf(person, rightCrowd))
    }

    @Test
    fun `un objet loin derriere ne decide pas du cote`() {
        val person = box("person", 0.42f, 0.58f, 2f)
        val farPole = box("pole", 0.70f, 0.72f, 9f)
        assertEquals(GuidanceSide.Right, sideOf(person, farPole))
    }

    @Test
    fun `un objet sans distance ne decide pas du cote`() {
        val person = box("person", 0.42f, 0.58f, 3f)
        val unknownCar = box("car", 0.60f, 1f, null)
        assertEquals(GuidanceSide.Right, sideOf(person, unknownCar))
    }

    @Test
    fun `un objet coupe par le bas compte, il est proche`() {
        val person = box("person", 0.42f, 0.58f, 3f)
        val nearCar = box("car", 0.60f, 1f, null, cutBottom = true)
        assertEquals(GuidanceSide.Left, sideOf(person, nearCar))
    }

    @Test
    fun `une bordure qui barre l image ne fait plus basculer le cote`() {
        for (center in listOf(0.49f, 0.51f)) {
            val curb = box("curb", center - 0.48f, center + 0.48f, 2f)
            assertEquals("centre $center", GuidanceSide.Right, sideOf(box("person", 0.42f, 0.58f, 3f), curb))
        }
    }

    @Test
    fun `tous les obstacles suivent la meme regle`() {
        for (label in listOf("person", "pole", "barrier", "wall", "fence", "street lamp", "trash can")) {
            assertEquals("$label plein axe", GuidanceSide.Right, sideOf(box(label, 0.46f, 0.54f, 3f)))
            assertEquals("$label à droite", GuidanceSide.Left, sideOf(box(label, 0.55f, 0.75f, 3f)))
            assertEquals("$label à gauche", GuidanceSide.Right, sideOf(box(label, 0.25f, 0.45f, 3f)))
        }
    }

    @Test
    fun `barriere large on sort par le cote ouvert, pas le plus proche`() {
        assertEquals(GuidanceSide.Right, sideOf(box("barrier", 0.05f, 0.58f, 3f)))
    }

    @Test
    fun `mur longe tout pres, on va vers le milieu`() {
        val leftWall = box("wall", 0f, 0.47f, 0.6f)
        val rightWall = box("wall", 0.53f, 1f, 3f)
        assertEquals(GuidanceSide.Right, sideOf(leftWall, rightWall))
    }

    @Test
    fun `de loin le balancement ne fait pas osciller le cote`() {
        for (x in listOf(0.44f, 0.50f, 0.56f)) {
            assertEquals("x=$x", GuidanceSide.Right, sideOf(box("person", x - 0.02f, x + 0.02f, 9f)))
        }
    }

    @Test
    fun `tous les obstacles parlent des 10 m, pas au dela`() {
        for (label in listOf("person", "pole", "barrier", "wall", "fence", "street lamp", "trash can")) {
            val near = ClearPath.view(listOf(box(label, 0.47f, 0.53f, 9.5f)), hfov).order
            assertEquals("$label à 9,5 m", label, near?.threat?.label)
            assertNull("$label à 10,5 m", ClearPath.view(listOf(box(label, 0.47f, 0.53f, 10.5f)), hfov).order)
        }
    }

    @Test
    fun `un mur sur le cote ferme ce cote`() {
        val person = box("person", 0.42f, 0.58f, 3f)
        val wall = box("wall", 0.62f, 1f, 3f).copy(closingMps = 1.2f, closingSpanMs = 1_000L)
        assertEquals(GuidanceSide.Left, sideOf(person, wall))
    }

    @Test
    fun `l egalite ne pousse pas dans un mur`() {
        assertEquals(GuidanceSide.Left, GapRoom(left = 0.02f, right = 0f).side)
        assertEquals(GuidanceSide.Right, GapRoom(left = 0.30f, right = 0.25f).side)
        assertEquals(GuidanceSide.Left, GapRoom(left = 0.40f, right = 0.20f).side)
    }

    @Test
    fun `rien ne gene, pas de cote`() {
        assertNull(sideOf(box("person", 0.90f, 0.98f, 3f)))
    }

    @Test
    fun `frame bouchee d un bord a l autre, pas de place`() {
        assertNull(WidestGap.of(listOf(box("wall", 0f, 1f, 3f)), 3f, hfov))
    }

    @Test
    fun `la place se lit a gauche et a droite`() {
        val room = WidestGap.of(listOf(box("person", 0.50f, 0.60f, 3f)), 3f, hfov)
        assertNotNull(room)
        assertTrue(room!!.left > room.right)
    }
}
