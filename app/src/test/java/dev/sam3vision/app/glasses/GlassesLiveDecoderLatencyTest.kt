package dev.sam3vision.app.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décisions pures de latence du décodeur du direct ([needsCodecRebuild]), testées sur JVM.
 * Le `MediaCodec` lui-même n'existe pas hors Android : la recréation du codec est jugée ici,
 * pas l'attente de sortie (qui ne se mesure qu'à l'exécution, en images par seconde).
 */
class GlassesLiveDecoderLatencyTest {

    @Test
    fun `un codec vivant a la meme taille n est pas recree`() {
        // 504x896 = la taille du flux : une config qui la garde ne doit rien recréer,
        // sinon chaque VPS/SPS/PPS rejoué gèle l'image 100 à 500 ms.
        assertFalse(needsCodecRebuild(true, 504, 896, 504, 896))
    }

    @Test
    fun `un changement de largeur ou de hauteur recree le codec`() {
        assertTrue(needsCodecRebuild(true, 504, 896, 1008, 896))
        assertTrue(needsCodecRebuild(true, 504, 896, 504, 1792))
    }

    @Test
    fun `sans codec vivant il faut toujours en creer un`() {
        // Dimensions mémorisées à 0 après `dropCodecLocked` : aucun codec, donc création,
        // même à taille nulle (le repli est que le codec soit absent).
        assertTrue(needsCodecRebuild(false, 0, 0, 504, 896))
        assertTrue(needsCodecRebuild(false, 504, 896, 504, 896))
    }
}
