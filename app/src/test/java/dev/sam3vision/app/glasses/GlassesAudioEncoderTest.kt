package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le débit AAC se décide sans matériel : voix 16 kHz mono allégée, reste inchangé.
 * (`audioBitrateFor` est pur ; le codec MediaCodec, lui, ne se teste pas en JVM.)
 */
class GlassesAudioEncoderTest {

    @Test
    fun `la voix 16 kHz mono tient en 32 kbit par seconde`() {
        assertEquals(32_000, audioBitrateFor(sampleRate = 16_000, channels = 1))
    }

    @Test
    fun `le repli 48 kHz garde le debit historique`() {
        assertEquals(64_000, audioBitrateFor(sampleRate = 48_000, channels = 1))
    }

    @Test
    fun `la stereo garde le debit historique`() {
        assertEquals(64_000, audioBitrateFor(sampleRate = 16_000, channels = 2))
    }
}
