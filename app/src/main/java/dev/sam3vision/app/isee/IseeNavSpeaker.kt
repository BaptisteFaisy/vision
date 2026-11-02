package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.vm.IseeVoice
import dev.sam3vision.core.speech.IseeClipsEn

/**
 * Parole navigation iSee (ordre user : ElevenLabs = LLM + noms GPS) :
 * [speakNavigation] dit le clip MP3 anglais si connu, silence sinon (zéro
 * synthèse réseau) ; [speakNetwork] synthétise les noms GPS dynamiques seuls
 * (exception ElevenLabs). Extrait de `IseeViewModel` (plafond 500 lignes).
 */
internal class IseeNavSpeaker(
    private val clipPlayer: IseeClipPlayer,
    private val voice: IseeVoice,
    private val player: SpeechPlayer,
    private val speakAnnouncement: (String) -> Unit,
    private val clearSpeaking: () -> Unit,
) {
    /** Annonce de navigation : clip MP3 anglais si connu, silence sinon. */
    suspend fun speakNavigation(text: String): Unit {
        val clipId = IseeClipsEn.idForText(text)
        if (clipId == null) {
            android.util.Log.w("VoiceMute", "nav-jet motif=sans-clip texte=[$text]")
            return
        }
        clipPlayer.speak(clipId, urgent = false)
    }

    /**
     * Clip du pack, **attendu jusqu'à sa fin** : la question du balayage vers
     * la droite s'ouvre au micro juste après, elle ne doit pas s'y enregistrer.
     * `false` = clip inconnu ou manquant (silence, l'appelant continue).
     */
    suspend fun speakClipAndWait(clipId: String): Boolean =
        clipPlayer.speakSequence(listOf(clipId), urgent = false)

    /** Voix réseau iSee : noms GPS dynamiques seuls (exception ElevenLabs). */
    suspend fun speakNetwork(text: String): Unit {
        val audio = voice.synthesize(text)
        if (audio.isEmpty()) return
        speakAnnouncement(text)
        player.playSlice(audio).onFailure { clearSpeaking() }
    }
}
