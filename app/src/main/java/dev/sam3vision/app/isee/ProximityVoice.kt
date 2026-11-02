package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Voix de proximité SPEC v4 : parle ou se tait — **aucune file** (ni douce,
 * ni route, ni arrivée, ni rattrapage : l'écrasé n'est jamais redit, le bus
 * ([VoiceBus]) arbitre l'instant et l'antirépétition ([dev.sam3vision.core.live.AntiRepeat]) la
 * fréquence, en amont).
 *
 * [speak] dit une phrase (clip enregistré d'abord — [clips], zéro réseau —,
 * synthèse ElevenLabs sinon pour les niveaux GPS seuls, silence sinon) ; [speakWord] dit un mot du guidage en clip
 * local (< 100 ms) ou en synthèse mise en cache. Chaque lecture coupe le
 * lecteur partagé ([SpeechPlayer], tranche 700 ms `playSlice` — chat,
 * route, annonce), jamais le TTS système (une alerte de sécurité ne se
 * chevauche pas : [systemBusy] tait la phrase).
 *
 * La parole du user ([userSpeaking] : micro tenu, transcription en cours)
 * passe devant tout — la voix coupe ce qu'elle lisait (une fois par prise
 * de parole, fanion rendu) et se tait — sauf le niveau 0 (voiture en
 * mouvement : seul 0 coupe la parole du user). Sans clé ElevenLabs, silence.
 * Voix réseau en panne ([isNetworkVoiceDown] : clé, crédits, cadence,
 * timeout, réseau) : la phrase part au TTS système ([system], en file),
 * jamais jetée quand un moteur est câblé (`null` = jet comme avant).
 * Le stop ne se répète jamais. L'appelant sérialise les appels
 * (le porte-parole du bus tient un mutex) : dernier gagnant.
 *
 * Chaque entrée de parole est tracée ([SpeechLog.expect]), chaque lecture
 * ([SpeechLog.play] + [SpeechLog.done]), chaque jet ([SpeechLog.fail] avec
 * le motif du garde — mêmes motifs que le logcat `VoiceMute`).
 *
 * [synthesize] injecte le moteur doux (ElevenLabs en prod, faux en test),
 * [canPlay] la porte d'annonce (le niveau 0 la contourne, sécurité).
 */
/** Étiquette des diagnostics du mutisme spontané (`logcat -s VoiceMute:*`). */
private const val TAG_MUTE = "VoiceMute"

