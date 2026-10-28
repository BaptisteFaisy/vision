package dev.sam3vision.app.glasses.bench

import dev.sam3vision.app.glasses.CropRect
import dev.sam3vision.app.glasses.FRAME_THROTTLE_MS
import dev.sam3vision.app.glasses.FrameVerdict
import dev.sam3vision.app.glasses.cropTo16x9
import dev.sam3vision.app.glasses.frameTimestampMs
import dev.sam3vision.app.glasses.frameVerdict
import dev.sam3vision.app.glasses.scaledDimensions
import dev.sam3vision.app.glasses.throttleDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Politique de frame partagée (horodatage, réduction, recadrage, étranglement,
 * verdict) testée sur JVM : ces décisions étaient enfouies dans
 * [GlassesFrameEncoder] ou le cycle du banc, où les appeler demandait un
 * `Bitmap` et `SystemClock` — le collecteur de production les réutilise telles
 * quelles.
 */
class GlassesFramePolicyTest {

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

    // ---- Étranglement du flux ----------------------------------------------

    @Test
    fun `la premiere frame part sans attendre`() {
        // lastSentAtMs = 0 : l'horloge a déjà dépassé la fenêtre depuis longtemps.
        assertEquals(0L, throttleDelayMs(lastSentAtMs = 0L, nowMs = 100_000L))
    }

    @Test
    fun `deux frames au meme instant gardent toute la fenetre`() {
        assertEquals(
            FRAME_THROTTLE_MS,
            throttleDelayMs(lastSentAtMs = 10_000L, nowMs = 10_000L),
        )
    }

    @Test
    fun `une frame juste avant la fin de fenetre attend le reste`() {
        assertEquals(
            1L,
            throttleDelayMs(lastSentAtMs = 10_000L, nowMs = 10_000L + FRAME_THROTTLE_MS - 1),
        )
    }

    @Test
    fun `une frame apres la fenetre n attend plus`() {
        assertEquals(
            0L,
            throttleDelayMs(lastSentAtMs = 10_000L, nowMs = 10_000L + FRAME_THROTTLE_MS),
        )
        assertEquals(
            0L,
            throttleDelayMs(lastSentAtMs = 10_000L, nowMs = 10_000L + 10 * FRAME_THROTTLE_MS),
        )
    }

    @Test
    fun `une horloge qui recule ne rapproche pas l envoi`() {
        // nowMs < lastSentAtMs : l'attente dépasse la fenêtre, elle n'est pas bornée
        // vers le haut (mieux vaut attendre que d'envoyer deux frames d'un coup).
        assertEquals(FRAME_THROTTLE_MS + 1_000L, throttleDelayMs(lastSentAtMs = 11_000L, nowMs = 10_000L))
    }

    @Test
    fun `la fenetre par defaut est celle du LLM`() {
        assertEquals(1_500L, FRAME_THROTTLE_MS)
        assertEquals(FRAME_THROTTLE_MS, throttleDelayMs(lastSentAtMs = 5L, nowMs = 5L))
    }

    // ---- Verdict sur une frame brute ---------------------------------------

    @Test
    fun `une frame brute exploitable est encodee`() {
        assertEquals(FrameVerdict.ENCODE, frameVerdict(false, false, 640, 480))
        // Cas dégénéré mais valide : 1x1 ne doit pas être jeté pour ses dimensions.
        assertEquals(FrameVerdict.ENCODE, frameVerdict(false, false, 1, 1))
    }

    @Test
    fun `une frame compressee est ecartee avant tout decodage`() {
        assertEquals(FrameVerdict.DROP_COMPRESSED, frameVerdict(true, false, 640, 480))
    }

    @Test
    fun `la compression prime sur le drapeau de configuration`() {
        assertEquals(FrameVerdict.DROP_COMPRESSED, frameVerdict(true, true, 640, 480))
    }

    @Test
    fun `une frame de configuration codec est ecartee`() {
        assertEquals(FrameVerdict.DROP_CODEC_CONFIG, frameVerdict(false, true, 640, 480))
    }

    @Test
    fun `des dimensions nulles ou negatives ecartent la frame`() {
        assertEquals(FrameVerdict.DROP_DIMENSIONS, frameVerdict(false, false, 0, 480))
        assertEquals(FrameVerdict.DROP_DIMENSIONS, frameVerdict(false, false, 640, 0))
        assertEquals(FrameVerdict.DROP_DIMENSIONS, frameVerdict(false, false, -2, -2))
    }
}
