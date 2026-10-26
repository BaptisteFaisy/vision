package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.ihear.ChatEntry
import dev.sam3vision.app.ihear.IhearUiState
import dev.sam3vision.app.ihear.INTRO_SELECTION
import dev.sam3vision.app.media.MessageMultimedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le fil du chat : une ligne Room = deux bulles possibles, l'intro reste la première
 * bulle (index 0), et l'historique envoyé au LLM est borné. Ces tests fixent ce que
 * la découpe du ViewModel ne doit pas changer : mêmes bulles, même sélection.
 */
class IhearTranscriptTest {

    private val state = MutableStateFlow(IhearUiState())
    private val dao = FakeMessageDao()
    private val transcript = IhearTranscript(dao, { fn -> state.value = fn(state.value) })

    @Test
    fun `etat initial — aucune bulle, la selection reste sur l'intro`() = runBlocking {
        transcript.refresh()

        assertTrue("aucune bulle au départ", state.value.entries.isEmpty())
        assertEquals(INTRO_SELECTION, state.value.selected)
    }

    @Test
    fun `une ligne donne la bulle du user puis celle de l'IA`() {
        val row = MessageMultimedia(
            id = 7L,
            timestamp = 1L,
            texteUtilisateur = "  où est la porte ?  ",
            texteIA = "Devant toi",
        )

        val bubbles = bubblesOf(row)

        assertEquals(2, bubbles.size)
        assertEquals("où est la porte ?", bubbles[0].text)
        assertEquals("Devant toi", bubbles[1].text)
        assertTrue("bulle du user", bubbles[0] is ChatEntry.UserBubble)
        assertTrue("bulle de l'IA", bubbles[1] is ChatEntry.AiBubble)
    }

    @Test
    fun `message vide ignore — ni texte blanc ni texte nul ne font une bulle`() {
        val row = MessageMultimedia(id = 3L, timestamp = 1L, texteUtilisateur = "   ", texteIA = null)

        assertTrue("rien à afficher", bubblesOf(row).isEmpty())
    }

    @Test
    fun `ajout de message — la nouvelle ligne arrive en derniere bulle et y met la selection`() = runBlocking {
        dao.seed(MessageMultimedia(id = 0L, timestamp = 1L, texteUtilisateur = "premier", texteIA = "ok"))
        dao.insert(MessageMultimedia(timestamp = 2L, texteUtilisateur = "second", texteIA = "voilà"))

        transcript.refresh()

        assertEquals(4, state.value.entries.size)
        assertEquals("voilà", state.value.entries.last().text)
        assertEquals(4, state.value.selected)
    }

    @Test
    fun `l'historique du LLM alterne user et assistant et se borne aux derniers tours`() = runBlocking {
        for (i in 1..12) {
            dao.insert(MessageMultimedia(timestamp = i.toLong(), texteUtilisateur = "q$i", texteIA = "r$i"))
        }

        val turns = transcript.questionTurns(limit = 4)

        assertEquals(4, turns.size)
        assertEquals("q11", turns.first().content)
        assertEquals("r12", turns.last().content)
    }

    @Test
    fun `l'historique complet par defaut garde les vingt derniers messages`() = runBlocking {
        for (i in 1..15) {
            dao.insert(MessageMultimedia(timestamp = i.toLong(), texteUtilisateur = "q$i", texteIA = "r$i"))
        }

        assertEquals(20, transcript.questionTurns().size)
        assertEquals(20, IhearTranscript.CHAT_HISTORY)
    }
}