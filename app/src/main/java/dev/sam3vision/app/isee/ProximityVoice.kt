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
 * Voix de proximité de la fenêtre caméra : directions dites en continu
 * ([speakWord], sauf le stop unique), positions dites une fois par scène
 * ([onPhrase], « Personne à 1 mètre. »).
 *
 * Cinq garde-fous : rien n'est dit sans clé ElevenLabs, rien par-dessus la
 * réponse du chat (une seule source sonore à la fois — c'est l'appelant qui
 * dit si le lecteur est libre), rien par-dessus la voix route (sauf urgence :
 * l'obstacle attend la fin du virage), et une synthèse annulée (phrase plus
 * fraîche arrivée entre-temps, `collectLatest` côté sonde) n'est jamais
 * jouée. Le stop seul ne se répète jamais ; les directions douces reparlent
 * à chaque émission (ordre user du 31/10).
 *
 * [urgent] = stop (mot du guidage, alerte mouvement en Traversée ou modèle
 * d'urgence externe) : coupe la lecture en cours et parle quand même,
 * y compris pendant une dictée — la
 * sécurité passe avant le sous-titre (miroir de `TraverseVoice`, qui
 * préempte la verte par la rouge). Les mots doux et les « Attention … »
 * attendent le silence. [synthesize] injecte le moteur (ElevenLabs en prod,
 * faux en test — miroir `IseePlayback`). [clips] est le pack voix (mots du
 * guidage) : clip local quand il existe (< 100 ms, zéro réseau), synthèse
 * + mise en cache sinon (`null` = synthèse seule, comme avant).
 * [routeSpeaking] dit si la voix route parle : l'obstacle non urgent se tait tant
 * qu'elle parle, le danger urgent coupe toujours. [flushPending] redit la phrase
 * douce gardée ([onPhrase] avec file) dès le silence, dans [PENDING_TTL_MS].
 */
class ProximityVoice(
    private val player: SpeechPlayer,
    private val settings: () -> Settings,
    private val onSpeaking: (String) -> Unit = {},
    private val canPlay: () -> Boolean = { true },
    private val synthesize: suspend (apiKey: String, voiceId: String, text: String) -> Result<ByteArray> =
        { apiKey, voiceId, text -> ElevenLabsTtsClient(apiKey, voiceId).synthesize(text) },
    private val clips: VoicePack? = null,
    private val routeSpeaking: () -> Boolean = { false },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private var lastPhrase: String? = null

    /** Phrase douce gardée ([onPhrase] avec file), `null` si aucune. */
    private var pending: Pending? = null

    /** Consigne route gardée ([onRoutePhrase] douce), `null` si aucune. */
    private var pendingRoute: Pending? = null

    /** Nouvelle session : la prochaine phrase sera dite même si elle ressemble à la précédente. */
    fun reset() {
        lastPhrase = null
        pending = null
        pendingRoute = null
    }

    /**
     * Nouvelle phrase de SAM. [playerIdle] vrai = aucune bulle du chat en cours de lecture ;
     * sinon on ne dit rien — mais la phrase avalée par un garde transitoire n'est
     * pas mémorisée : réémise, elle se dira une fois la voie libre (seul ce qui a
     * vraiment parlé est dédupliqué). [urgent] coupe la lecture en cours et parle
     * quand même, dictée ou pas. Tant que la voix route parle ([routeSpeaking]),
     * l'obstacle non urgent se tait aussi, puis se dit à la prochaine émission.
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
        if (phrase == lastPhrase) return
        val current = settings()
        if (!current.ttsConfigured) return
        if (!urgent && (!playerIdle || routeSpeaking() || busy())) {
            if (queue) pending = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        speak(phrase, current, urgent)
    }

    /**
     * Consigne de guidage GPS (manœuvres, même voix Laure que les obstacles) :
     * l'urgence coupe, la douce attend le silence en file dédiée (une seule,
     * la plus fraîche — une consigne périme vite) et se redit par
     * [flushPending] avant toute douce d'obstacle (danger > route > doux).
     * La route ne s'attend pas elle-même ([routeSpeaking] ignoré : en mode
     * Laure la voix route part d'ici, pas du haut-parleur paresseux).
     */
    suspend fun onRoutePhrase(
        phrase: String,
        playerIdle: Boolean,
        urgent: Boolean = false,
    ) {
        if (phrase == lastPhrase) return
        val current = settings()
        if (!current.ttsConfigured) return
        if (!urgent && !playerIdle) {
            pendingRoute = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        speak(phrase, current, urgent)
    }

    /**
     * Redit les phrases gardées dès que plus rien ne parle : chat silencieux
     * ([playerIdle]), route tue et [busy] faux (P1/P2 en cours, vu par
     * l'appelant) — la consigne route d'abord, puis la douce d'obstacle (une
     * seule par appel, jamais de rafale). Périmée ([PENDING_TTL_MS]) ou déjà
     * dite : jetée. À appeler à chaque frame par le guetteur en file.
     */
    suspend fun flushPending(playerIdle: Boolean, busy: () -> Boolean = { false }) {
        if (!playerIdle || routeSpeaking() || busy()) return
        val current = settings()
        if (!current.ttsConfigured) return
        if (flushOne(pendingRoute, current) { pendingRoute = null }) return
        flushOne(pending, current) { pending = null }
    }

    /** Redit [due] si elle est encore fraîche et jamais dite ; `true` si parlée. */
    private suspend fun flushOne(due: Pending?, current: Settings, clear: () -> Unit): Boolean {
        if (due == null) return false
        if (due.phrase == lastPhrase || nowMs() >= due.expiresAtMs) {
            clear()
            return false
        }
        clear()
        speak(due.phrase, current, urgent = false)
        return true
    }

    /** Synthèse + lecture d'une phrase admise (mémorisée : dite une fois). */
    private suspend fun speak(phrase: String, current: Settings, urgent: Boolean) {
        if (urgent) player.stop()
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, phrase)
            .getOrElse { return }
        // L'urgence (stop) parle toujours : la sécurité passe avant la
        // priorité du user et avant la porte « GPS désactivé ». (Le rebord
        // imminent parle par la voix de sécurité, hors d'ici.)
        coroutineContext.ensureActive()
        if (bytes.isNotEmpty() && (urgent || canPlay())) {
            // Mémorisée au moment de parler seulement : un avalement transitoire
            // (chat, route, porte, synthèse) ne tait pas les réémissions.
            lastPhrase = phrase
            onSpeaking(phrase)
            player.play(bytes).onFailure { onSpeaking("") }
        }
    }

    /**
     * Mot du guidage (`GuidanceWord`) : clip local quand le pack l'a (< 100
     * ms, zéro réseau), sinon synthèse réseau mise en cache pour la suite.
     * Directions douces redites en continu (ordre user du 31/10) : le même
     * mot reparle à chaque émission, sauf le stop (unique, jamais répété).
     * Mêmes garde-fous que [onPhrase] (clé exigée, urgent coupe) : seul ce
     * qui a vraiment parlé est mémorisé — un mot avalé par un garde
     * transitoire ne tait pas ses réémissions. [queue] : le mot doux tué
     * part en file et se redit par [flushPending] (en synthèse, pas en
     * clip : parlé lentement vaut mieux que perdu).
     */
    suspend fun speakWord(
        clipId: String,
        text: String,
        language: SpeechLanguage,
        playerIdle: Boolean,
        urgent: Boolean = true,
        queue: Boolean = false,
    ) {
        if (text == lastPhrase && GuidanceWord.isStopPhrase(text)) return
        val current = settings()
        if (!current.ttsConfigured) return
        if (!urgent && !playerIdle) {
            if (queue) pending = Pending(text, nowMs() + PENDING_TTL_MS)
            return
        }
        if (urgent) player.stop()
        val file = clips?.clipFor(clipId, language, current.ttsVoiceId)?.takeIf { it.isFile }
        if (file != null) {
            coroutineContext.ensureActive()
            lastPhrase = text
            onSpeaking(text)
            player.playFile(file).onFailure { onSpeaking("") }
            return
        }
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, text)
            .getOrElse { return }
        coroutineContext.ensureActive()
        if (bytes.isNotEmpty() && (urgent || canPlay())) {
            clips?.put(clipId, language, current.ttsVoiceId, bytes)
            lastPhrase = text
            onSpeaking(text)
            player.play(bytes).onFailure { onSpeaking("") }
        }
    }

    /**
     * Stop d'urgence injecté par le modèle d'urgence externe (ordre user du
     * 29/10, contrat `docs/URGENCE-EXTERNE.md`) : « Stop. » dit aussitôt, en
     * clip local (< 100 ms) quand le pack l'a, coupe tout (chat compris).
     * Dédupliqué comme le reste : un « Stop. » déjà dit ne se répète pas
     * (le stop interne du guidage est supprimé depuis l'ordre user du
     * 31/10 : jamais de stop pour un obstacle).
     */
    suspend fun reportEmergency(language: SpeechLanguage, playerIdle: Boolean) {
        val stop = GuidanceWord.Stop
        speakWord(stop.clipId, stop.text(language), language, playerIdle, urgent = true)
    }

    /** Phrase douce en attente de silence : [phrase] à redire avant [expiresAtMs]. */
    private data class Pending(val phrase: String, val expiresAtMs: Long)

    companion object {
        /** Rattrapage borné : au-delà, la scène a changé, la phrase est périmée. */
        const val PENDING_TTL_MS = 3_000L
    }
}
