package dev.sam3vision.app.tap

import android.content.Context
import dev.sam3vision.app.MicBus
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.TtsClient
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate d'envoi et raisons de « Failed » de la session tap. Zéro caméra, zéro réseau :
 * le contexte est une instance vide jamais touchée (permissions et blocage injectés),
 * le TTS est un faux (aucun clip, mot tu) et l'expéditeur compte ses appels.
 */
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
    }

    @Test
    fun `permissions refusees echouent explicitement`() = runTest {
        val harness = harness(scope = this, sendBlocked = { null }, permissionsGranted = { false })

        harness.session.onTap()
        advanceUntilIdle()

        assertEquals(
            TapStatus.Failed("permission caméra ou micro refusée"),
            harness.statuses.single(),
        )
        assertEquals(0, harness.calls())
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
        sendBlocked: () -> String?,
        permissionsGranted: () -> Boolean = { true },
    ): Harness {
        val statuses = mutableListOf<TapStatus>()
        var senderCalls = 0
        val player = SpeechPlayer(File(tmp, "clips"))
        val session = TapSession(
            context = blankContext(),
            scope = scope,
            video = TapVideoSession(blankContext()),
            announcements = AnnouncementPlayer(
                dir = File(tmp, "announcements"),
                tts = object : TtsClient {
                    override suspend fun synthesize(text: String) = Result.failure<ByteArray>(ApiError.Network())
                },
                player = player,
                scope = scope,
            ),
            sender = { senderCalls++.let { Result.success("résumé") } },
            onStatus = statuses::add,
            clock = { 0L },
            sendBlocked = sendBlocked,
            permissionsGranted = permissionsGranted,
        )
        return Harness(session, statuses) { senderCalls }
    }

    private class Harness(
        val session: TapSession,
        val statuses: List<TapStatus>,
        val calls: () -> Int,
    )

    /**
     * Instance de [Context] sans constructeur : `Context` est abstrait, `MockContext`
     * n'est pas au classpath des tests unitaires, et aucun framework de mocks n'est
     * en dépendances. L'instance n'est jamais touchée (jamais de méthode appelée),
     * elle ne sert qu'à construire la session.
     */
    private fun blankContext(): Context {
        // Réflexion pure : `sun.misc.Unsafe` n'est pas au classpath de compilation
        // des tests unitaires, mais présent sur la JVM qui les exécute.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(field.get(null), Context::class.java) as Context
    }
}
