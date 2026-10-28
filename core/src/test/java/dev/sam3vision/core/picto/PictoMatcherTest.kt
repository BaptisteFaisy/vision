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
        val photo = textured(64)
        val hit = matchTemplate(photo, buildTemplate(photo), "p1", "objet")
        assertTrue(hit != null)
        val cx = (hit!!.x + hit.w / 2) * 64
        val cy = (hit.y + hit.h / 2) * 64
        assertEquals(32.0, cx.toDouble(), 3.0)
        assertEquals(32.0, cy.toDouble(), 3.0)
    }

    @Test
    fun `ne trouve rien dans une autre texture`() {
        val template = buildTemplate(textured(64, seed = 1))
        val other = textured(64, seed = 2)
        assertNull(matchTemplate(other, template, "p1", "objet"))
    }

    @Test
    fun `gabarit uni ne matche jamais`() {
        val flat = PictoTemplate(IntArray(TEMPLATE_EDGE * TEMPLATE_EDGE) { 128 }, TEMPLATE_EDGE)
        assertNull(matchTemplate(textured(64), flat, "p1", "objet"))
    }

    @Test
    fun `retrouve a l'echelle 1_4 quand la frame grandit`() {
        val frame = textured(64)
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