/** Plafond d'une synthèse proximité : au-delà, panne réseau (repli système). */
private const val PROX_SYNTH_TIMEOUT_MS = 5_000L

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
    private val system: SystemTtsEngine? = null,
    private val onOfflineSpoken: () -> Unit = {},
) {

    /** Dernière phrase parlée (`null` si aucune : le stop ne se répète jamais). */
    private var lastText: String? = null

    /** Lecture déjà coupée pour la parole du user (une fois par prise de parole). */
    private var cutForUserDone = false

    /**
     * Jet diagnostiqué : motif au logcat en warning (visible en release —
     * les jets sont déjà espacés par l'antirépétition) et à la trace
     * ([SpeechLog.fail], [ou] = chemin appelant), `false` rendu.
     */
    private fun mute(ou: String, text: String, level: Int, reason: String): Boolean {
        android.util.Log.w(TAG_MUTE, "prox-jet level=$level motif=$reason texte=[$text]")
        SpeechLog.fail(ou, reason)
        return false
    }

    /** Voix réseau en panne durable (clé, crédits, cadence, timeout, réseau) : le repli système prend le relais. */
    private fun isNetworkVoiceDown(error: Throwable): Boolean = when (error) {
        is ApiError.InvalidKey, is ApiError.PaymentRequired, is ApiError.RateLimited,
        is ApiError.Timeout, is ApiError.Network -> true
        else -> false
    }

    /**
     * Repli TTS système (hors-ligne) : [phrase] dite sans ElevenLabs, en file
     * (`QUEUE_ADD`, jamais de chevauchement). `true` si confiée au moteur
     * (mémorisée comme une parole normale) ; `false` si aucun moteur câblé ou
     * refus (non mémorisée : le jet garde le motif `panne-reseau`).
     */
    private fun speakOffline(phrase: String, current: Settings): Boolean {
        val engine = system ?: return false
        val locale = SystemTtsEngine.localeOf(current.speechLanguage)
        if (!engine.speak(phrase, false, locale, current.ttsSpeed)) return false
        markSpoken(phrase)
        SpeechLog.play("repli-système", phrase)
        SpeechLog.done(phrase)
        runCatching { onOfflineSpoken() }
        return true
    }

    /**
     * Synthèse plafonnée : le moteur doux sous [PROX_SYNTH_TIMEOUT_MS], sinon
     * [ApiError.Timeout] (chemin panne réseau : repli système s'il est câblé).
     */
    private suspend fun synthBound(apiKey: String, voiceId: String, text: String): Result<ByteArray> =
        withTimeoutOrNull(PROX_SYNTH_TIMEOUT_MS) { synthesize(apiKey, voiceId, text) }
            ?: Result.failure(ApiError.Timeout())

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
     * Lecture tracée : [SpeechLog.play] avant de confier au lecteur, [SpeechLog.done]
     * au bout, [SpeechLog.fail] `lecture` + sous-titre rendu si la lecture échoue
     * (le rendu reste `true` : confié, comme avant — seule la trace change).
     */
    private suspend fun playTraced(ou: String, text: String, source: String, play: suspend () -> Result<Unit>) {
        SpeechLog.play(source, text)
        play().onSuccess { SpeechLog.done(text) }
            .onFailure { SpeechLog.fail(ou, "lecture"); onSpeaking("") }
    }

    /**
     * Parle [text] au niveau [level] (clip [clipId] d'abord, synthèse sinon) ;
     * `true` si parlé (ou confié au lecteur). Parole du user (sauf niveau 0),
     * porte [canPlay] fermée (sauf niveau 0), TTS système occupé, synthèse en
     * échec non réseau : `false`, phrase jetée (jamais redite, jamais mise en
     * file). Panne réseau ([isNetworkVoiceDown]) : repli système si câblé.
     */
    suspend fun speak(text: String, clipId: String? = null, level: Int = OutdoorLevel.FALLBACK): Boolean {
        val ou = "speak"
        SpeechLog.expect(ou, text)
        if (text.isBlank()) return mute(ou, text, level, "vide")
        coroutineContext.ensureActive()
        if (yieldsToUser(level)) {
            cutForUser()
            return mute(ou, text, level, "parole-user")
        }
        val privileged = level == OutdoorLevel.CAR_MOVING
        if (!privileged && !canPlay()) return mute(ou, text, level, "porte-canPlay")
        if (systemBusy()) return mute(ou, text, level, "tts-systeme-occupe")
        val current = settings()
        val id = clipId ?: recordedClipId(text)
        val file = if (clipId != null) {
            clips?.clipFor(clipId, current.speechLanguage, current.ttsVoiceId)?.takeIf { it.isFile }
        } else {
            recordedFileFor(text, current, clips)
        }
        if (file != null) {
            markSpoken(text)
            playTraced(ou, text, "clip") { player.playSliceFile(file) }
            return true
        }
        // Sans clip, seuls les niveaux GPS synthétisent (noms rues/lieux
        // dynamiques — ordre user : ElevenLabs = LLM + noms GPS, le reste en MP3).
        if (level != OutdoorLevel.GPS_REMINDER && level != OutdoorLevel.GPS_EVENT)
            return mute(ou, text, level, "sans-clip")
        if (!current.ttsConfigured) return mute(ou, text, level, "tts-non-configure")
        val bytes = synthBound(current.ttsApiKey, current.ttsVoiceId, text).getOrElse { error ->
            val down = isNetworkVoiceDown(error)
            if (down && speakOffline(text, current)) return true
            return mute(ou, text, level, if (down) "panne-reseau" else "synthese-echec")
        }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || (!privileged && !canPlay()) || systemBusy()) return mute(ou, text, level, "coupe-apres-synthese")
        id?.let { clips?.put(it, current.speechLanguage, current.ttsVoiceId, bytes) }
        markSpoken(text)
        playTraced(ou, text, "synthèse") { player.playSlice(bytes) }
        return true
    }

    /**
     * Mot du guidage au niveau [level] : clip local quand le pack l'a
     * (< 100 ms, zéro réseau), sinon synthèse mise en cache ; `true` si
     * parlé. Mêmes gardes que [speak] (user, porte, système), même jet
     * sans file, même repli panne réseau ; le stop ne se répète jamais.
     */
    suspend fun speakWord(
        clipId: String,
        text: String,
        language: SpeechLanguage,
        level: Int = OutdoorLevel.HUMAN,
    ): Boolean {
        val ou = "speakWord"
        SpeechLog.expect(ou, text)
        if (text.isBlank()) return mute(ou, text, level, "vide")
        if (text == lastText && GuidanceWord.isStopPhrase(text)) return mute(ou, text, level, "stop-deja-dit")
        coroutineContext.ensureActive()
        if (yieldsToUser(level)) {
            cutForUser()
            return mute(ou, text, level, "parole-user")
        }
        val privileged = level == OutdoorLevel.CAR_MOVING
        if (!privileged && !canPlay()) return mute(ou, text, level, "porte-canPlay")
        if (systemBusy()) return mute(ou, text, level, "tts-systeme-occupe")
        val current = settings()
        val file = clips?.clipFor(clipId, language, current.ttsVoiceId)?.takeIf { it.isFile }
        if (file != null) {
            markSpoken(text)
            playTraced(ou, text, "clip") { player.playSliceFile(file) }
            return true
        }
        // Sans clip, seuls les niveaux GPS synthétisent (noms rues/lieux
        // dynamiques — ordre user : ElevenLabs = LLM + noms GPS, le reste en MP3).
        if (level != OutdoorLevel.GPS_REMINDER && level != OutdoorLevel.GPS_EVENT)
            return mute(ou, text, level, "sans-clip")
        if (!current.ttsConfigured) return mute(ou, text, level, "tts-non-configure")
        val bytes = synthBound(current.ttsApiKey, current.ttsVoiceId, text).getOrElse { error ->
            val down = isNetworkVoiceDown(error)
            if (down && speakOffline(text, current)) return true
            return mute(ou, text, level, if (down) "panne-reseau" else "synthese-echec")
        }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || (!privileged && !canPlay()) || systemBusy()) return mute(ou, text, level, "coupe-apres-synthese")
        clips?.put(clipId, language, current.ttsVoiceId, bytes)
        markSpoken(text)
        playTraced(ou, text, "synthèse") { player.playSlice(bytes) }
        return true
    }

    /** Marquée parlée : sous-titre posé, coupe user ré-armée. */
    private fun markSpoken(text: String) {
        lastText = text
        cutForUserDone = false
        onSpeaking(text)
    }
}
