package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.LiveVoiceRequest
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IseePictoVoiceTest {

    private val opened = mutableListOf<Pair<LiveVoiceRequest.Request, List<String>?>>()
    private val spoken = mutableListOf<String>()
    private var state = IseeUiState()

    private fun voice(
        labels: List<String> = listOf("medics"),
        language: SpeechLanguage = SpeechLanguage.Fr,
        found: MutableList<String> = mutableListOf(),
    ) = IseePictoVoice(
        armedLabels = { labels },
        settings = { Settings.Default.copy(speechLanguage = language) },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        openLive = { request, prompts -> opened += Pair(request, prompts) },
        onFind = { found += it },
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
    fun `mot visant un repere reste sur place et confirme`() = runBlocking {
        assertTrue(voice().run("médicament"))
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Je cherche medicament."), spoken)
        assertEquals("Je cherche medicament.", state.status)
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
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Je cherche cle."), spoken)
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

    @Test
    fun `find the keys cherche sur place sans ouvrir le direct`() = runBlocking {
        val found = mutableListOf<String>()
        assertTrue(voice(language = SpeechLanguage.En, found = found).run("Find the keys"))
        assertEquals(listOf("keys"), found)
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Looking for keys."), spoken)
        assertEquals("Looking for keys.", state.status)
    }

    @Test
    fun `find avec repere arme chasse au lieu de regenerer`() = runBlocking {
        val found = mutableListOf<String>()
        assertTrue(voice(listOf("keys"), found = found).run("find the keys"))
        assertTrue(found.isEmpty())
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Je cherche keys."), spoken)
    }

    @Test
    fun `find sans objet ne declenche rien`() = runBlocking {
        val found = mutableListOf<String>()
        val orders = voice(found = found)
        assertFalse(orders.run("find"))
        assertFalse(orders.run("I found my keys"))
        assertTrue(found.isEmpty())
        assertTrue(opened.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `where are my keys cherche sur place sans ouvrir le direct`() = runBlocking {
        val found = mutableListOf<String>()
        assertTrue(voice(emptyList(), SpeechLanguage.En, found).run("Where are my keys"))
        assertEquals(listOf("keys"), found)
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Looking for keys."), spoken)
        assertEquals("Looking for keys.", state.status)
    }

    @Test
    fun `where avec repere arme chasse sur place au lieu de regenerer`() = runBlocking {
        val found = mutableListOf<String>()
        assertTrue(voice(listOf("keys"), SpeechLanguage.En, found).run("Where are my keys"))
        assertTrue(found.isEmpty())
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Looking for keys."), spoken)
        assertEquals("Looking for keys.", state.status)
    }

    @Test
    fun `where hors maison ne declenche rien`() = runBlocking {
        val found = mutableListOf<String>()
        val orders = voice(emptyList(), SpeechLanguage.En, found)
        assertFalse(orders.run("Where is the exit?"))
        assertFalse(orders.run("Where am I?"))
        assertFalse(orders.run("Where is it?"))
        assertTrue(found.isEmpty())
        assertTrue(opened.isEmpty())
        assertTrue(spoken.isEmpty())
    }
}
