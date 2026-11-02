package dev.sam3vision.app.traverse

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraverseClipStoreTest {

    private fun store(): DirTraverseClipStore =
        DirTraverseClipStore(Files.createTempDirectory("traverse-clips").toFile())

    @Test
    fun `pathFor - stable par phrase, distinct par texte`() {
        val store = store()
        assertEquals(store.pathFor("Rouge."), store.pathFor("Rouge."))
        assertNotEquals(store.pathFor("Rouge."), store.pathFor("Vert."))
        assertTrue(store.pathFor("Rouge.").endsWith(".wav"))
    }

    @Test
    fun `clipFor - null quand absent ou vide`() {
        val store = store()
        assertNull(store.clipFor("Rouge."))
        java.io.File(store.pathFor("Rouge.")).writeBytes(ByteArray(0))
        assertNull("fichier vide = inutilisable", store.clipFor("Rouge."))
    }

    @Test
    fun `clipFor - chemin quand le clip est ecrit`() {
        val store = store()
        val path = store.pathFor("Rouge.")
        java.io.File(path).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(path, store.clipFor("Rouge."))
    }

    @Test
    fun `sha16 - seize hex stables`() {
        val hash = DirTraverseClipStore.sha16("Rouge.")
        assertEquals(16, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hash, DirTraverseClipStore.sha16("Rouge."))
        assertNotEquals(hash, DirTraverseClipStore.sha16("Vert."))
    }
}
