package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.LiveVoiceRequest
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoPlaceMode
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
        created: MutableList<String> = mutableListOf(),
        hits: List<PictoHit> = emptyList(),
        placeMode: PictoPlaceMode = PictoPlaceMode.DEDANS,
    ) = IseePictoVoice(
        armedLabels = { labels },
        settings = { Settings.Default.copy(speechLanguage = language) },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        openLive = { request, prompts -> opened += Pair(request, prompts) },
        onFind = { found += it },
        onCreate = { created += it },
        hits = { hits },
        placeMode = { placeMode },
    )

    @Test
    fun `pictogramme de x cree sur place et confirme`() = runBlocking {
        val created = mutableListOf<String>()
        assertTrue(voice(created = created).run("fais un pictogramme de ces médics"))
        assertEquals(listOf("medics"), created)
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Je crée le pictogramme."), spoken)
        assertEquals("Je crée le pictogramme.", state.status)
    }

    @Test
    fun `pictogramme sans nom part au direct`() = runBlocking {
        val created = mutableListOf<String>()
        assertTrue(voice(created = created).run("fais un pictogramme de ça"))
        assertTrue(created.isEmpty())
        assertEquals(listOf(Pair(LiveVoiceRequest.Request(pictoCreate = ""), null)), opened)
        assertEquals(listOf("Je crée le pictogramme."), spoken)
    }

    @Test
    fun `create a picto for my keys cree sur place`() = runBlocking {
        val created = mutableListOf<String>()
        assertTrue(voice(language = SpeechLanguage.En, created = created).run("create a picto for my keys"))
        assertEquals(listOf("keys"), created)
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Creating the pictogram."), spoken)
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
    fun `chasse d'un repere visible dit ou il est`() = runBlocking {
        val hits = listOf(PictoHit(pictoId = "p1", label = "clés", x = 0f, y = 0.4f, w = 0.2f, h = 0.2f, score = 0.9f))
        assertTrue(voice(labels = listOf("clés"), hits = hits).run("clé"))
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Les clés sont devant toi, à 9 heures."), spoken)
        assertEquals("Les clés sont devant toi, à 9 heures.", state.status)
    }

    @Test
    fun `le reste ne declenche rien`() = runBlocking {
        val orders = voice()
        assertFalse(orders.run("pourquoi ?"))
        assertFalse(orders.run("va à la pharmacie"))
        assertFalse(orders.run("où est la sortie ?"))
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
    fun `dehors aucun ordre picto n'est consomme ni dit`() = runBlocking {
        val found = mutableListOf<String>()
        val created = mutableListOf<String>()
        val orders = voice(found = found, created = created, placeMode = PictoPlaceMode.DEHORS)
        assertFalse(orders.run("Find the keys"))
        assertFalse(orders.run("où sont mes clés"))
        assertFalse(orders.run("fais un pictogramme de ces médics"))
        assertFalse(orders.run("démo picto"))
        assertFalse(orders.run("médicament"))
        assertTrue(found.isEmpty())
        assertTrue(created.isEmpty())
        assertTrue(opened.isEmpty())
        assertTrue(spoken.isEmpty())
        assertTrue(state.status == null)
    }

    @Test
    fun `ou sont mes cles cherche sur place comme where`() = runBlocking {
        val found = mutableListOf<String>()
        assertTrue(voice(found = found).run("où sont mes clés"))
        assertEquals(listOf("keys"), found)
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Je cherche keys."), spoken)
        assertEquals("Je cherche keys.", state.status)
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
