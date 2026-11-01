package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Voix de proximité SPEC v4 : parle ou se tait — **aucune file** (ni douce,
 * ni route, ni arrivée, ni rattrapage : l'écrasé n'est jamais redit, le bus
 * ([VoiceBus]) arbitre l'instant et l'antirépétition ([dev.sam3vision.core.live.AntiRepeat]) la
 * fréquence, en amont).
 *
 * [speak] dit une phrase (clip enregistré d'abord — [clips], zéro réseau —,
 * synthèse ElevenLabs sinon) ; [speakWord] dit un mot du guidage en clip
 * local (< 100 ms) ou en synthèse mise en cache. Chaque lecture coupe le
 * lecteur partagé ([SpeechPlayer] : `play` arrête la précédente — chat,
 * route, annonce), jamais le TTS système (une alerte de sécurité ne se
 * chevauche pas : [systemBusy] tait la phrase).
 *
 * La parole du user ([userSpeaking] : micro tenu, transcription en cours)
 * passe devant tout — la voix coupe ce qu'elle lisait (une fois par prise
 * de parole, fanion rendu) et se tait — sauf le niveau 0 (voiture en
 * mouvement : seul 0 coupe la parole du user). Sans clé ElevenLabs, silence
 * (repli hors-ligne supprimé : la voix réseau en panne ne parle plus au
 * système). Le stop ne se répète jamais. L'appelant sérialise les appels
 * (le porte-parole du bus tient un mutex) : dernier gagnant.
 *
 * [synthesize] injecte le moteur doux (ElevenLabs en prod, faux en test),
 * [canPlay] la porte d'annonce (le niveau 0 la contourne, sécurité).
 */
class ProximityVoice(
    private val player: SpeechPlayer,
    private val settings: () -> Settings,
    private val onSpeaking: (String) -> Unit = {},
    private val canPlay: () -> Boolean = { true },
    private val synthesize: suspend (apiKey: String, voiceId: String, text: String) -> Result<ByteArray> =
        { apiKey, voiceId, text ->
            ElevenLabsTtsClient(apiKey, voiceId, cache = ElevenLabsTtsClient.sharedPhrases).synthesize(text)
        },
    private val clips: VoicePack? = null,
    private val systemBusy: () -> Boolean = { false },
    private val userSpeaking: () -> Boolean = { false },
) {

    /** Dernière phrase parlée (`null` si aucune : le stop ne se répète jamais). */
    private var lastText: String? = null

    /** Lecture déjà coupée pour la parole du user (une fois par prise de parole). */
    private var cutForUserDone = false

    /** Nouvelle session : le stop se redira, la coupe user est ré-armée. */
    fun reset() {
        lastText = null
        cutForUserDone = false
    }

    /**
     * Vrai si le user parle à iSee (tenue du micro, transcription) : l'appelant
     * ne fait alors rien couper — seul le niveau 0 parle par-dessus.
     */
    fun userIsSpeaking(): Boolean = userSpeaking()

    /** La phrase de niveau [level] s'efface devant la parole du user (sauf niveau 0). */
    private fun yieldsToUser(level: Int): Boolean =
        level != OutdoorLevel.CAR_MOVING && userSpeaking()

    /** Coupe la lecture en cours pour la parole du user (une fois), fanion rendu. */
    private fun cutForUser() {
        if (cutForUserDone) return
        cutForUserDone = true
        player.stop()
        onSpeaking("")
    }

    /**
     * Parle [text] au niveau [level] (clip [clipId] d'abord, synthèse sinon) ;
     * `true` si parlé (ou confié au lecteur). Parole du user (sauf niveau 0),
     * porte [canPlay] fermée (sauf niveau 0), TTS système occupé, synthèse en
     * échec : `false`, phrase jetée (jamais redite, jamais mise en file).
     */
    suspend fun speak(text: String, clipId: String? = null, level: Int = OutdoorLevel.FALLBACK): Boolean {
        if (text.isBlank()) return false
        coroutineContext.ensureActive()
        if (yieldsToUser(level)) {
            cutForUser()
            return false
        }
        val privileged = level == OutdoorLevel.CAR_MOVING
        if (!privileged && !canPlay()) return false
        if (systemBusy()) return false
        val current = settings()
        val id = clipId ?: recordedClipId(text)
        val file = if (clipId != null) {
            clips?.clipFor(clipId, current.speechLanguage, current.ttsVoiceId)?.takeIf { it.isFile }
        } else {
            recordedFileFor(text, current, clips)
        }
        if (file != null) {
            markSpoken(text)
            player.playFile(file).onFailure { onSpeaking("") }
            return true
        }
        if (!current.ttsConfigured) return false
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, text).getOrElse { return false }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || (!privileged && !canPlay()) || systemBusy()) return false
        id?.let { clips?.put(it, current.speechLanguage, current.ttsVoiceId, bytes) }
        markSpoken(text)
        player.play(bytes).onFailure { onSpeaking("") }
        return true
    }

    /**
     * Mot du guidage au niveau [level] : clip local quand le pack l'a
     * (< 100 ms, zéro réseau), sinon synthèse mise en cache ; `true` si
     * parlé. Mêmes gardes que [speak] (user, porte, système), même jet
     * sans file ; le stop ne se répète jamais.
     */
    suspend fun speakWord(
        clipId: String,
        text: String,
        language: SpeechLanguage,
        level: Int = OutdoorLevel.HUMAN,
    ): Boolean {
        if (text.isBlank()) return false
        if (text == lastText && GuidanceWord.isStopPhrase(text)) return false
        coroutineContext.ensureActive()
        if (yieldsToUser(level)) {
            cutForUser()
            return false
        }
        val privileged = level == OutdoorLevel.CAR_MOVING
        if (!privileged && !canPlay()) return false
        if (systemBusy()) return false
        val current = settings()
        val file = clips?.clipFor(clipId, language, current.ttsVoiceId)?.takeIf { it.isFile }
        if (file != null) {
            markSpoken(text)
            player.playFile(file).onFailure { onSpeaking("") }
            return true
        }
        if (!current.ttsConfigured) return false
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, text).getOrElse { return false }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || (!privileged && !canPlay()) || systemBusy()) return false
        clips?.put(clipId, language, current.ttsVoiceId, bytes)
        markSpoken(text)
        player.play(bytes).onFailure { onSpeaking("") }
        return true
    }

    /** Marquée parlée : sous-titre posé, coupe user ré-armée. */
    private fun markSpoken(text: String) {
        lastText = text
        cutForUserDone = false
        onSpeaking(text)
    }
}
