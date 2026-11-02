package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Vigile du bus vocal ([BusSpeaker]) : une parole coincée (synthèse ou
 * lecteur figés, observé sur appareil : paroles acceptées sans issue, niveau
 * actif épinglé, plus ni voix ni texte) est jetée à son budget et rend le
 * bus — la parole suivante parle. Temps virtuel (`runTest`), faux puits.
 *
 * Sans coupe intempestive (même fichier : c'est le [BusSpeaker] qu'on
 * teste) : re-post identique + lecteur occupé = ni coupe ni rejou (le son
 * continue, le bus reste tenu) ; remplaçante injouable + lecteur occupé =
 * jet sans coupe ; au repos tout se joue comme avant ; contenu nouveau =
 * coupe + jeu (préemption préservée). Le lecteur JVM ne joue jamais
 * (`MediaPlayer` indisponible) : l'occupation passe par la sonde `isBusy`,
 * les coupes par les événements `stop` du vrai lecteur.
 */
class BusSpeakerWatchdogTest {

    /** Puits qui fige la lecture (`hang = true`) ou la réussit aussitôt. */
    private class FakeSink(var hang: Boolean = true) : ClipSink {
        val slices: MutableList<String> = mutableListOf()
        val plain: MutableList<String> = mutableListOf()
        var queued = 0
        /** JNI figé : ignore l'annulation (mutex tenu passé le budget) jusqu'à [release]. */
        var stuck: Boolean = false
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        override suspend fun playFile(file: File): Result<Unit> {
            plain += file.name
            return Result.success(Unit)
        }
        override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> {
            slices += file.name
            if (stuck) withContext(NonCancellable) { release.await() }
            else if (hang) CompletableDeferred<Result<Unit>>().await()
            return Result.success(Unit)
        }
        override suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> {
            queued += 1
            return Result.success(PlaybackEnd.Done)
        }
        override fun stop() = Unit
    }

    private class Rig(
        val sink: FakeSink = FakeSink(),
        userHoldsMic: Boolean = false,
        extraClips: List<String> = emptyList(),
    ) {
        val dir: File = Files.createTempDirectory("vigile").toFile()
        val playerEvents: MutableList<String> = mutableListOf()
        val player = SpeechPlayer(dir, onEvent = playerEvents::add)
        val voiceId = "rachel-test"
        val pack = VoicePack(
            filesDir = dir,
            packVoiceId = { voiceId },
            bundledClip = { null },
        )
        val spoken: MutableList<String> = mutableListOf()
        val clips = IseeClipPlayer(pack, { voiceId }, sink, spoken::add)
        val proximity = ProximityVoice(player, { Settings.Default }, userSpeaking = { userHoldsMic })

        init {
            pack.put("guide_left", SpeechLanguage.En, voiceId, "audio".toByteArray())
            extraClips.forEach { pack.put(it, SpeechLanguage.En, voiceId, "audio".toByteArray()) }
        }
    }

    private var savedSpeaker: VoiceBusSpeaker? = null
    private var savedIdle: (() -> Unit)? = null

    @Before
    fun sauverBus() {
        savedSpeaker = VoiceBus.speaker
        savedIdle = VoiceBus.onIdle
        VoiceBus.reset()
    }

    @After
    fun rendreBus() {
        VoiceBus.speaker = savedSpeaker
        VoiceBus.onIdle = savedIdle
        VoiceBus.onSpeechIdle()
        VoiceBus.reset()
    }

