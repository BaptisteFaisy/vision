package dev.sam3vision.core.live

import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LiveLlmClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLiveLlm : LiveLlmClient {
    var calls = 0
    var lastLabels: List<String> = emptyList()
    var lastPrevious: String = ""
    var result: Result<String> = Result.success("une personne traverse")

    override suspend fun describeFrame(
        jpegB64: String,
        labels: List<String>,
        previous: String,
    ): Result<String> {
        calls += 1
        lastLabels = labels
        lastPrevious = previous
        return result
    }
}

class LiveNarratorTest {

    private var now = 10_000L

    private fun narrator(llm: LiveLlmClient, intervalMs: Long = 2_000L) =
        LiveNarrator(llm, minIntervalMs = intervalMs, clock = { now })

    @Test
    fun `la premiere frame part puis la cadence est respectee`() {
        val llm = FakeLiveLlm()
        val subject = narrator(llm)
        assertTrue(subject.due())
        val text = runBlocking { subject.narrate("QUJD", listOf("personne")) }?.getOrNull()
        assertEquals("une personne traverse", text)
        assertEquals(1, llm.calls)
        assertFalse(subject.due())
        now += 1_999
        assertFalse(subject.due())
        now += 1
        assertTrue(subject.due())
    }

    @Test
    fun `la description precedente est transmise au tour suivant`() {
        val llm = FakeLiveLlm()
        val subject = narrator(llm)
        runBlocking { subject.narrate("QUJD", emptyList()) }
        now += 2_000
        runBlocking { subject.narrate("QUJD", emptyList()) }
        assertEquals("une personne traverse", llm.lastPrevious)
        assertEquals("une personne traverse", subject.previous)
    }

    @Test
    fun `une frame trop tot est ignoree sans appel`() {
        val llm = FakeLiveLlm()
        val subject = narrator(llm)
        runBlocking { subject.narrate("QUJD", emptyList()) }
        assertNull(runBlocking { subject.narrate("QUJD", emptyList()) })
        assertEquals(1, llm.calls)
    }

    @Test
    fun `un echec consomme la cadence et garde la description precedente`() {
        val llm = FakeLiveLlm()
        val subject = narrator(llm)
        runBlocking { subject.narrate("QUJD", emptyList()) }
        llm.result = Result.failure(ApiError.Timeout())
        now += 2_000
        val failed = runBlocking { subject.narrate("QUJD", emptyList()) }
        assertTrue(failed!!.isFailure)
        assertEquals("une personne traverse", subject.previous)
        assertFalse("la cadence doit rester consommée", subject.due())
    }

    @Test
    fun `une frame vide ne part jamais`() {
        val llm = FakeLiveLlm()
        assertNull(runBlocking { narrator(llm).narrate("   ", listOf("personne")) })
        assertEquals(0, llm.calls)
    }

    @Test
    fun `les labels sont bornes a huit`() {
        val llm = FakeLiveLlm()
        runBlocking { narrator(llm).narrate("QUJD", (0 until 12).map { "objet$it" }) }
        assertEquals(8, llm.lastLabels.size)
    }

    @Test
    fun `reset oublie la continuite et libere la cadence`() {
        val llm = FakeLiveLlm()
        val subject = narrator(llm)
        runBlocking { subject.narrate("QUJD", emptyList()) }
        subject.reset()
        assertEquals("", subject.previous)
        assertTrue(subject.due())
    }
}