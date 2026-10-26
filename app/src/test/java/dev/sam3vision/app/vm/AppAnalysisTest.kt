package dev.sam3vision.app.vm

import android.graphics.Bitmap
import dev.sam3vision.app.Phase
import dev.sam3vision.app.UiState
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.SegmentResult
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SamClient
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le chemin SAM + LLM : un échec de segmentation n'empêche pas la description (l'image
 * est décrite sans objet), un échec du LLM met la vue en échec **et la photo reste là**,
 * donc « Réessayer » repart. Rien de tout ça ne doit changer en découpant le ViewModel.
 */
class AppAnalysisTest {

    private val screen = FakeScreen()
    private val announced = mutableListOf<List<SegObject>>()
    private var overlayCalls = 0

    private fun runner(llm: LlmClient, sam: SamClient? = null): AppAnalysis = AppAnalysis(
        state = { screen.value },
        update = screen.update,
        llm = { llm },
        sam = { sam },
        overlays = { overlayCalls++; emptyList<Bitmap>() },
        announce = { objects, _ -> announced += objects },
    )

    private fun runner(settings: dev.sam3vision.core.settings.Settings, llm: LlmClient): AppAnalysis {
        screen.state.update { it.copy(settings = settings) }
        return runner(llm)
    }

    @Test
    fun `analyse reussie — etat pret, objets segmentes, positions annoncees`() = runTest {
        val chaise = SegObject("chaise", 0.9f, listOf(0f, 0f, 1f, 1f), "masque")
        var labelsSeen: List<String> = emptyList()
        val llm = FakeLlm(onDescribe = { labels ->
            labelsSeen = labels
            Result.success(analysis("une chaise", listOf(chaise)))
        })
        val sam = FakeSam(onSegment = Result.success(SegmentResult(10, 10, listOf(chaise))))

        runner(llm, sam).analyze("photo")

        assertEquals(Phase.Ready, screen.value.phase)
        assertEquals(listOf(chaise), screen.value.objects)
        assertEquals("les libellés SAM partent au LLM", listOf("chaise"), labelsSeen)
        assertTrue(screen.value.segmentationActive)
        assertNull("des objets : aucune note", screen.value.segmentationNote)
        assertEquals("masques demandés", 1, overlayCalls)
        assertEquals("positions annoncées", listOf(listOf(chaise)), announced)
    }

    @Test
    fun `echec SAM — la description LLM arrive quand meme`() = runTest {
        val llm = FakeLlm(onDescribe = { Result.success(analysis("un mur")) })
        val sam = FakeSam(onSegment = Result.failure(ApiError.Network()))

        runner(llm, sam).analyze("photo")

        assertEquals(1, sam.segmentCalls)
        assertEquals(1, llm.describeCalls)
        assertEquals(Phase.Ready, screen.value.phase)
        assertEquals("un mur", screen.value.analysis?.description)
        assertTrue("aucun objet sans segmentation", screen.value.objects.isEmpty())
        assertFalse(screen.value.segmentationActive)
        assertEquals("Aucun objet segmenté sur cette image.", screen.value.segmentationNote)
    }

    @Test
    fun `echec LLM — vue en echec avec le message du client`() = runTest {
        val llm = FakeLlm(onDescribe = { Result.failure(ApiError.Timeout("délai dépassé côté LLM")) })

        runner(llm, FakeSam()).analyze("photo")

        val phase = screen.value.phase
        assertTrue("phase d'échec", phase is Phase.Failed)
        assertEquals("délai dépassé côté LLM", (phase as Phase.Failed).message)
        assertNull("aucune analyse posée", screen.value.analysis)
        assertFalse(screen.value.segmentationActive)
        assertTrue("aucune position annoncée", announced.isEmpty())
    }

    @Test
    fun `echec sans message — motif par defaut lisible`() = runTest {
        val llm = FakeLlm(onDescribe = { Result.failure(RuntimeException()) })

        runner(llm).analyze("photo")

        assertEquals(Phase.Failed(AppStatusText.ANALYSIS_FAILED), screen.value.phase)
    }

    @Test
    fun `echec LLM puis reessai — la deuxieme analyse reussit`() = runTest {
        var broken = true
        val llm = FakeLlm(onDescribe = {
            if (broken) Result.failure(ApiError.Network("réseau")) else Result.success(analysis("un bureau"))
        })
        val runner = runner(llm)

        runner.analyze("photo")
        assertTrue(screen.value.phase is Phase.Failed)

        // Ce que fait retryAnalysis() : la photo est toujours là, la vue repart.
        screen.state.update { it.copy(phase = Phase.Analyzing) }
        broken = false
        runner.analyze("photo")

        assertEquals(Phase.Ready, screen.value.phase)
        assertEquals("un bureau", screen.value.analysis?.description)
    }

    @Test
    fun `nouvelle analyse — le chat de l ancienne photo est vide`() = runTest {
        val llm = FakeLlm(onDescribe = { Result.success(analysis("une table")) })
        screen.state.update {
            it.copy(chat = listOf(ChatTurn(Role.USER, "et là ?")), analysis = analysis("avant"))
        }

        runner(llm).analyze("photo")

        assertTrue("chat remis à zéro", screen.value.chat.isEmpty())
        assertEquals("une table", screen.value.analysis?.description)
    }
}