    @Test
    fun `parole coincee jetee au budget, bus rendu, suivante parle`() = runTest {
        val rig = Rig()
        val released = mutableListOf<Boolean>()
        val oldSpeaker = VoiceBus.speaker
        val oldIdle = VoiceBus.onIdle
        try {
            VoiceBus.reset()
            // backgroundScope : les superviseurs (25 s virtuelles) meurent en fin de test.
            val bus = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, sayTimeoutMs = 1_000L)
            VoiceBus.speaker = bus
            VoiceBus.onIdle = { released += true }

            // Parole 1 : le puits fige la lecture, la parole tient le mutex.
            VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
            runCurrent()
            assertEquals(1, rig.sink.slices.size)

            // Parole 2 (même niveau, acceptée) : s'empile derrière la coincée.
            VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
            runCurrent()

            // Le budget passe : les deux paroles périmées sont jetées, le bus rendu.
            advanceTimeBy(1_500L)
            runCurrent()
            assertTrue(released.isNotEmpty())
            assertEquals(1, rig.sink.slices.size)

            // Parole 3 (puits réparé) : parle aussitôt — le bus n'est pas coincé.
            rig.sink.hang = false
            VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
            runCurrent()
            assertEquals(2, rig.sink.slices.size)
        } finally {
            VoiceBus.speaker = oldSpeaker
            VoiceBus.onIdle = oldIdle
            VoiceBus.onSpeechIdle()
            VoiceBus.reset()
        }
    }

    @Test
    fun `mutex coince — timeout puis suivante parle, orpheline abandonnee`() = runTest {
        val rig = Rig()
        rig.sink.hang = false
        rig.sink.stuck = true
        val oldSpeaker = VoiceBus.speaker
        val oldIdle = VoiceBus.onIdle
        try {
            VoiceBus.reset()
            VoiceBus.speaker = BusSpeaker(
                backgroundScope, rig.player, rig.clips, rig.proximity,
                sayTimeoutMs = 3_000L, nowMs = { testScheduler.currentTime },
            )
            try {
                // Parole 1 : se fige dans le lecteur (annulation ignorée, mutex tenu).
                VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
                runCurrent()
                assertEquals(1, rig.sink.slices.size)
                // Budget dépassé : la coincée ne rend rien (ni bus, ni mutex).
                advanceTimeBy(3_500L)
                runCurrent()
                assertEquals(1, rig.sink.slices.size)
                // Suivante (re-post identique, lecteur libre) : abandonne et PARLE.
                VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
                runCurrent()
                assertEquals(2, rig.sink.slices.size)
            } finally {
                rig.sink.release.complete(Unit)
                runCurrent()
            }
        } finally {
            VoiceBus.speaker = oldSpeaker
            VoiceBus.onIdle = oldIdle
            VoiceBus.onSpeechIdle()
            VoiceBus.reset()
        }
    }

    @Test
    fun `chemin coince — bus rendu, repare, suivante parle sans marteler`() = runTest {
        val rig = Rig()
        rig.sink.hang = false
        rig.sink.stuck = true
        val released = mutableListOf<Boolean>()
        val oldSpeaker = VoiceBus.speaker
        val oldIdle = VoiceBus.onIdle
        try {
            VoiceBus.reset()
            VoiceBus.speaker = BusSpeaker(
                backgroundScope, rig.player, rig.clips, rig.proximity,
                sayTimeoutMs = 3_000L, nowMs = { testScheduler.currentTime },
            )
            VoiceBus.onIdle = { released += true }
            try {
                VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
                runCurrent()
                advanceTimeBy(3_500L)
                runCurrent()
                VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
                runCurrent()
                assertEquals(2, rig.sink.slices.size)
                advanceTimeBy(RELEASE_TIMEOUT_MS) // calme : superviseur rend le bus (25 s)
                runCurrent()
                assertTrue(released.isNotEmpty())
                rig.sink.stuck = false // lecteur réparé : la suivante parle
                advanceTimeBy(3_500L)
                runCurrent()
                VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
                runCurrent()
                assertEquals(3, rig.sink.slices.size)
                assertEquals(listOf("stop", "stop"), rig.playerEvents) // coincé : coupes tues
                VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
                runCurrent()
                assertEquals(4, rig.sink.slices.size)
                assertEquals(listOf("stop", "stop", "stop"), rig.playerEvents)
            } finally {
                rig.sink.release.complete(Unit)
                runCurrent()
            }
        } finally {
            VoiceBus.speaker = oldSpeaker
            VoiceBus.onIdle = oldIdle
            VoiceBus.onSpeechIdle()
            VoiceBus.reset()
        }
    }

    @Test
    fun `repost identique occupe — ni coupe ni rejou, bus tenu puis rendu par la vigile`() = runTest {
        val rig = Rig()
        rig.sink.hang = false
        VoiceBus.speaker = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, isBusy = { true })

        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
        runCurrent()
        assertEquals(1, rig.sink.slices.size)
        assertEquals(listOf("stop"), rig.playerEvents)

        // Re-post accepté (niveau égal) : rien n'est coupé ni rejoué.
        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
        runCurrent()
        assertEquals(1, rig.sink.slices.size)
        assertEquals(listOf("stop"), rig.playerEvents)
        // Rien ne s'est terminé : le bus reste tenu (pas de onSpeechIdle).
        assertFalse(VoiceBus.post(OutdoorLevel.FALLBACK, "repli"))

        // La vigile suit le jeton le plus récent : pas de fuite de hold.
        advanceTimeBy(RELEASE_TIMEOUT_MS + 1_000L)
        runCurrent()
        assertTrue(VoiceBus.post(OutdoorLevel.FALLBACK, "repli"))
    }

    @Test
    fun `verdict repete apres done — reparle (repos, pas de doublon)`() = runTest {
        val rig = Rig(extraClips = listOf("verdict_cross"))
        rig.sink.hang = false
        VoiceBus.speaker = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, isBusy = { false })

        repeat(2) {
            VoiceBus.post(CrossingLevels.VERDICT, "Cross.", "verdict_cross")
            runCurrent()
            VoiceBus.onSpeechIdle()
        }

        assertEquals(2, rig.sink.plain.size)
        assertEquals(listOf("stop", "stop"), rig.playerEvents)
    }

    @Test
    fun `contenu nouveau occupe — coupe et joue (preemption preservee)`() = runTest {
        val rig = Rig()
        rig.sink.hang = false
        VoiceBus.speaker = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, isBusy = { true })

        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
        runCurrent()
        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", clipId = null, clips = listOf("guide_left"))
        runCurrent()

        assertEquals(1, rig.sink.slices.size)
        assertEquals(1, rig.sink.queued)
        assertEquals(listOf("stop", "stop"), rig.playerEvents)
    }

    @Test
    fun `sequence inconnue occupee — jet sans coupe, bus tenu`() = runTest {
        val rig = Rig()
        rig.sink.hang = false
        VoiceBus.speaker = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, isBusy = { true })

        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
        runCurrent()
        assertEquals(1, rig.sink.slices.size)

        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left", clips = listOf("clip-inexistant-xyz"))
        runCurrent()

        assertEquals(listOf("stop"), rig.playerEvents)
        assertEquals(0, rig.sink.queued)
        assertEquals(1, rig.sink.slices.size)
        assertFalse(VoiceBus.post(OutdoorLevel.FALLBACK, "repli"))
    }

    @Test
    fun `clip inconnu sans repli occupe — jet sans coupe, bus tenu`() = runTest {
        val rig = Rig()
        rig.sink.hang = false
        VoiceBus.speaker = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, isBusy = { true })

        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
        runCurrent()
        assertEquals(1, rig.sink.slices.size)

        VoiceBus.post(OutdoorLevel.HUMAN, "Phrase sans clip ni catalogue.", "clip-inexistant-xyz")
        runCurrent()

        assertEquals(listOf("stop"), rig.playerEvents)
        assertEquals(1, rig.sink.slices.size)
        assertFalse(VoiceBus.post(OutdoorLevel.FALLBACK, "repli"))
    }

    @Test
    fun `parole user — remplaçante sans clip tuee sans coupe`() = runTest {
        val rig = Rig(userHoldsMic = true)
        rig.sink.hang = false
        VoiceBus.speaker = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, isBusy = { true })

        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
        runCurrent()
        assertEquals(1, rig.sink.slices.size)

        // Test unitaire de la porte : en prod le bus rejetterait avant (micro tenu).
        VoiceBus.post(OutdoorLevel.HUMAN, "Phrase sans clip ni catalogue.", "clip-inexistant-xyz")
        runCurrent()

        assertEquals(listOf("stop"), rig.playerEvents)
        assertEquals(1, rig.sink.slices.size)
    }

    @Test
    fun `injouable au repos — tentative puis bus rendu comme avant`() = runTest {
        val rig = Rig()
        rig.sink.hang = false
        VoiceBus.speaker = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, isBusy = { false })

        VoiceBus.post(OutdoorLevel.HUMAN, "Phrase sans clip ni catalogue.", "clip-inexistant-xyz")
        runCurrent()

        // Au repos, rien à protéger : la parole tente (stop sans effet) et le
        // jet rend le bus par le chemin historique (pas par la vigile : aucun
        // temps virtuel n'a passé).
        assertEquals(listOf("stop"), rig.playerEvents)
        assertTrue(VoiceBus.post(OutdoorLevel.FALLBACK, "repli"))
    }

    @Test
    fun `niveau GPS sans clip occupe — tentative tout de meme (synthese possible)`() = runTest {
        val rig = Rig()
        rig.sink.hang = false
        VoiceBus.speaker = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, isBusy = { true })

        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Tournez à droite.", "clip-inexistant-xyz")
        runCurrent()

        // La synthèse reste possible : le doute joue (ici tts-non-configure,
        // donc jet après tentative — sans réseau en test).
        assertEquals(listOf("stop"), rig.playerEvents)
        assertTrue(VoiceBus.post(OutdoorLevel.FALLBACK, "repli"))
    }
}
