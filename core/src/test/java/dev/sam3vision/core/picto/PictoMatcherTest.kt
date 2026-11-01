package dev.sam3vision.core.picto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Texture déterministe : variance forte, aucun motif ambigu. */
private fun textured(size: Int, seed: Int = 0): GrayImage {
    val gray = IntArray(size * size) { i ->
        val x = i % size
        val y = i / size
        (x * 7 + y * 13 + (x * y + seed) % 11 + seed * 31) % 256
    }
    return GrayImage(gray, size, size)
}

/**
 * Carré blanc troué sur fond noir : motif unique et non périodique — une rampe
 * ou une texture périodique s'auto-corrèle partout et rend tout test ambigu.
 */
private fun geometric(size: Int, squareAt: Int, square: Int, hole: Int): GrayImage {
    val gray = IntArray(size * size)
    for (y in squareAt until squareAt + square) {
        for (x in squareAt until squareAt + square) gray[y * size + x] = 255
    }
    val h0 = squareAt + (square - hole) / 2
    for (y in h0 until h0 + hole) {
        for (x in h0 until h0 + hole) gray[y * size + x] = 0
    }
    return GrayImage(gray, size, size)
}

/** Bruit LCG déterministe : décorrélé de tout motif, le vrai cas « rien ». */
private fun noise(size: Int, seed: Long): GrayImage {
    var s = seed
    return GrayImage(
        IntArray(size * size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            ((s ushr 33) % 256).toInt()
        },
        size,
        size,
    )
}

/** Arête verticale : sombre à gauche, clair à droite — montant, halo de lampe. */
private fun edgeScene(size: Int, at: Int, dark: Int = 60, light: Int = 210): GrayImage =
    GrayImage(IntArray(size * size) { i -> if (i % size < at) dark else light }, size, size)

/** Découpe 24×24 à (20,20) : gabarit à l'échelle exacte de la frame. */
private fun directTemplate(frame: GrayImage): PictoTemplate {
    val crop = IntArray(24 * 24) { i -> frame.gray[(20 + i / 24) * frame.width + 20 + i % 24] }
    return PictoTemplate(crop, 24)
}

class PictoMatcherTest {

    @Test
    fun `retrouve le gabarit decoupe dans la frame`() {
        val frame = textured(64)
        val hit = matchTemplate(frame, directTemplate(frame), "p1", "objet")
        assertTrue(hit != null)
        assertEquals(20f / 64f, hit!!.x, 0.001f)
        assertEquals(20f / 64f, hit.y, 0.001f)
        assertTrue(hit.score > 0.999f)
    }

    @Test
    fun `chaine complete - gabarit construit puis reconnu sur la photo`() {
        val photo = geometric(64, 22, 20, 8)
        val hit = matchTemplate(photo, buildTemplate(photo), "p1", "objet")
        assertTrue(hit != null)
        val cx = (hit!!.x + hit.w / 2) * 64
        val cy = (hit.y + hit.h / 2) * 64
        assertEquals(32.0, cx.toDouble(), 3.0)
        assertEquals(32.0, cy.toDouble(), 3.0)
    }

    @Test
    fun `ne trouve rien dans du bruit`() {
        val template = buildTemplate(geometric(64, 22, 20, 8))
        assertNull(matchTemplate(noise(64, 12345L), template, "p1", "objet"))
    }

    @Test
    fun `gabarit uni ne matche jamais`() {
        val flat = PictoTemplate(IntArray(TEMPLATE_EDGE * TEMPLATE_EDGE) { 128 }, TEMPLATE_EDGE)
        assertNull(matchTemplate(textured(64), flat, "p1", "objet"))
    }

