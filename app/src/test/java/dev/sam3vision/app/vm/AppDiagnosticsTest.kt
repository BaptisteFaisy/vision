package dev.sam3vision.app.vm

import dev.sam3vision.app.UiState
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les tests des Réglages : un réglage manquant est refusé **localement** — message clair
 * et aucun client construit, donc aucun appel réseau — et un réglage présent rend le
 * verdict du vrai client dans `statusLine`.
 */
class AppDiagnosticsTest {

    private val screen = FakeScreen(UiState(settings = ConfiguredSettings))
    private var samUrlCalled = ""

    private fun diagnostics(
        llm: LlmClient = FakeLlm(),
        speech: (Settings) -> SpeechClient? = { null },
        samUrl: (Settings) -> String = { "https://sam.test" },
        sam: SamClient = FakeSam(),
        noteHealth: (String, Result<Boolean>) -> Unit = { _, _ -> },
    ): AppDiagnostics = AppDiagnostics(
        state = { screen.value },
        update = screen.update,
        llm = { llm },
        speech = speech,
        probeImage = { "mini-image" },
        samUrl = samUrl,
        samClient = { url -> samUrlCalled = url; sam },
        noteHealth = noteHealth,
    )

    @Test
    fun `SAM sans URL — message clair, aucun appel reseau`() = runTest {
        val sam = FakeSam()

        diagnostics(samUrl = { "" }, sam = sam).testSam()

        assertEquals(AppStatusText.SAM_NO_URL, screen.value.statusLine)
        assertEquals("aucun client construit", "", samUrlCalled)
        assertEquals(0, sam.healthCalls)
    }

    @Test
    fun `SAM joignable — verdict OK sur l endpoint des reglages`() = runTest {
        val sam = FakeSam(onHealth = Result.success(true))

        diagnostics(sam = sam).testSam()

        assertEquals("https://sam.test", samUrlCalled)
        assertEquals(1, sam.healthCalls)
        assertEquals(AppStatusText.SAM_OK, screen.value.statusLine)
    }

    @Test
    fun `SAM muet — verdict KO`() = runTest {
        val sam = FakeSam(onHealth = Result.success(false))

        diagnostics(sam = sam).testSam()

        assertEquals(AppStatusText.SAM_HEALTH_FALSE, screen.value.statusLine)
    }

    @Test
    fun `SAM injoignable — motif du client dans le verdict`() = runTest {
        val sam = FakeSam(onHealth = Result.failure(ApiError.Network("réseau")))

        diagnostics(sam = sam).testSam()

        assertEquals("SAM KO — réseau", screen.value.statusLine)
    }

    @Test
    fun `SAM teste — issue notee pour le moniteur`() = runTest {
        val noted = mutableListOf<Pair<String, Result<Boolean>>>()
        val sam = FakeSam(onHealth = Result.success(true))

        diagnostics(sam = sam, noteHealth = { url, result -> noted += url to result }).testSam()

        assertEquals(listOf("https://sam.test"), noted.map { it.first })
        assertEquals(true, noted.single().second.getOrNull())
    }

    @Test
    fun `SAM injoignable — echec note pour le moniteur`() = runTest {
        val noted = mutableListOf<Pair<String, Result<Boolean>>>()
        val sam = FakeSam(onHealth = Result.failure(ApiError.Network("réseau")))

        diagnostics(sam = sam, noteHealth = { url, result -> noted += url to result }).testSam()

        assertEquals("https://sam.test", noted.single().first)
        assertEquals("réseau", noted.single().second.exceptionOrNull()?.message)
    }

    @Test
    fun `SAM sans URL — rien n est note`() = runTest {
        val noted = mutableListOf<Pair<String, Result<Boolean>>>()

        diagnostics(samUrl = { "" }, noteHealth = { url, result -> noted += url to result }).testSam()

        assertTrue("aucun appel : rien à noter", noted.isEmpty())
    }

    @Test
    fun `ASR sans moteur — message clair, aucun appel reseau`() = runTest {
        diagnostics(speech = { null }).testAsr()

        assertEquals(AppStatusText.ASR_NO_ENGINE, screen.value.statusLine)
    }

    @Test
    fun `LLM — description tronquee dans le verdict`() = runTest {
        val long = "une table basse en bois ".repeat(6)
        val llm = FakeLlm(onDescribe = { Result.success(analysis(long)) })

        diagnostics(llm = llm).testLlm()

        assertEquals(1, llm.describeCalls)
        assertEquals("LLM OK — « ${long.take(70)} »", screen.value.statusLine)
    }

    @Test
    fun `LLM en echec — verdict KO`() = runTest {
        val llm = FakeLlm(onDescribe = { Result.failure(ApiError.InvalidKey("clé invalide (401)")) })

        diagnostics(llm = llm).testLlm()

        assertEquals("LLM KO — clé invalide (401)", screen.value.statusLine)
    }
}