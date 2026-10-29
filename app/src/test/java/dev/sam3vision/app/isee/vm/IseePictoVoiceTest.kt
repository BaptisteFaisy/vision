package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.LiveVoiceRequest
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IseePictoVoiceTest {

    private val opened = mutableListOf<Pair<LiveVoiceRequest.Request, List<String>?>>()
    private val spoken = mutableListOf<String>()
    private var state = IseeUiState()

    private fun voice(labels: List<String> = listOf("medics")) = IseePictoVoice(
        armedLabels = { labels },
        settings = { Settings.Default.copy(speechLanguage = SpeechLanguage.Fr) },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        openLive = { request, prompts -> opened += Pair(request, prompts) },
    )

    @Test
    fun `pictogramme de x ouvre le direct en creation et confirme`() = runBlocking {
        assertTrue(voice().run("fais un pictogramme de ces médics"))
        assertEquals(listOf(Pair(LiveVoiceRequest.Request(pictoCreate = "medics"), null)), opened)
        assertEquals(listOf("Je crée le pictogramme."), spoken)
        assertEquals("Je crée le pictogramme.", state.status)
    }

    @Test
    fun `demo picto ouvre le direct en demo et confirme`() = runBlocking {
        assertTrue(voice().run("démo picto"))
        assertEquals(listOf(Pair(LiveVoiceRequest.Request(pictoDemo = true), null)), opened)
        assertEquals(listOf("Démo picto activée."), spoken)
    }

    @Test
    fun `mot visant un repere ouvre le direct en guidage muet`() = runBlocking {
        assertTrue(voice().run("médicament"))
        assertEquals(listOf(Pair(LiveVoiceRequest.Request(pictoHunt = "medicament"), null)), opened)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `mot maison seul sans repere ouvre la chasse sam muette`() = runBlocking {
        assertTrue(voice().run("clés"))
        assertEquals(listOf(Pair(LiveVoiceRequest.Request(), listOf("keys"))), opened)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `le repere prime sur sam`() = runBlocking {
        assertTrue(voice(listOf("clés")).run("clé"))
        assertEquals(1, opened.size)
        assertEquals("cle", opened.single().first.pictoHunt)
        assertNull(opened.single().second)
    }

    @Test
    fun `le reste ne declenche rien`() = runBlocking {
        val orders = voice()
        assertFalse(orders.run("pourquoi ?"))
        assertFalse(orders.run("va à la pharmacie"))
        assertFalse(orders.run("où sont les clés ?"))
        assertFalse(orders.run(""))
        assertTrue(opened.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `medicament sans repere part en chasse sam par l alias`() = runBlocking {
        assertTrue(voice(emptyList()).run("médicament"))
        assertEquals(listOf(Pair(LiveVoiceRequest.Request(), listOf("medicine box"))), opened)
    }

    @Test
    fun `mot inconnu sans repere ni concept ne declenche rien`() = runBlocking {
        assertFalse(voice(emptyList()).run("doudou"))
        assertTrue(opened.isEmpty())
    }
}
