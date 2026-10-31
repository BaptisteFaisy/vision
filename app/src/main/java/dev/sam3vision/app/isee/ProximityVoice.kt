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
 * Voix de proximité de la fenêtre caméra : directions douces dites en continu
 * ([speakWord]), positions et obstacles doux redits après [REPEAT_TTL_MS]
 * tant qu'ils durent ([onPhrase] : un danger présent ne se tait pas, le
 * retour d'une scène parle) — en voix Laure (ElevenLabs).
 *
 * L'urgence ([urgent] : stop, danger qui s'approche, sortie de trace) ne
 * parle jamais Laure : elle part par [safety] (voix de sécurité, système
 * grave, hors-ligne — ordre user : une urgence ne sonne jamais comme une
 * phrase normale), coupe tout (chat compris, dictée ou pas) et reste orale
 * seule (aucun sous-titre, aucun fanion parlant — le TTS système ne rend
 * pas de fin). Les clips stop (`guide_stop`, `tl_stop`) sont l'héritage
 * d'avant : l'urgent ne les joue plus.
 *
 * Garde-fous du doux : rien sans clé ElevenLabs, rien par-dessus la réponse
 * du chat (une seule source sonore à la fois — c'est l'appelant qui dit si
 * le lecteur est libre), rien par-dessus la voix route (le doux attend la
 * fin du virage), et une synthèse annulée (phrase plus fraîche arrivée
 * entre-temps, `collectLatest` côté sonde) n'est jamais jouée. Le stop seul
 * ne se répète jamais ; les directions douces reparlent à chaque émission
 * (ordre user du 31/10).
 *
 * [synthesize] injecte le moteur doux (ElevenLabs en prod, faux en test —
 * miroir `IseePlayback`). [clips] est le pack voix des mots doux : clip
 * local quand il existe (< 100 ms, zéro réseau), synthèse + mise en cache
 * sinon (`null` = synthèse seule, comme avant). [routeSpeaking] dit si la
 * voix route parle : le doux se tait tant qu'elle parle. [flushPending]
 * redit la phrase douce gardée ([onPhrase] avec file) dès le silence, dans
 * [PENDING_TTL_MS]. L'arrivée sur le passage ([onArrivalPhrase]) passe en
 * dernier, derrière la route et les doux — la plus basse priorité de
 * toutes les annonces.
 */
