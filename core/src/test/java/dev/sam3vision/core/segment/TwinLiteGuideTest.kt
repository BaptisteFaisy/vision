package dev.sam3vision.core.segment

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boussole TwinLite : le tiers du bas le plus praticable donne le cap,
 * STOP quand rien ne passe, le centre puis la gauche aux égalités.
 */
class TwinLiteGuideTest {

    /** Masque depuis des lignes de `0`/`1` (toutes de même longueur). */
    private fun masque(vararg lignes: String): SegmentMask {
        val largeur = lignes.first().length
        require(lignes.all { it.length == largeur }) { "lignes de meme longueur" }
        val bytes = ByteArray(largeur * lignes.size) { i ->
            if (lignes[i / largeur][i % largeur] == '1') 1 else 0
        }
        return SegmentMask(largeur, lignes.size, bytes)
    }

    @Test
    fun `le tiers gauche plein envoie a gauche`() {
        // Haut vide aussi : les parts se comptent sur la moitié basse seule.
        val conseil = TwinLiteGuide.directionFor(
            masque("000000", "000000", "110000", "110000"),
        )

        assertEquals(TwinLiteDirection.LEFT, conseil.direction)
        assertEquals(1f, conseil.left, 1e-6f)
        assertEquals(0f, conseil.center, 0f)
        assertEquals(0f, conseil.right, 0f)
    }

    @Test
    fun `le tiers central plein envoie tout droit`() {
        val conseil = TwinLiteGuide.directionFor(
            masque("000000", "000000", "001100", "001100"),
        )

        assertEquals(TwinLiteDirection.STRAIGHT, conseil.direction)
        assertEquals(1f, conseil.center, 1e-6f)
    }

    @Test
    fun `le tiers droit plein envoie a droite`() {
        val conseil = TwinLiteGuide.directionFor(
            masque("000000", "000000", "000011", "000011"),
        )

        assertEquals(TwinLiteDirection.RIGHT, conseil.direction)
        assertEquals(1f, conseil.right, 1e-6f)
    }

    @Test
    fun `le tiers le plus praticable gagne`() {
        val conseil = TwinLiteGuide.directionFor(
            masque("000000", "000000", "111111", "001000"),
        )

        assertEquals(TwinLiteDirection.STRAIGHT, conseil.direction)
        assertEquals(0.5f, conseil.left, 1e-6f)
        assertEquals(0.75f, conseil.center, 1e-6f)
        assertEquals(0.5f, conseil.right, 1e-6f)
    }

    @Test
    fun `tout vide envoie stop`() {
        val conseil = TwinLiteGuide.directionFor(
            masque("000000", "000000", "000000", "000000"),
        )

        assertEquals(TwinLiteDirection.STOP, conseil.direction)
        assertEquals(0f, conseil.left, 0f)
        assertEquals(0f, conseil.center, 0f)
        assertEquals(0f, conseil.right, 0f)
    }

    @Test
    fun `sous le seuil partout envoie stop`() {
        // 2 px sur 9 par tiers : 0,22 < 0,25.
        val conseil = TwinLiteGuide.directionFor(
            masque(
                "000000000",
                "000000000",
                "000000000",
                "100100100",
                "100100100",
                "000000000",
            ),
        )

        assertEquals(TwinLiteDirection.STOP, conseil.direction)
        assertEquals(2f / 9f, conseil.left, 1e-6f)
        assertEquals(2f / 9f, conseil.center, 1e-6f)
        assertEquals(2f / 9f, conseil.right, 1e-6f)
    }

    @Test
    fun `a egalite le centre gagne`() {
        val conseil = TwinLiteGuide.directionFor(
            masque("000000", "000000", "111100", "111100"),
        )

        assertEquals(TwinLiteDirection.STRAIGHT, conseil.direction)
    }

    @Test
    fun `a egalite sans centre la gauche gagne`() {
        val conseil = TwinLiteGuide.directionFor(
            masque("000000", "000000", "110011", "110011"),
        )

        assertEquals(TwinLiteDirection.LEFT, conseil.direction)
    }

    @Test
    fun `plein partout envoie tout droit`() {
        val conseil = TwinLiteGuide.directionFor(
            masque("111111", "111111", "111111", "111111"),
        )

        assertEquals(TwinLiteDirection.STRAIGHT, conseil.direction)
    }

    @Test
    fun `le haut du masque est ignore`() {
        val conseil = TwinLiteGuide.directionFor(
            masque("111111", "111111", "000000", "000000"),
        )

        assertEquals(TwinLiteDirection.STOP, conseil.direction)
    }

    @Test
    fun `un quart pile est praticable`() {
        // « Sous le seuil » se lit strict : 0,25 passe, en dessous non.
        val conseil = TwinLiteGuide.directionFor(
            masque("000000", "000000", "100000", "000000"),
        )

        assertEquals(TwinLiteDirection.LEFT, conseil.direction)
        assertEquals(0.25f, conseil.left, 0f)
    }

    @Test
    fun `le seuil vaut un quart`() {
        assertEquals(0.25f, TwinLiteGuide.WALKABLE_FRACTION, 0f)
    }

    @Test
    fun `masque vide envoie stop sans broncher`() {
        val vide = TwinLiteGuide.directionFor(SegmentMask(0, 0, ByteArray(0)))

        assertEquals(TwinLiteDirection.STOP, vide.direction)
        assertEquals(0f, vide.left, 0f)
        assertEquals(0f, vide.center, 0f)
        assertEquals(0f, vide.right, 0f)
        assertEquals(
            TwinLiteDirection.STOP,
            TwinLiteGuide.directionFor(SegmentMask(4, 4, ByteArray(0))).direction,
        )
        assertEquals(
            TwinLiteDirection.STOP,
            TwinLiteGuide.directionFor(SegmentMask(6, 0, ByteArray(0))).direction,
        )
    }
}
