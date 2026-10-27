package dev.sam3vision.app.glasses.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Politique de frame ([frameTimestampMs], [scaledDimensions], [cropTo16x9])
 * testée sur JVM : ces décisions étaient enfouies dans [GlassesFrameEncoder],
 * où les appeler demandait un `Bitmap` et `SystemClock`.
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

    // ---- Recadrage 16:9 -----------------------------------------------------

    @Test
    fun `le portrait des lunettes perd le haut et le bas`() {
        // 504x896 = VideoQuality.MEDIUM : 504 de large → 283 de haut, centré.
        assertEquals(CropRect(0, 306, 504, 283), cropTo16x9(504, 896))
    }

    @Test
    fun `un 16-9 exact ne bouge pas`() {
        assertNull(cropTo16x9(1280, 720))
        assertNull(cropTo16x9(1920, 1080))
        assertNull(cropTo16x9(16, 9))
    }

    @Test
    fun `un carre perd le haut et le bas`() {
        assertEquals(CropRect(0, 109, 500, 281), cropTo16x9(500, 500))
    }

    @Test
    fun `un paysage plus large que 16-9 perd les cotes`() {
        assertEquals(CropRect(249, 0, 1422, 800), cropTo16x9(1920, 800))
    }

    @Test
    fun `un recadrage impossible garde l image entiere`() {
        assertNull(cropTo16x9(0, 480))
        assertNull(cropTo16x9(640, 0))
        assertNull("1 px de large ne fait pas un 16:9", cropTo16x9(1, 100))
    }
}