class ProximityVoice(
    private val player: SpeechPlayer,
    private val settings: () -> Settings,
    private val safety: SafetyVoice,
    private val onSpeaking: (String) -> Unit = {},
    private val canPlay: () -> Boolean = { true },
    private val synthesize: suspend (apiKey: String, voiceId: String, text: String) -> Result<ByteArray> =
        { apiKey, voiceId, text -> ElevenLabsTtsClient(apiKey, voiceId).synthesize(text) },
    private val clips: VoicePack? = null,
    private val routeSpeaking: () -> Boolean = { false },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private var lastPhrase: String? = null

    /** Instant de la dernière phrase dite ([nowMs]) : la déduplication est bornée ([REPEAT_TTL_MS]). */
    private var lastPhraseAtMs: Long = 0L

    /** Phrase douce gardée ([onPhrase] avec file), `null` si aucune. */
    private var pending: Pending? = null

    /** Consigne route gardée ([onRoutePhrase] douce), `null` si aucune. */
    private var pendingRoute: Pending? = null

    /** Arrivée sur le passage gardée ([onArrivalPhrase]), `null` si aucune. */
    private var pendingArrival: Pending? = null

    /** Nouvelle session : la prochaine phrase sera dite même si elle ressemble à la précédente. */
    fun reset() {
        lastPhrase = null
        lastPhraseAtMs = 0L
        pending = null
        pendingRoute = null
        pendingArrival = null
    }

    /**
     * Nouvelle phrase de SAM. [playerIdle] vrai = aucune bulle du chat en cours de lecture ;
     * sinon le doux ne dit rien — mais la phrase avalée par un garde transitoire n'est
     * pas mémorisée : réémise, elle se dira une fois la voie libre (seul ce qui a
     * vraiment parlé est dédupliqué, pendant [REPEAT_TTL_MS]). [urgent] part par
     * la voix de sécurité ([speakUrgent], grave, hors-ligne, coupe tout),
     * dictée ou pas. Tant que la voix route parle ([routeSpeaking]),
     * le doux se tait aussi, puis se dit à la prochaine émission.
     * [queue] : la phrase douce tuée par le chat, la route ou [busy] est gardée
     * (une seule, la plus fraîche) et redite par [flushPending] dès le silence,
     * dans [PENDING_TTL_MS] — sans file, elle se redit à la prochaine émission.
     * [busy] : P1/P2 en cours, vus par l'appelant — la phrase douce part en
     * file si [queue] (jamais de chevauchement SafetyVoice).
     */
    suspend fun onPhrase(
        phrase: String,
        playerIdle: Boolean,
        urgent: Boolean = false,
        queue: Boolean = false,
        busy: () -> Boolean = { false },
    ) {
        if (saidRecently(phrase)) return
        if (urgent) {
            speakUrgent(phrase)
            return
        }
        val current = settings()
        if (!current.ttsConfigured) return
        if (!playerIdle || routeSpeaking() || busy()) {
            if (queue) pending = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        speak(phrase, current)
    }

    /**
     * Consigne de guidage GPS (manœuvres douces en voix Laure) : l'urgence
     * (sortie de trace, recalcul) part en voix de sécurité ([speakUrgent],
     * grave, coupe), la douce attend le silence en file dédiée (une seule,
     * la plus fraîche — une consigne périme vite) et se redit par
     * [flushPending] avant toute douce d'obstacle (danger > route > doux >
     * arrivée). La route ne s'attend pas elle-même ([routeSpeaking] ignoré :
     * en mode Laure la voix route part d'ici, pas du haut-parleur paresseux).
     */
    suspend fun onRoutePhrase(
        phrase: String,
        playerIdle: Boolean,
        urgent: Boolean = false,
    ) {
        if (saidRecently(phrase)) return
        if (urgent) {
            speakUrgent(phrase)
            return
        }
        val current = settings()
        if (!current.ttsConfigured) return
        if (!playerIdle) {
            pendingRoute = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        speak(phrase, current)
    }

    /**
     * Arrivée sur le passage (« Sur le passage. ») : la plus basse priorité
     * de toutes les annonces (ordre user du 31/10) — parlée au silence seul
     * (chat tu et route tue), sinon gardée (une seule, la plus fraîche) et
     * redite en dernier par [flushPending] (route, puis doux, puis arrivée)
     * ou par [flushArrival] (rattrapage Traversée, où la file obstacles est
     * coupée). Ne coupe jamais rien : aucun `urgent` ici.
     */
    suspend fun onArrivalPhrase(phrase: String, playerIdle: Boolean) {
        if (saidRecently(phrase)) return
        val current = settings()
        if (!current.ttsConfigured) return
        if (!playerIdle || routeSpeaking()) {
            pendingArrival = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        speak(phrase, current)
    }

    /**
     * Redit les phrases gardées dès que plus rien ne parle : chat silencieux
     * ([playerIdle]), route tue et [busy] faux (P1/P2 en cours, vu par
     * l'appelant) — la consigne route d'abord, puis la douce d'obstacle, puis
     * l'arrivée sur le passage (une seule par appel, jamais de rafale).
     * Périmée ([PENDING_TTL_MS]) ou déjà dite : jetée. À appeler à chaque
     * frame par le guetteur en file.
     */
    suspend fun flushPending(playerIdle: Boolean, busy: () -> Boolean = { false }) {
        if (!playerIdle || routeSpeaking() || busy()) return
        val current = settings()
        if (!current.ttsConfigured) return
        if (flushOne(pendingRoute, current) { pendingRoute = null }) return
        if (flushOne(pending, current) { pending = null }) return
        flushOne(pendingArrival, current) { pendingArrival = null }
    }

    /**
     * Redit l'arrivée gardée seule, au silence (chat tu, route tue) et sans
     * route ni doux frais devant elle : l'arrivée reste la dernière, même
     * hors [flushPending] (Traversée, où la file obstacles est coupée mais
     * pas le rattrapage latéral). Périmée ou déjà dite : jetée.
     */
    suspend fun flushArrival(playerIdle: Boolean) {
        if (!playerIdle || routeSpeaking()) return
        val current = settings()
        if (!current.ttsConfigured) return
        if (hasFresh(pendingRoute) || hasFresh(pending)) return
        flushOne(pendingArrival, current) { pendingArrival = null }
    }

    /** Vrai si [due] attend encore d'être dite (fraîche et jamais parlée). */
    private fun hasFresh(due: Pending?): Boolean =
        due != null && due.phrase != lastPhrase && nowMs() < due.expiresAtMs

    /**
     * Vrai si [phrase] vient d'être dite (moins de [REPEAT_TTL_MS]) : la
     * déduplication est bornée — un danger présent se redit, le retour
     * d'une scène parle, et rien ne bégaie. Le stop garde sa règle propre
     * (jamais répété, [speakWord]).
     */
    private fun saidRecently(phrase: String): Boolean =
        phrase == lastPhrase && nowMs() - lastPhraseAtMs < REPEAT_TTL_MS

    /** Redit [due] si elle est encore fraîche et jamais dite ; `true` si parlée. */
    private suspend fun flushOne(due: Pending?, current: Settings, clear: () -> Unit): Boolean {
        if (due == null) return false
        if (due.phrase == lastPhrase || nowMs() >= due.expiresAtMs) {
            clear()
            return false
        }
        clear()
        speak(due.phrase, current)
        return true
    }

    /**
     * Urgence admise (jamais dite récemment, jamais un stop déjà dit) : voix
     * de sécurité aussitôt (grave, hors-ligne, coupe tout — [SafetyVoice]),
     * mémorisée comme le doux (redite après [REPEAT_TTL_MS] si elle dure).
     * Orale seule : aucun sous-titre ni fanion (le TTS système ne rend pas
     * de fin, un fanion sans fin muterait la voix douce — voir `cutSpeaking`).
     */
    private fun speakUrgent(phrase: String) {
        if (phrase.isBlank()) return
        safety.speak(phrase)
        lastPhrase = phrase
        lastPhraseAtMs = nowMs()
    }

    /** Synthèse + lecture d'une phrase douce admise (mémorisée : dite une fois). */
    private suspend fun speak(phrase: String, current: Settings) {
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, phrase)
            .getOrElse { return }
        coroutineContext.ensureActive()
        if (bytes.isNotEmpty() && canPlay()) {
            // Mémorisée au moment de parler seulement : un avalement transitoire
            // (chat, route, porte, synthèse) ne tait pas les réémissions.
            lastPhrase = phrase
            lastPhraseAtMs = nowMs()
            onSpeaking(phrase)
            player.play(bytes).onFailure { onSpeaking("") }
        }
    }

    /**
     * Mot du guidage (`GuidanceWord`) : doux en clip local quand le pack l'a
     * (< 100 ms, zéro réseau), sinon synthèse réseau mise en cache pour la
     * suite. Directions douces redites en continu (ordre user du 31/10) : le
     * même mot reparle à chaque émission ; le stop part en voix de sécurité
     * ([speakUrgent], unique, jamais répété — ses clips sont l'héritage
     * d'avant, l'urgent ne les joue plus). Mêmes garde-fous que [onPhrase]
     * (clé exigée pour le doux) : seul ce qui a vraiment parlé est mémorisé
     * — un mot avalé par un garde transitoire ne tait pas ses réémissions.
     * [queue] : le mot doux tué part en file et se redit par [flushPending]
     * (en synthèse, pas en clip : parlé lentement vaut mieux que perdu).
     */
    suspend fun speakWord(
        clipId: String,
        text: String,
        language: SpeechLanguage,
        playerIdle: Boolean,
        urgent: Boolean = false,
        queue: Boolean = false,
    ) {
        if (text == lastPhrase && GuidanceWord.isStopPhrase(text)) return
        if (urgent) {
            speakUrgent(text)
            return
        }
        val current = settings()
        if (!current.ttsConfigured) return
        if (!playerIdle) {
            if (queue) pending = Pending(text, nowMs() + PENDING_TTL_MS)
            return
        }
        val file = clips?.clipFor(clipId, language, current.ttsVoiceId)?.takeIf { it.isFile }
        if (file != null) {
            coroutineContext.ensureActive()
            lastPhrase = text
            lastPhraseAtMs = nowMs()
            onSpeaking(text)
            player.playFile(file).onFailure { onSpeaking("") }
            return
        }
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, text)
            .getOrElse { return }
        coroutineContext.ensureActive()
        if (bytes.isNotEmpty() && canPlay()) {
            clips?.put(clipId, language, current.ttsVoiceId, bytes)
            lastPhrase = text
            lastPhraseAtMs = nowMs()
            onSpeaking(text)
            player.play(bytes).onFailure { onSpeaking("") }
        }
    }

    /** Phrase douce en attente de silence : [phrase] à redire avant [expiresAtMs]. */
    private data class Pending(val phrase: String, val expiresAtMs: Long)

    companion object {
        /** Rattrapage borné : au-delà, la scène a changé, la phrase est périmée. */
        const val PENDING_TTL_MS = 3_000L

        /**
         * Redite d'une phrase identique : 4 s, le temps de dire une phrase
         * courte (comme le délai amont) — les directions en clips tournent
         * à 2 s, la synthèse réseau coûte plus cher. Au-delà, un danger
         * présent se redit et le retour d'une scène parle.
         */
        const val REPEAT_TTL_MS = 4_000L
    }
}
