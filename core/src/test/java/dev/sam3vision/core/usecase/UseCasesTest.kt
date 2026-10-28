package dev.sam3vision.core.usecase

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.SegmentResult
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SamClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val CHAIR = SegObject("chaise", 0.9f, listOf(1f, 2f, 3f, 4f), "AAAA")

private class FakeLlm : LlmClient {
    var lastLabels: List<String> = emptyList()
    var lastHistory: List<ChatTurn> = emptyList()
    var lastContext: String? = null
    var describeResult: Result<Analysis> =
        Result.success(Analysis("une pièce", listOf("intérieur"), emptyList()))
    var askResult: Result<String> = Result.success("réponse")

    override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> {
        lastLabels = labels
        return describeResult
    }

    override suspend fun ask(
        jpegB64: String,
        question: String,
        history: List<ChatTurn>,
        context: String?,
    ): Result<String> {
        lastHistory = history
        lastContext = context
        return askResult
    }
}

private class FakeSam(private val result: Result<SegmentResult>) : SamClient {
    override suspend fun segment(pngOrJpegB64: String, prompts: List<String>?) = result
    override suspend fun health(): Result<Boolean> = Result.success(true)
}

class UseCasesTest {

    @Test
    fun `analyze garde au plus 8 objets a l'ecran`() = runBlocking {
        val llm = FakeLlm()
        val many = (1..12).map { CHAIR.copy(label = "objet$it") }
        val sam = FakeSam(Result.success(SegmentResult(10, 10, many)))
        val analysis = AnalyzeUseCase(llm, sam).invoke("QUJD").getOrThrow()
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, analysis.objects.size)
        assertEquals((1..8).map { "objet$it" }, llm.lastLabels)
    }

    @Test
    fun `analyze suit le plafond regle dans les reglages`() = runBlocking {
        val llm = FakeLlm()
        val many = (1..9).map { CHAIR.copy(label = "objet$it") }
        val sam = FakeSam(Result.success(SegmentResult(10, 10, many)))
        val analysis = AnalyzeUseCase(llm, sam, maxObjects = 2).invoke("QUJD").getOrThrow()
        assertEquals(2, analysis.objects.size)
        assertEquals(listOf("objet1", "objet2"), llm.lastLabels)
    }

    @Test
    fun `analyze fusionne les objets segmentes et les labels`() = runBlocking {
        val llm = FakeLlm()
        val sam = FakeSam(Result.success(SegmentResult(10, 10, listOf(CHAIR))))
        val analysis = AnalyzeUseCase(llm, sam).invoke("QUJD").getOrThrow()

        assertEquals(1, analysis.objects.size)
        assertEquals("chaise", analysis.objects.single().label)
        assertEquals(listOf("chaise"), llm.lastLabels)
        assertEquals("une pièce", analysis.description)
    }

    @Test
    fun `analyze sans endpoint SAM appelle juste le LLM`() = runBlocking {
        val llm = FakeLlm()
        val analysis = AnalyzeUseCase(llm, sam = null).invoke("QUJD").getOrThrow()

        assertTrue(analysis.objects.isEmpty())
        assertTrue(llm.lastLabels.isEmpty())
    }

    @Test
    fun `analyze tolere un echec de segmentation`() = runBlocking {
        val llm = FakeLlm()
        val sam = FakeSam(Result.failure(ApiError.Network()))
        val analysis = AnalyzeUseCase(llm, sam).invoke("QUJD").getOrThrow()

        assertTrue(analysis.objects.isEmpty())
        assertEquals("une pièce", analysis.description)
    }

    @Test
    fun `onSamOutcome recoit le succes de segmentation`() = runBlocking {
        val seen = mutableListOf<Result<SegmentResult>?>()
        val sam = FakeSam(Result.success(SegmentResult(10, 10, listOf(CHAIR))))

        AnalyzeUseCase(FakeLlm(), sam, onSamOutcome = { seen += it }).invoke("QUJD").getOrThrow()

        assertEquals(listOf(CHAIR), seen.single()?.getOrNull()?.objects)
    }

    @Test
    fun `onSamOutcome recoit l echec de segmentation`() = runBlocking {
        val seen = mutableListOf<Result<SegmentResult>?>()
        val sam = FakeSam(Result.failure(ApiError.Network("coupé")))

        AnalyzeUseCase(FakeLlm(), sam, onSamOutcome = { seen += it }).invoke("QUJD").getOrThrow()

        assertEquals("coupé", seen.single()?.exceptionOrNull()?.message)
    }

    @Test
    fun `onSamOutcome vaut Timeout quand le budget expire`() = runBlocking {
        val seen = mutableListOf<Result<SegmentResult>?>()
        val hang = CompletableDeferred<Result<SegmentResult>>()
        val hanging = object : SamClient {
            override suspend fun segment(
                pngOrJpegB64: String,
                prompts: List<String>?,
            ): Result<SegmentResult> = hang.await()

            override suspend fun health(): Result<Boolean> = Result.success(true)
        }

        val analysis = AnalyzeUseCase(
            FakeLlm(),
            hanging,
            samBudgetMs = 20,
            onSamOutcome = { seen += it },
        ).invoke("QUJD").getOrThrow()

        assertTrue(seen.single()?.exceptionOrNull() is ApiError.Timeout)
        assertTrue("la photo n'attend pas", analysis.objects.isEmpty())
    }

    @Test
    fun `onSamOutcome est null sans client SAM`() = runBlocking {
        val seen = mutableListOf<Result<SegmentResult>?>()

        AnalyzeUseCase(FakeLlm(), sam = null, onSamOutcome = { seen += it }).invoke("QUJD").getOrThrow()

        assertEquals(listOf(null), seen)
    }

    @Test
    fun `analyze propage l erreur du LLM`() {
        val llm = FakeLlm().apply { describeResult = Result.failure(ApiError.InvalidKey()) }
        val failure = runBlocking { AnalyzeUseCase(llm, null).invoke("QUJD") }.exceptionOrNull()
        assertTrue(failure is ApiError.InvalidKey)
    }

    @Test
    fun `ask tronque l historique a 20 tours`() = runBlocking {
        val llm = FakeLlm()
        val history = (0 until 45).map { ChatTurn(Role.USER, "tour-$it") }
        AskUseCase(llm).invoke("QUJD", "question", history).getOrThrow()

        assertEquals(AskUseCase.MAX_HISTORY_TURNS, llm.lastHistory.size)
        assertEquals("tour-25", llm.lastHistory.first().content)
    }

    @Test
    fun `ask transmet le contexte camera au LLM`() = runBlocking {
        val llm = FakeLlm()
        AskUseCase(llm).invoke("QUJD", "qu'y avait-il à gauche ?", emptyList(), "il y a 5 s : personne").getOrThrow()

        assertEquals("il y a 5 s : personne", llm.lastContext)
    }

    @Test
    fun `ask sans contexte n'envoie rien de particulier`() = runBlocking {
        val llm = FakeLlm()
        AskUseCase(llm).invoke("QUJD", "question", emptyList()).getOrThrow()

        assertEquals(null, llm.lastContext)
    }

    @Test
    fun `nextHistory ajoute la question et la reponse et borne le total`() {
        val useCase = AskUseCase(FakeLlm())
        val history = (0 until 20).map { ChatTurn(Role.USER, "tour-$it") }
        val next = useCase.nextHistory(history, "q", "r")

        assertEquals(22, next.size)
        assertEquals(ChatTurn(Role.USER, "q"), next[next.size - 2])
        assertEquals(ChatTurn(Role.ASSISTANT, "r"), next.last())
        // historique deja plein : on ne depasse jamais 2 x 20 messages
        val full = (0 until 40).map { ChatTurn(Role.USER, "vieux-$it") }
        assertEquals(40, useCase.nextHistory(full, "q", "r").size)
    }
}
