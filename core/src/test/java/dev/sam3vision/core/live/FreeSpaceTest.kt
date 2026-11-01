package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Place latérale libre : ce que le terrain permet réellement de chaque côté de
 * l'axe. Les bords vus (bordure, mur, clôture, portail, haie, barrière) la
 * bornent, marge de sécurité déduite ; un côté sans bord mesuré garde le
 * trottoir par défaut. Le sol marchable (`sidewalk`) n'est pas un bord.
 */
class FreeSpaceTest {

    /**
     * Bord placé pour que sa distance **latérale** à l'axe vaille [lateralM]
     * (négatif à gauche), à la profondeur [depth] : inverse de la projection
     * `(centerX − 0,5) × 2 × profondeur × tan(hfov/2)`.
     */
    private fun bord(
        label: String,
        lateralM: Float,
        depth: Float = 2f,
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
    ): PlacedLabel {
        val halfWidth = 2f * depth * LensSpec.halfTangent(hfovDeg)
        return PlacedLabel(label, 0.5f + lateralM / halfWidth, 0.5f, depth)
    }

    @Test
    fun `aucun bord mesure donne le trottoir par defaut`() {
        val espace = FreeSpace.of(emptyList())

        assertEquals(FreeSpace.DEFAULT_HALF_M, espace.leftM, 0.001f)
        assertEquals(FreeSpace.DEFAULT_HALF_M, espace.rightM, 0.001f)
        assertEquals(1.8f, espace.totalM, 0.001f)
        assertEquals(GuidanceSide.Right, espace.side)
        assertEquals(FreeSpace.unknown(), espace)
    }

    @Test
    fun `bordure mesuree a 0,7 m a gauche laisse 0,4 m`() {
        val espace = FreeSpace.of(listOf(bord("curb", -0.7f)))

        assertEquals(0.4f, espace.leftM, 0.01f)
        assertEquals(FreeSpace.DEFAULT_HALF_M, espace.rightM, 0.001f)
        assertEquals(GuidanceSide.Right, espace.side)
    }

    @Test
    fun `deux bords asymetriques choisissent le cote le plus large`() {
        val espace = FreeSpace.of(listOf(bord("curb", -0.6f), bord("wall", 1.6f, depth = 4f)))

        assertEquals(0.3f, espace.leftM, 0.01f)
        assertEquals(1.3f, espace.rightM, 0.01f)
        assertEquals(GuidanceSide.Right, espace.side)
    }

    @Test
    fun `deux bords asymetriques inverses choisissent la gauche`() {
        val espace = FreeSpace.of(listOf(bord("fence", -1.6f, depth = 4f), bord("gate", 0.6f)))

        assertEquals(GuidanceSide.Left, espace.side)
        assertEquals(1.3f, espace.room(GuidanceSide.Left), 0.01f)
    }

    @Test
    fun `bord non mesure est ignore`() {
        val espace = FreeSpace.of(listOf(PlacedLabel("curb", 0.2f, 0.5f, null)))

        assertEquals(FreeSpace.unknown(), espace)
    }

    @Test
    fun `bord a distance nulle ou negative est ignore`() {
        val espace = FreeSpace.of(
            listOf(PlacedLabel("wall", 0.2f, 0.5f, 0f), PlacedLabel("wall", 0.2f, 0.5f, -2f)),
        )

        assertEquals(FreeSpace.unknown(), espace)
    }

    @Test
    fun `bord au dela du maximum est borne`() {
        val espace = FreeSpace.of(listOf(bord("wall", 6f, depth = 10f)))

        assertEquals(FreeSpace.MAX_HALF_M, espace.rightM, 0.001f)
        assertEquals(GuidanceSide.Right, espace.side)
    }

    @Test
    fun `sidewalk seul ne borne pas la place`() {
        val espace = FreeSpace.of(listOf(bord("sidewalk", -0.7f), bord("crosswalk", 0.7f)))

        assertEquals(FreeSpace.unknown(), espace)
    }

    @Test
    fun `egalite des deux cotes donne le cote droit`() {
        val espace = FreeSpace.of(listOf(bord("curb", -1f, depth = 4f), bord("wall", 1f, depth = 4f)))

        assertEquals(0.7f, espace.leftM, 0.01f)
        assertEquals(0.7f, espace.rightM, 0.01f)
        assertEquals(GuidanceSide.Right, espace.side)
    }

    @Test
    fun `canShift vrai quand la place suffit faux sinon`() {
        val espace = FreeSpace(0.4f, 0.9f)

        assertTrue(espace.canShift(GuidanceSide.Left, 0.4f))
        assertTrue(espace.canShift(GuidanceSide.Right, 0.9f))
        assertFalse(espace.canShift(GuidanceSide.Left, 0.5f))
        assertFalse(espace.canShift(GuidanceSide.Right, 1f))
    }

    @Test
    fun `room rend la place du cote demande`() {
        val espace = FreeSpace(0.4f, 1.2f)

        assertEquals(0.4f, espace.room(GuidanceSide.Left), 0.001f)
        assertEquals(1.2f, espace.room(GuidanceSide.Right), 0.001f)
        assertEquals(1.6f, espace.totalM, 0.001f)
    }

    @Test
    fun `bord pile sur l axe borne le cote droit`() {
        val espace = FreeSpace.of(listOf(bord("fence", 0f)))

        assertEquals(0f, espace.rightM, 0.001f)
        assertEquals(FreeSpace.DEFAULT_HALF_M, espace.leftM, 0.001f)
        assertEquals(GuidanceSide.Left, espace.side)
    }

    @Test
    fun `le bord le plus proche du cote fait foi`() {
        val espace = FreeSpace.of(listOf(bord("curb", -1.2f), bord("hedge", -0.5f)))

        assertEquals(0.2f, espace.leftM, 0.01f)
    }

    @Test
    fun `pluriel simple reconnu comme bord`() {
        val espace = FreeSpace.of(listOf(bord("walls", -1f, depth = 4f)))

        assertEquals(0.7f, espace.leftM, 0.01f)
    }
}
