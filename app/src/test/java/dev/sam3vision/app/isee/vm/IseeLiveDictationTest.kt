package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.audio.PcmPreview
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IseeLiveDictationTest {
    @Test
    fun `une dictee partielle apparait pendant la tenue et disparait au silence`() = runBlocking {
        val h = Harness { Result.success("where is the door") }
        try {
            h.preview.start(Settings.Default)
            await { h.state.value.liveTranscript.isNotEmpty() }
            assertTrue(h.state.value.userSpeaking)
            h.sample = h.sample.copy(speaking = false, totalBytes = 40_000L)
            await { !h.state.value.userSpeaking }
            assertEquals("", h.state.value.liveTranscript)
            assertTrue(h.state.value.holding)
            assertFalse(h.state.value.transcribing)
        } finally { h.close() }
    }

    @Test
    fun `une reponse ASR tardive ne revient jamais apres relachement`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<String>>()
        val h = Harness {
            entered.complete(Unit)
            withContext(NonCancellable) { reply.await() }
        }
        try {
            h.preview.start(Settings.Default)
            withTimeout(2_000L) { entered.await() }
            h.preview.stop()
            h.state.value = h.state.value.copy(holding = false)
            reply.complete(Result.success("stale words"))
            delay(30)
            assertEquals("", h.state.value.liveTranscript)
            assertFalse(h.state.value.userSpeaking)
        } finally { reply.complete(Result.success("")); h.close() }
    }

    @Test
    fun `un echec de l'apercu ne bloque ni le micro ni la dictee finale`() = runBlocking {
        val called = CompletableDeferred<Unit>()
        val h = Harness { called.complete(Unit); Result.failure(IllegalStateException("offline")) }
        try {
            h.preview.start(Settings.Default)
            withTimeout(2_000L) { called.await() }
            assertTrue(h.state.value.holding)
            assertEquals("", h.state.value.liveTranscript)
            assertEquals(null, h.state.value.status)
        } finally { h.close() }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(2_000L) {
        while (!condition()) delay(2)
    }

    private class Harness(transcribe: suspend () -> Result<String>) {
        val state = MutableStateFlow(IseeUiState(holding = true))
        var sample = PcmPreview(ByteArray(32_000), 32_000L, speaking = true)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val client = object : SpeechClient {
            override suspend fun transcribe(wav: ByteArray) = transcribe()
        }
        val preview = IseeLiveDictation(scope, { sample }, { client }, { state.value = it(state.value) }, 5L, 2L)

        fun close() {
            preview.stop()
            scope.cancel()
        }
    }
}
