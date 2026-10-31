package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.net.ScribeStreamSession
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dictée temps réel de l'appui long : la session se nourrit des morceaux du
 * micro, les partiels s'affichent sous la boule, et toute mort (ouverture,
 * réseau, repli) redémarre les aperçus batch — le moteur final retombe
 * alors sur le batch, sans régression.
 */
class IseeStreamDictationTest {

    @Test
    fun `le stream démarre avec une clé et nourrit la session`() {
        val h = Harness()
        try {
            val attempting = h.stream.start(Settings.Default.copy(ttsApiKey = "k")) { h.fallbacks++ }

            assertTrue(attempting)
            assertTrue(h.session.started)
            h.listener!!(ByteArray(1_000), 1_000)
            assertEquals(listOf(1_000), h.session.chunks)
            h.partial!!("where is")
            assertEquals("where is", h.state.value.liveTranscript)
            assertTrue(h.state.value.userSpeaking)
        } finally { h.close() }
    }

    @Test
    fun `sans clé ou drapeau coupé le batch reprend aussitôt`() {
        val h = Harness()
        try {
            assertFalse(h.stream.start(Settings.Default) { h.fallbacks++ })
            assertFalse(h.stream.start(Settings.Default.copy(ttsApiKey = "k", scribeRealtime = false)) { h.fallbacks++ })
            assertNull(h.listener)
            assertFalse(h.session.started)
        } finally { h.close() }
    }

    @Test
    fun `un partiel après relâchement est ignoré`() {
        val h = Harness()
        try {
            h.stream.start(Settings.Default.copy(ttsApiKey = "k")) { h.fallbacks++ }
            h.state.value = h.state.value.copy(holding = false)

            h.partial!!("trop tard")

            assertEquals("", h.state.value.liveTranscript)
        } finally { h.close() }
    }

    @Test
    fun `une mort de session redémarre les aperçus batch`() {
        val h = Harness()
        try {
            h.stream.start(Settings.Default.copy(ttsApiKey = "k")) { h.fallbacks++ }
            h.partial!!("where is")

            h.death!!()

            assertEquals(1, h.fallbacks)
            assertEquals("le moteur final retombe sur le batch", h.batch, h.stream.finalClient(h.batch))
            assertEquals("", h.state.value.liveTranscript)
            assertFalse(h.state.value.userSpeaking)
        } finally { h.close() }
    }

    @Test
    fun `un échec d'ouverture redémarre les aperçus batch`() {
        val h = Harness()
        try {
            h.session.startResult = Result.failure(IllegalStateException("refusé"))

            val attempting = h.stream.start(Settings.Default.copy(ttsApiKey = "k")) { h.fallbacks++ }

            assertTrue("la tentative a bien eu lieu", attempting)
            assertEquals(1, h.fallbacks)
            assertEquals(h.batch, h.stream.finalClient(h.batch))
            assertNull(h.stream.finalClient(null))
        } finally { h.close() }
    }

    @Test
    fun `le moteur final commite et rend le texte`() = runBlocking {
        val h = Harness()
        try {
            h.stream.start(Settings.Default.copy(ttsApiKey = "k")) { h.fallbacks++ }

            val text = h.stream.finalClient(h.batch)!!.transcribe(ByteArray(44)).getOrThrow()

            assertEquals("final words", text)
            assertTrue(h.session.committed)
            assertNull("commit réussi : le batch ne part pas", h.batch.heard)
        } finally { h.close() }
    }

    @Test
    fun `un commit en échec retombe sur le batch avec le wav complet`() = runBlocking {
        val h = Harness()
        try {
            h.session.commitResult = Result.failure(IllegalStateException("socket mort"))
            h.stream.start(Settings.Default.copy(ttsApiKey = "k")) { h.fallbacks++ }
            val wav = ByteArray(1_000)

            val text = h.stream.finalClient(h.batch)!!.transcribe(wav).getOrThrow()

            assertEquals("batch words", text)
            assertEquals("le batch reçoit le wav complet", wav, h.batch.heard)
        } finally { h.close() }
    }

    @Test
    fun `stop est idempotent et coupe tout`() {
        val h = Harness()
        try {
            h.stream.start(Settings.Default.copy(ttsApiKey = "k")) { h.fallbacks++ }

            h.stream.stop()
            h.stream.stop()

            assertTrue(h.session.aborted)
            assertNull(h.listener)
            assertEquals(0, h.fallbacks)
        } finally { h.close() }
    }

    @Test
    fun `les morceaux d'une tentative périmée sont ignorés`() {
        val h = Harness()
        try {
            h.stream.start(Settings.Default.copy(ttsApiKey = "k")) { h.fallbacks++ }
            val stale = h.listener!!
            h.stream.stop()

            stale(ByteArray(500), 500)

            assertTrue(h.session.chunks.isEmpty())
        } finally { h.close() }
    }

    private class Harness {
        val state = MutableStateFlow(IseeUiState(holding = true))
        val session = FakeSession()
        val batch = FakeBatch()
        var listener: ((ByteArray, Int) -> Unit)? = null
        var partial: ((String) -> Unit)? = null
        var death: (() -> Unit)? = null
        var fallbacks = 0
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stream = IseeStreamDictation(
            scope,
            { listener = it },
            { state.value = it(state.value) },
        ) { _, onPartial, onDeath ->
            partial = onPartial
            death = onDeath
            session
        }

        fun close() {
            stream.stop()
            scope.cancel()
        }
    }

    private class FakeSession : ScribeStreamSession {
        var startResult: Result<Unit> = Result.success(Unit)
        var commitResult: Result<String> = Result.success("final words")
        val chunks = mutableListOf<Int>()
        var started = false
        var committed = false
        var aborted = false
        private var live = true

        override suspend fun start(): Result<Unit> {
            started = true
            if (startResult.isFailure) live = false
            return startResult
        }

        override fun sendPcm(chunk: ByteArray, count: Int): Boolean {
            if (!live) return false
            chunks += count
            return true
        }

        override suspend fun commitAndFinal(): Result<String> {
            committed = true
            return commitResult
        }

        override fun abort() {
            aborted = true
            live = false
        }

        override val isLive: Boolean get() = live
    }

    private class FakeBatch : SpeechClient {
        var heard: ByteArray? = null

        override suspend fun transcribe(wav: ByteArray): Result<String> {
            heard = wav
            return Result.success("batch words")
        }
    }
}
