package dev.sam3vision.app.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décision de survie du codec du direct ([shouldDropCodec], [isTransientCodecError]),
 * testée sur JVM. Le `MediaCodec` lui-même n'existe pas hors Android : la survie est
 * jugée ici, pas les appels gardés (qui ne s'éprouvent qu'au direct, sur lunettes).
 */
class GlassesCodecErrorsTest {

    @Test
    fun `une erreur non transitoire jette le codec`() {
        // Codec mort ou erreur irrécupérable : la frame suivante le recrée proprement.
        assertTrue(shouldDropCodec(false))
    }

    @Test
    fun `une erreur transitoire garde le codec`() {
        // Recréer le codec coûte 100 à 500 ms de gel : pas pour une erreur qui passe
        // toute seule — la frame suivante rejoue le même appel, sans gel.
        assertFalse(shouldDropCodec(true))
    }

    @Test
    fun `une erreur hors codec n est jamais transitoire`() {
        // `as? CodecException` seul : aucune méthode du stub Android n'est appelée.
        assertFalse(isTransientCodecError(IllegalStateException("codec mort")))
        assertFalse(isTransientCodecError(RuntimeException("panne")))
    }
}
