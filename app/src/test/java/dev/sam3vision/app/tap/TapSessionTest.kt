package dev.sam3vision.app.tap

import androidx.lifecycle.LifecycleOwner
import dev.sam3vision.app.MicBus
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.TtsClient
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate d'envoi et raisons de « Failed » de la session tap. Zéro caméra, zéro réseau,
 * zéro `Context` : l'enregistreur est un faux, le TTS est un faux (aucun clip, mot tu),
 * l'expéditeur rejoue une issue fixée et l'horloge est pilotée à la main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TapSessionTest {

    private val tmp: File = Files.createTempDirectory("tap-session-test").toFile()

    @After
    fun tearDown() {
        MicBus.reset()
        tmp.deleteRecursively()
    }

    @Test
    fun `envoi bloque echoue explicitement sans expedier ni enregistrer`() = runTest {
        val harness = harness(
            scope = this,
            sendBlocked = { "endpoint SAM 3.1 vide : renseigne sam31.endpoint dans local.properties" },
        )

        harness.session.onTap()
        advanceUntilIdle()

        val failed = harness.statuses.single() as TapStatus.Failed
        assertTrue(failed.reason.contains("sam31.endpoint"))
        assertEquals(0, harness.calls())
        assertTrue(harness.video.started == null)
    }

    @Test
    fun `permissions refusees echouent explicitement`() = runTest {
        val harness = harness(scope = this, permissionsGranted = { false })

        harness.session.onTap()
        advanceUntilIdle()

        assertEquals(TapStatus.Failed("permission caméra ou micro refusée"), harness.statuses.single())
        assertEquals(0, harness.calls())
    }

    @Test
    fun `double tap heureux envoie le clip et affiche le resume`() = runTest {
        val harness = harness(scope = this, outcome = Result.success("Un chat traverse."))

        harness.session.onTap()
        runCurrent()
        harness.now = TapConfig.MIN_RECORDING_MS + 100
        harness.session.onTap()
        advanceUntilIdle()

        assertEquals(
            listOf(TapStatus.Recording, TapStatus.Sending, TapStatus.Sent("Un chat traverse.")),
            harness.statuses,
        )
        assertEquals(1, harness.calls())
    }

    @Test
    fun `echec 404 remonte la raison http`() = runTest {
        val harness = harness(scope = this, outcome = Result.failure(ApiError.Http(404, "Not Found")))

        harness.session.onTap()
        runCurrent()
        harness.now = TapConfig.MIN_RECORDING_MS + 100
        harness.session.onTap()
        advanceUntilIdle()

        val failed = harness.statuses.last() as TapStatus.Failed
        assertTrue(failed.reason.contains("404"))
    }

    @Test
    fun `echec 429 remonte la raison limite`() = runTest {
        val harness = harness(scope = this, outcome = Result.failure(ApiError.RateLimited()))

        harness.session.onTap()
        runCurrent()
        harness.now = TapConfig.MIN_RECORDING_MS + 100
        harness.session.onTap()
        advanceUntilIdle()

        assertEquals(TapStatus.Failed("limite atteinte (429)"), harness.statuses.last())
    }

    @Test
    fun `release sur idle ne fait rien et libere le micro`() = runTest {
        val harness = harness(scope = this, sendBlocked = { "bloqué" })

        harness.session.release()
        advanceUntilIdle()

        assertTrue(harness.statuses.isEmpty())
        assertEquals(MicBus.Owner.NONE, MicBus.owner)
    }

    private fun harness(
        scope: TestScope,
        outcome: Result<String> = Result.success("résumé"),
        sendBlocked: () -> String? = { null },
        permissionsGranted: () -> Boolean = { true },
    ): Harness {
        val statuses = mutableListOf<TapStatus>()
        val video = FakeVideoRecorder()
        var calls = 0
        var now = 0L
        val player = SpeechPlayer(File(tmp, "clips"))
        val session = TapSession(
            scope = scope,
            video = video,
            announcements = AnnouncementPlayer(
                dir = File(tmp, "announcements"),
                tts = object : TtsClient {
                    override suspend fun synthesize(text: String) = Result.failure<ByteArray>(ApiError.Network())
                },
                player = player,
                scope = scope,
            ),
            sender = { calls++.let { outcome } },
            onStatus = statuses::add,
            newVideoFile = { File(tmp, "clip.mp4").apply { writeBytes(ByteArray(8)) } },
            permissionsGranted = permissionsGranted,
            clock = { now },
            sendBlocked = sendBlocked,
        )
        return Harness(session, statuses, video, { calls }, { value -> now = value }, { now })
    }

    private class Harness(
        val session: TapSession,
        val statuses: List<TapStatus>,
        val video: FakeVideoRecorder,
        val calls: () -> Int,
        private val setNow: (Long) -> Unit,
        private val getNow: () -> Long,
    ) {
        var now: Long
            get() = getNow()
            set(value) = setNow(value)
    }

    private class FakeVideoRecorder : TapVideoRecorder {
        var started: File? = null
        override val ready: Boolean get() = true
        override suspend fun bind(owner: LifecycleOwner?): Result<Unit> = Result.success(Unit)
        override fun start(file: File, withAudio: Boolean): Result<Unit> {
            started = file
            return Result.success(Unit)
        }
        override fun stop() = Unit
        override suspend fun stopAndAwait(file: File, timeoutMs: Long): File? = file
        override fun release() = Unit
    }
}
