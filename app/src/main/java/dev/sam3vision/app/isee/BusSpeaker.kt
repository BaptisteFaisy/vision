package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.VOICE_SLICE_MS
import dev.sam3vision.core.crossing.CrossingLevels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * [SpeechPlayer] vu comme [ClipSink] : lecture simple (fin au rappel),
 * tranche 700 ms ([playSlice], verdicts exceptés — dits entiers) ou tranche
 * en file (fin rendue, sans rappel — sans libération du bus entre tranches).
 */
internal class SpeechPlayerClipSink(private val player: SpeechPlayer) : ClipSink {
    override suspend fun playFile(file: File): Result<Unit> = player.playFile(file)
    override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> = player.playSliceFile(file, sliceMs)
    override suspend fun playQueued(bytes: ByteArray) = player.playQueued(bytes)
    override fun stop() = player.stop()
}

/**
 * Porte-parole du [VoiceBus] (SPEC v4) : séquence de clips anglais si [clips]
 * est non vide, clip anglais seul sinon (id fourni, ou retrouvé depuis le
 * texte — [spokenClipId]), sinon voix de proximité ([ProximityVoice] : les
 * niveaux GPS synthétisent, les autres se taisent sans clip).
 *
 * Coupe d'abord le lecteur partagé (préemption physique, chat compris — la
 * tranche coupée attend sa reprise), puis parle sous mutex : une séquence en
 * cours ne bloque jamais une parole plus prioritaire (la coupe la libère,
 * pas d'interblocage). Génération ([generation]) : seule la parole la plus
 * récente rend le repos — une séquence coupée ne libère pas le niveau de sa
 * remplaçante. Les lectures simples libèrent par le rappel du lecteur
 * ([VoiceBus.onSpeechIdle]) ; les séquences (sans rappel) et les jets
 * libèrent ici.
 */
internal class BusSpeaker(
    private val scope: CoroutineScope,
    private val player: SpeechPlayer,
    private val clips: IseeClipPlayer,
    private val proximity: ProximityVoice,
) : VoiceBusSpeaker {

    /** Parole en cours sous mutex : une seule lecture du bus à la fois. */
    private val speech = Mutex()

    /** Compteur des paroles : seule la plus récente rend le repos. */
    private var generation = 0L

    override fun idle(): Boolean = player.position() == null

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        scope.launch {
            // Hors mutex : la coupe libère la séquence qui le tient.
            player.stop()
            speech.withLock {
                val current = ++generation
                if (clips.isNotEmpty()) {
                    this@BusSpeaker.clips.speakSequence(clips, urgent = true)
                    if (current == generation) VoiceBus.onSpeechIdle()
                    return@withLock
                }
                val id = clipId ?: spokenClipId(text)
                val slice = if (level == CrossingLevels.VERDICT) null else VOICE_SLICE_MS
                if (id != null && this@BusSpeaker.clips.speak(id, urgent = true, sliceMs = slice)) return@withLock
                if (!proximity.speak(text, clipId = null, level = level) && current == generation) {
                    VoiceBus.onSpeechIdle()
                }
            }
        }
    }

    /** Coupe la lecture du bus (parole du user) : directe, jamais bloquante. */
    override fun cut() {
        player.stop()
    }
}
