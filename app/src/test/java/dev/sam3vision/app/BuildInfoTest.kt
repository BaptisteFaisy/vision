package dev.sam3vision.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "build" block only says whether a key is there — never its value. */
class BuildInfoTest {

    @Test
    fun `the summary says yes or no for each engine`() {
        val summary = buildConfigSummary(llm = true, voice = false, sam = true, asr = false)
        assertTrue(summary.contains("LLM: yes"))
        assertTrue(summary.contains("voice: no"))
        assertTrue(summary.contains("dictation: no"))
        assertTrue(summary.contains("SAM: yes"))
    }

    @Test
    fun `a keyless build says so without inventing anything`() {
        val summary = buildConfigSummary(llm = false, voice = false, sam = false, asr = false)
        assertTrue(summary.contains("LLM: no"))
        assertFalse(summary.contains(": yes"))
    }
}
