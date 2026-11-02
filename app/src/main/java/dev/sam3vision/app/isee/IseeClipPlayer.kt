package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File

/**
 * Destination d'un clip : [dev.sam3vision.app.SpeechPlayer] en production
 * (adaptateur dans `ProbeWiring.kt`), faux en test JVM — aucun Android ici.
 */
interface ClipSink {
    /** Joue un clip déjà sur disque (coupe la lecture précédente). */
    suspend fun playFile(file: File): Result<Unit>

    /** Joue un clip en tranche ([sliceMs], 700 ms voix) : vitesse adaptée, pitch conservé. */
    suspend fun playSlice(file: File, sliceMs: Long): Result<Unit>

    /** Joue une tranche et suspend jusqu'à sa fin (phrases composées). */
    suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd>

    /** Coupe la lecture en cours. */
    fun stop()
}

/**
 * Lecteur des clips anglais SPEC v4 : clip local (cache puis embarqué
 * `res/raw`) joué en < 100 ms, zéro appel réseau au moment de parler.
 *
 * Un clip manquant ou inconnu = silence (jamais de synthèse de repli : le
 * GPS et le chat, seuls à parler réseau, gardent leurs chemins existants).
 * [urgent] coupe la lecture en cours, sinon le clip attend son tour via la
 * file ([speakSequence], tranches enchaînées sans chevauchement).
 * [speakSequence] ne joue rien si un clip manque : une phrase incomplète
 * reste muette plutôt que cassée (« Looking for » sans objet ne se dit pas).
 *
 * 100 % injecté ([VoicePack], voix, [ClipSink]) : testé en JVM sans Android.
 */
class IseeClipPlayer(
    private val clips: VoicePack,
    private val voiceId: () -> String,
    private val sink: ClipSink,
    private val onSpeaking: (String) -> Unit = {},
) {

    /**
     * Joue [clipId] ([IseeClipsEn]) : vrai si le clip est parti, faux si
     * inconnu, manquant sur disque, ou refusé par le lecteur (alors silence).
     * [sliceMs] non nul : lecture en tranche (700 ms voix, verdicts exceptés).
     */
    suspend fun speak(clipId: String, urgent: Boolean, sliceMs: Long? = null): Boolean {
        val text = IseeClipsEn.all[clipId] ?: return false
        val file = clipFile(clipId) ?: return false
        if (urgent) sink.stop()
        onSpeaking(text)
        val played = if (sliceMs == null) sink.playFile(file) else sink.playSlice(file, sliceMs)
        if (played.isFailure) {
            onSpeaking("")
            return false
        }
        return true
    }

    /**
     * Joue [clipIds] dans l'ordre (« Person. » + « 3 o'clock. » + « 5
     * meters. ») : vrai si la phrase complète est partie. Faux, sans rien
     * jouer, si un clip est inconnu ou manquant ; faux aussi si une tranche
     * est coupée ou refusée (les tranches déjà jouées restent entendues).
     */
    suspend fun speakSequence(clipIds: List<String>, urgent: Boolean): Boolean {
        if (clipIds.isEmpty()) return false
        val texts = clipIds.map { IseeClipsEn.all[it] ?: return false }
        val files = clipIds.map { clipFile(it) ?: return false }
        if (urgent) sink.stop()
        onSpeaking(texts.joinToString(" "))
        for (file in files) {
            val end = sink.playQueued(file.readBytes())
                .getOrElse {
                    onSpeaking("")
                    return false
                }
            if (end != PlaybackEnd.Done) {
                onSpeaking("")
                return false
            }
        }
        return true
    }

    /** Coupe la lecture en cours (ordre plus prioritaire, écran quitté) et rend le bus. */
    fun stop() = sink.stop().also { VoiceBus.onSpeechIdle() }

    private fun clipFile(clipId: String): File? =
        clips.clipFor(clipId, SpeechLanguage.En, voiceId())?.takeIf { it.isFile }
}