    @Test
    fun `retrouve a l'echelle 1_4 quand la frame grandit`() {
        val frame = geometric(64, 22, 20, 8)
        val big = GrayImage(resizeGray(frame.gray, 64, 90), 90, 90)
        val hit = matchTemplate(big, directTemplate(frame), "p1", "objet")
        assertTrue(hit != null)
        // Centre attendu ≈ (20+12)*90/64 = 45, tolérance d'un pas + rééchantillonnage.
        val cx = (hit!!.x + hit.w / 2) * 90
        val cy = (hit.y + hit.h / 2) * 90
        assertEquals(45.0, cx.toDouble(), 4.0)
        assertEquals(45.0, cy.toDouble(), 4.0)
    }

    @Test
    fun `buildTemplate prend le carre central reduit`() {
        val photo = GrayImage(IntArray(48 * 32) { i -> i % 48 }, 48, 32)
        val template = buildTemplate(photo)
        assertEquals(TEMPLATE_EDGE, template.edge)
        // Carré 16×16 à (16,8) : le premier pixel vaut x = 16.
        assertEquals(16, template.gray[0])
    }

    @Test
    fun `arete seule sans objet - pas de match malgre un score haut`() {
        // Gabarit arête (sombre|clair, comme le gabarit clés du téléphone) sur
        // une scène arête ailleurs : le seuil seul rendait un HIT à ~1.0 — le
        // picto clés sur la lampe du 01/11. La marge l'interdit : l'arête
        // matche partout, aucune position ne se détache.
        val template = PictoTemplate(IntArray(24 * 24) { i -> if (i % 24 < 8) 40 else 200 }, 24)
        assertNull(matchTemplate(edgeScene(96, 50), template, "p1", "keys"))
    }

    @Test
    fun `vrai positif texture garde sa marge`() {
        // Incrustation exacte dans du bruit : pic unique à 1.0, second loin
        // derrière — le rappel ne doit pas souffrir de la marge.
        val frame = noise(96, 999L)
        val patch = IntArray(24 * 24) { (it * 37 + 11) % 256 }
        for (dy in 0 until 24) {
            for (dx in 0 until 24) frame.gray[(40 + dy) * 96 + 30 + dx] = patch[dy * 24 + dx]
        }
        val hit = matchTemplate(frame, PictoTemplate(patch, 24), "p1", "objet")
        assertTrue(hit != null)
        assertEquals(30f / 96f, hit!!.x, 0.001f)
        assertEquals(40f / 96f, hit.y, 0.001f)
    }

    @Test
    fun `gabarit cadre sur la boite, pas sur le centre`() {
        val photo = geometric(64, 40, 16, 6)
        val boxed = buildTemplateFromBox(photo, listOf(0.55f, 0.55f, 0.95f, 0.95f))
        assertEquals(TEMPLATE_EDGE, boxed.edge)
        val hit = matchTemplate(photo, boxed, "p1", "objet")
        assertTrue(hit != null)
        val cx = (hit!!.x + hit.w / 2) * 64
        val cy = (hit.y + hit.h / 2) * 64
        assertEquals(48.0, cx.toDouble(), 4.0)
        assertEquals(48.0, cy.toDouble(), 4.0)
    }

    @Test
    fun `boite degeneree garde le carre central`() {
        val photo = geometric(64, 22, 20, 8)
        assertTrue(buildTemplateFromBox(photo, emptyList()).gray.contentEquals(buildTemplate(photo).gray))
        assertTrue(buildTemplateFromBox(photo, listOf(0f, 0f, 0f, 0f)).gray.contentEquals(buildTemplate(photo).gray))
    }
}

class PictoPromptTest {

    @Test
    fun `consigne noire sur jaune sans texte`() {
        val prompt = pictoPrompt("")
        assertTrue(prompt.contains("black"))
        assertTrue(prompt.contains("yellow"))
        assertTrue(prompt.contains("no text"))
        assertTrue(prompt.contains("main centered object"))
    }

    @Test
    fun `le nom de l'aidant leve l'ambiguite`() {
        assertTrue(pictoPrompt("boîte de médicaments").contains("boîte de médicaments"))
    }
}
