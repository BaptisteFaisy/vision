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

    @Test
    fun `les reperes egares a la racine sont rapatries au listage`() {
        val filesDir = Files.createTempDirectory("isee-picto-loose").toFile().apply { deleteOnExit() }
        // Ancien chemin du Direct : dossier picto-* écrit à la racine.
        val loose = PictoStore(filesDir).save("Médocs", byteArrayOf(1, 2, 3), template())
        val store = PictoStore(File(filesDir, PICTOS_DIR))
        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("Médocs", listed.single().label)
        assertTrue(listed.single().png.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(File(File(filesDir, PICTOS_DIR), loose.id).isDirectory)
        assertTrue(!File(filesDir, loose.id).exists())
        // Idempotent : le second listage ne bouge plus rien.
        assertEquals(1, store.list().size)
    }

    @Test
    fun `le rapatriement ignore l'invalide et n'ecrase jamais`() {
        val filesDir = Files.createTempDirectory("isee-picto-clash").toFile().apply { deleteOnExit() }
        val store = PictoStore(File(filesDir, PICTOS_DIR))
        val kept = store.save("Garde", byteArrayOf(4), template())
        // Homonyme valide à la racine : laissé en place, le présent gagne.
        val clash = File(filesDir, kept.id).apply { mkdirs() }
        File(clash, "label.txt").writeText("Intrus")
        File(clash, "picto.png").writeBytes(byteArrayOf(9))
        File(clash, "template.bin").writeBytes(templateBlob())
        File(filesDir, "picto-vide").mkdirs()
        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("Garde", listed.single().label)
        assertTrue(clash.exists())
        assertTrue(File(filesDir, "picto-vide").exists())
    }

    @Test
    fun `deux dossiers la meme milliseconde ne se confondent pas`() {
        val root = Files.createTempDirectory("isee-picto-clash-ms").toFile().apply { deleteOnExit() }
        val store = PictoStore(root)
        val first = store.newPictoDir(1_000L).apply { mkdirs() }
        val second = store.newPictoDir(1_000L)
        assertEquals("picto-1000", first.name)
        assertEquals("picto-1001", second.name)
    }

    @Test
    fun `hors dossier pictos aucun rapatriement`() {
        val filesDir = Files.createTempDirectory("isee-picto-root").toFile().apply { deleteOnExit() }
        File(filesDir, "picto-vide").mkdirs()
        val store = PictoStore(filesDir)
        assertEquals(0, store.list().size)
        assertTrue(File(filesDir, "picto-vide").exists())
    }

    @Test
    fun `effacer supprime tous les reperes`() {
        val store = store()
        store.save("Médocs", byteArrayOf(1), template())
        store.save("Clés", byteArrayOf(2), template())
        assertEquals(2, store.list().size)
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `echec de sauvegarde ne laisse aucun dossier partiel`() {
        val fileRoot = Files.createTempFile("isee-picto-file", null).toFile().apply { deleteOnExit() }
        val store = PictoStore(fileRoot)
        assertTrue(runCatching { store.save("Cles", byteArrayOf(1), template()) }.isFailure)
        assertTrue(store.list().isEmpty())
    }

    private fun templateBlob(): ByteArray {
        val blob = ByteArray(2 + TEMPLATE_EDGE * TEMPLATE_EDGE)
        blob[0] = (TEMPLATE_EDGE shr 8).toByte()
        blob[1] = TEMPLATE_EDGE.toByte()
        return blob
    }
}
