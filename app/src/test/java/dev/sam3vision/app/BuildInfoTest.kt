package dev.sam3vision.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le bloc « build » dit seulement si une clé est là — jamais sa valeur. */
class BuildInfoTest {

    @Test
    fun `le resume dit oui ou non pour chaque moteur`() {
        val summary = buildConfigSummary(llm = true, voice = false, sam = true, asr = false)
        assertTrue(summary.contains("LLM : oui"))
        assertTrue(summary.contains("voix : non"))
        assertTrue(summary.contains("dictée : non"))
        assertTrue(summary.contains("SAM : oui"))
    }

    @Test
    fun `un build sans aucune cle le dit sans rien inventer`() {
        val summary = buildConfigSummary(llm = false, voice = false, sam = false, asr = false)
        assertTrue(summary.contains("LLM : non"))
        assertFalse(summary.contains(": oui"))
    }
}
