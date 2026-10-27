package dev.sam3vision.app.glasses.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Politique de frame ([frameTimestampMs], [scaledDimensions]) testée sur JVM :
 * ces deux décisions étaient enfouies dans [GlassesFrameEncoder], où les appeler
 * demandait un `Bitmap` et `SystemClock`.
 */
class FramePolicyTest {

    // ---- Horodatage ----------------------------------------------------------

    @Test
    fun `les microsecondes du SDK deviennent des millisecondes`() {
        assertEquals(1_000L, frameTimestampMs(presentationTimeUs = 1_000_000L, fallbackMs = 42L))
        assertEquals(0L, frameTimestampMs(presentationTimeUs = 999L, fallbackMs = 42L))
    }

    @Test
    fun `sans horodatage SDK on garde l horloge de repli`() {
        assertEquals(7_777L, frameTimestampMs(presentationTimeUs = 0L, fallbackMs = 7_777L))
        assertEquals(
            "un horodatage négatif n'est pas une date",
            7_777L,
            frameTimestampMs(presentationTimeUs = -5L, fallbackMs = 7_777L),
        )
    }

    // ---- Réduction sous MAX_IMAGE_EDGE --------------------------------------

    @Test
    fun `une image sous la borne n est pas reduite`() {
        // 504x896 = VideoQuality.MEDIUM, le flux que le spike demande.
        assertNull(scaledDimensions(504, 896))
        assertNull("la borne elle-même est déjà bonne", scaledDimensions(1024, 1024))
    }

    @Test
    fun `l image est reduite sous la borne en gardant le ratio`() {
        assertEquals(1024 to 576, scaledDimensions(1280, 720))
        assertEquals("portrait : la hauteur est le plus grand côté", 341 to 1024, scaledDimensions(1000, 3000))
    }

    @Test
    fun `une image tres allongee garde au moins un pixel`() {
        // 1 px de haut donnerait 0,25 px après réduction : on garde 1.
        assertEquals(1024 to 1, scaledDimensions(4096, 1))
        assertEquals(1 to 1024, scaledDimensions(1, 4096))
    }

    @Test
    fun `les dimensions inexploitables ne sont pas reduites`() {
        assertNull(scaledDimensions(0, 480))
        assertNull(scaledDimensions(640, 0))
        assertNull(scaledDimensions(-2, -2))
        assertNull("une borne nulle n'a pas de sens", scaledDimensions(2000, 1000, maxEdge = 0))
    }
}
