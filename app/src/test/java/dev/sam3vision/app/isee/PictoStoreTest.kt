package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.PictoTemplate
import dev.sam3vision.core.picto.TEMPLATE_EDGE
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le magasin des repères : un dossier par picto, les entrées abîmées
 * ignorées (jamais fatales), le nom par défaut quand l'aidant n'en met pas.
 */
class PictoStoreTest {

    private fun store(): PictoStore =
        PictoStore(Files.createTempDirectory("isee-picto-store").toFile().apply { deleteOnExit() })

    private fun template(): PictoTemplate =
        PictoTemplate(IntArray(TEMPLATE_EDGE * TEMPLATE_EDGE) { it % 256 }, TEMPLATE_EDGE)

    @Test
    fun `sauvegarde puis relecture integrale`() {
        val store = store()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val saved = store.save("Médocs", png, template())
        assertEquals("Médocs", saved.label)
        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals(saved.id, listed.single().id)
        assertTrue(listed.single().png.contentEquals(png))
        assertTrue(listed.single().template.gray.contentEquals(template().gray))
        assertTrue(store.pngOf(saved.id).contentEquals(png))
    }

    @Test
    fun `nom vide devient Objet`() {
        val saved = store().save("  ", byteArrayOf(1), template())
        assertEquals(PictoStore.DEFAULT_LABEL, saved.label)
    }

    @Test
    fun `entrees abimees ignorees, valide garde`() {
        val root = Files.createTempDirectory("isee-picto-corrupt").toFile().apply { deleteOnExit() }
        val store = PictoStore(root)
        store.save("Bon", byteArrayOf(1), template())
        File(root, "picto-vide").mkdirs()
        File(root, "picto-court").mkdirs()
        File(File(root, "picto-court"), "label.txt").writeText("Court")
        File(File(root, "picto-court"), "picto.png").writeBytes(byteArrayOf(1))
        File(File(root, "picto-court"), "template.bin").writeBytes(byteArrayOf(0, 1))
        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("Bon", listed.single().label)
    }

    @Test
    fun `pngOf inconnu rend null`() {
        assertNull(store().pngOf("picto-inexistant"))
    }
}
