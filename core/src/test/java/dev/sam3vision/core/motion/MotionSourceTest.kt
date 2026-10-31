package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de la couture A (lane L13, `docs/L13-FUSION.md` § 3) : `MotionSource.estimateSince`
 * rend un mouvement exploitable sur une scène **connue**, et `NoMotion` n'en rend aucun.
 *
 * Les scènes sont synthétiques et déterministes : le mouvement vrai est connu, donc ces tests
 * jugent l'estimateur, pas une séquence vidéo réelle — aucune mesure de terrain ici.
 */
class MotionSourceTest {

    private val source = BlockMotionSource()

    @Test
    fun `NoMotion ne rend aucun mouvement`() {
        val scene = MotionScenes.scene()
        assertNull(NoMotion.estimateSince(scene, MotionScenes.shifted(scene, 4, 2)))
    }

    @Test
    fun `grilles de tailles differentes refusees`() {
        val scene = MotionScenes.scene()
        val small = GrayMask(32, 16, ByteArray(32 * 16))
        assertNull(source.estimateSince(scene, small))
        assertNull(source.estimateSince(small, scene))
    }

    @Test
    fun `deux frames identiques rendent le mouvement nul`() {
        val scene = MotionScenes.scene()
        assertEquals(MotionSample.Zero, source.estimateSince(scene, MotionScenes.scene()))
    }

    @Test
    fun `translation retrouvee au pas de la grille fine`() {
        val scene = MotionScenes.scene()
        val sample = source.estimateSince(scene, MotionScenes.shifted(scene, 4, -2))
        assertNotNull(sample)
        val motion = sample!!
        assertEquals(4f, motion.dx, 2f) // pas de la grille fine : 2 px de plan
        assertEquals(-2f, motion.dy, 2f)
        assertEquals(1f, motion.scale, 0.02f)
        assertTrue("confiance trop basse : ${motion.confidence}", motion.confidence > 0.5f)
    }

    @Test
    fun `translation vers la gauche retrouvee`() {
        val scene = MotionScenes.scene()
        val sample = source.estimateSince(scene, MotionScenes.shifted(scene, -6, 3))
        assertNotNull(sample)
        val motion = sample!!
        assertEquals(-6f, motion.dx, 2f)
        assertEquals(3f, motion.dy, 2f)
        assertTrue("confiance trop basse : ${motion.confidence}", motion.confidence > 0.5f)
    }

    @Test
    fun `echelle reconnue sur un zoom connu`() {
        val scene = MotionScenes.scene()
        val sample = source.estimateSince(scene, MotionScenes.zoomed(scene, 1.06f))
        assertNotNull(sample)
        val motion = sample!!
        assertTrue("zoom non vu : échelle ${motion.scale}", motion.scale > 1.03f && motion.scale < 1.09f)
        assertTrue("confiance trop basse : ${motion.confidence}", motion.confidence > 0.3f)
    }

    @Test
    fun `scene plate ne donne pas d avis`() {
        val flat = GrayMask(MotionScenes.WIDTH, MotionScenes.HEIGHT, ByteArray(MotionScenes.WIDTH * MotionScenes.HEIGHT) { 90 })
        val other = GrayMask(MotionScenes.WIDTH, MotionScenes.HEIGHT, ByteArray(MotionScenes.WIDTH * MotionScenes.HEIGHT) { 91 })
        assertEquals(MotionSample.Zero, source.estimateSince(flat, flat))
        val avis = source.estimateSince(flat, other)
        assertTrue("avis sur une image plate : $avis", avis == null || avis.confidence < BlockMotionSource.TRUST_MIN)
    }

    @Test
    fun `confiance - le fond qui defile parle, l'image plate se tait`() {
        // Vocabulaire V1 : la confiance ordonne « fond change » et « fond identique ».
        val scene = MotionScenes.scene()
        val moving = source.estimateSince(scene, MotionScenes.shifted(scene, 4, -2))
        assertNotNull(moving)
        val motion = moving!!
        assertTrue(
            "défilement franc : confiance ${motion.confidence}",
            motion.confidence > BlockMotionSource.TRUST_MIN,
        )

        val flat = GrayMask(MotionScenes.WIDTH, MotionScenes.HEIGHT, ByteArray(MotionScenes.WIDTH * MotionScenes.HEIGHT) { 90 })
        val drifted = GrayMask(MotionScenes.WIDTH, MotionScenes.HEIGHT, ByteArray(MotionScenes.WIDTH * MotionScenes.HEIGHT) { 91 })
        val flatAvis = source.estimateSince(flat, drifted)
        assertTrue(
            "image plate : silence ou doute, jamais franc",
            flatAvis == null || flatAvis.confidence < motion.confidence,
        )
    }

    @Test
    fun `scaledBy convertit vers la grille du masque`() {
        val sample = MotionSample(dx = 2f, dy = -1f, scale = 1.02f, confidence = 0.8f)
        val mask = sample.scaledBy(2f)
        assertEquals(4f, mask.dx, 1e-6f)
        assertEquals(-2f, mask.dy, 1e-6f)
        assertEquals(1.02f, mask.scale, 1e-6f)
        assertEquals(0.8f, mask.confidence, 1e-6f)
    }
}
