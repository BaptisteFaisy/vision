package dev.sam3vision.app.vm

import dev.sam3vision.app.MAX_IMAGE_EDGE
import dev.sam3vision.app.targetSize
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le chargement d'une photo avant envoi : le bord max de l'app (1024 px, jamais
 * d'agrandissement) est bien celui passé au décodeur, et une image illisible laisse
 * l'appelant décider du message — sans encodage inutile.
 *
 * Le faux décodeur applique la **vraie** règle de `decodeScaledExact`
 * ([targetSize] : même calcul, un seul endroit) : le test vérifie donc à la fois la
 * borne transmise et la taille qui en sort, sans `Bitmap` (indisponible en JVM).
 */
class AppImageLoaderTest {

    private fun loader(
        decode: (File, Int) -> String?,
        encode: (String) -> String = { it },
    ): AppImageLoader<String> = AppImageLoader(decode = decode, encode = encode)

    private fun scaledToEdge(width: Int, height: Int, edge: Int): String {
        val (scaledWidth, scaledHeight) = targetSize(width, height, 0, edge)
        return "${scaledWidth}x${scaledHeight}"
    }

    @Test
    fun `photo trop grande — decodeur appele au bord max, image reduite`() = runTest {
        var seenEdge = -1
        val photo = loader(
            decode = { _, edge -> seenEdge = edge; scaledToEdge(4000, 3000, edge) },
        ).load(File("photo.jpg"))

        assertEquals(MAX_IMAGE_EDGE, seenEdge)
        assertEquals("1024x768", photo?.image)
    }

    @Test
    fun `petite photo — jamais agrandie`() = runTest {
        val photo = loader(
            decode = { _, edge -> scaledToEdge(800, 600, edge) },
        ).load(File("petite.jpg"))

        assertEquals("800x600", photo?.image)
    }

    @Test
    fun `image illisible — rien, et aucun encodage`() = runTest {
        var encoded = 0
        val photo = loader(
            decode = { _, _ -> null },
            encode = { encoded++; it },
        ).load(File("casse.jpg"))

        assertNull(photo)
        assertEquals(0, encoded)
    }

    @Test
    fun `base64 — le resultat de l encodage de l image decodee`() = runTest {
        val photo = loader(
            decode = { _, _ -> "image-1024" },
            encode = { image -> "jpeg:$image" },
        ).load(File("photo.jpg"))

        assertEquals("image-1024", photo?.image)
        assertEquals("jpeg:image-1024", photo?.base64)
    }
}