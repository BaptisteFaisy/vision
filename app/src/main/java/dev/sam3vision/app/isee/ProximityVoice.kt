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
import kotlin.coroutines.coroutineContext

/**
 * Voix de proximité de la fenêtre caméra : directions dites en continu
 * ([speakWord], sauf le stop unique), positions dites une fois par scène
 * ([onPhrase], « Personne à 1 mètre. »).
 *
 * Garde-fous : rien par-dessus la réponse du chat (une seule source sonore
 * à la fois — c'est l'appelant qui dit si le lecteur est libre), rien
 * par-dessus la voix route (sauf urgence : l'obstacle attend la fin du
 * virage), rien par-dessus le TTS système en cours ([systemBusy] : une
 * alerte de sécurité ne se chevauche pas), et une synthèse annulée (phrase
 * plus fraîche arrivée entre-temps) n'est jamais jouée. Le stop seul ne se
 * répète jamais ; les directions douces reparlent à chaque émission (ordre
 * user du 31/10).
 *
 * [urgent] = stop (mot du guidage, alerte mouvement en Traversée ou modèle
 * d'urgence externe) : coupe la lecture en cours et parle quand même,
 * y compris pendant une dictée — la sécurité passe avant le sous-titre
 * (miroir de `TraverseVoice`, qui préempte la verte par la rouge). Parlé
 * via [safety] (TTS système immédiat, coupe tout) quand elle est câblée,
 * jamais après une synthèse réseau de plusieurs secondes ; en clip local
 * (< 100 ms) quand le pack l'a. Les mots doux et les « Attention … »
 * attendent le silence. [synthesize] injecte le moteur (ElevenLabs en prod,
 * faux en test — miroir `IseePlayback`). [clips] est le pack voix (mots du
 * guidage) : clip local quand il existe (< 100 ms, zéro réseau, sans clé),
 * synthèse + mise en cache sinon (`null` = synthèse seule, comme avant).
 * [routeSpeaking] dit si la voix route parle : l'obstacle non urgent se tait tant
 * qu'elle parle, le danger urgent coupe toujours. [flushPending] redit la phrase
 * douce gardée ([onPhrase] avec file) dès le silence, dans [PENDING_TTL_MS].
 *
 * Sans clé ElevenLabs (ou voix réseau en panne : 401/402/429), pas de
 * silence : la phrase part au TTS système ([system], doux en file,
 * urgent en coupe via [safety]), précédée une fois par session du signal
 * [networkVoiceDown]. Le repli est oral seul (ni sous-titre, ni fanion —
 * miroir `SafetyVoice` : sans fin de lecture, un fanion bloquerait la voix
 * douce pour toujours), mais mémorisé (pas de répétition).
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
    private val routeSpeaking: () -> Boolean = { false },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val safety: SafetyVoice? = null,
    private val system: SystemTtsEngine? = null,
    private val systemBusy: () -> Boolean = { false },
) {

    private var lastPhrase: String? = null

    /** Phrase douce gardée ([onPhrase] avec file), `null` si aucune. */
    private var pending: Pending? = null

    /** Consigne route gardée ([onRoutePhrase] douce), `null` si aucune. */
    private var pendingRoute: Pending? = null

    /** Signal « voix réseau indisponible » déjà parlé (une fois par session). */
    private var networkAlertSpoken = false

    /** Nouvelle session : la prochaine phrase sera dite même si elle ressemble à la précédente. */
    fun reset() {
        lastPhrase = null
        pending = null
        pendingRoute = null
        networkAlertSpoken = false
    }

    /**
     * Nouvelle phrase de SAM. [playerIdle] vrai = aucune bulle du chat en cours de lecture ;
     * sinon on ne dit rien — mais la phrase avalée par un garde transitoire n'est
     * pas mémorisée : réémise, elle se dira une fois la voie libre (seul ce qui a
     * vraiment parlé est dédupliqué). [urgent] coupe la lecture en cours et parle
     * quand même, dictée ou pas (via [safety] si câblée). Tant que la voix route
     * parle ([routeSpeaking]) ou que le TTS système parle ([systemBusy]),
     * l'obstacle non urgent se tait aussi, puis se dit à la prochaine émission.
     * [queue] : la phrase douce tuée par le chat, la route, le système ou [busy]
     * est gardée (une seule, la plus fraîche) et redite par [flushPending] dès
     * le silence, dans [PENDING_TTL_MS] — sans file, elle se redit à la
     * prochaine émission. [busy] : P1/P2 en cours, vus par l'appelant — la
     * phrase douce part en file si [queue] (jamais de chevauchement SafetyVoice).
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
        if (!urgent && (!playerIdle || routeSpeaking() || busy() || systemBusy())) {
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
     * Laure la voix route part d'ici, pas du haut-parleur paresseux) ; elle
     * attend le TTS système ([systemBusy], jamais de chevauchement d'alerte).
     */
    suspend fun onRoutePhrase(
        phrase: String,
        playerIdle: Boolean,
        urgent: Boolean = false,
    ) {
        if (phrase == lastPhrase) return
        val current = settings()
        if (!urgent && (!playerIdle || systemBusy())) {
            pendingRoute = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        speak(phrase, current, urgent)
    }

    /**
     * Redit les phrases gardées dès que plus rien ne parle : chat silencieux
     * ([playerIdle]), route tue, système tu ([systemBusy]) et [busy] faux
     * (P1/P2 en cours, vu par l'appelant) — la consigne route d'abord, puis
     * la douce d'obstacle (une seule par appel, jamais de rafale). Périmée
     * ([PENDING_TTL_MS]) ou déjà dite : jetée. En échec (synthèse, porte) :
     * gardée pour le prochain silence — la file ne perd pas. À appeler à
     * chaque frame par le guetteur en file, Traversée comprise.
     */
    suspend fun flushPending(playerIdle: Boolean, busy: () -> Boolean = { false }) {
        if (!playerIdle || routeSpeaking() || busy() || systemBusy()) return
        val current = settings()
        if (flushOne(pendingRoute, current) { pendingRoute = null }) return
        flushOne(pending, current) { pending = null }
    }

    /**
     * Redit [due] si elle est encore fraîche et jamais dite ; `true` si parlée.
     * Marquée dite après parole moteur : en échec, la phrase reste en file
     * (l'appelant garde son `Pending`) au lieu d'être perdue.
     */
    private suspend fun flushOne(due: Pending?, current: Settings, clear: () -> Unit): Boolean {
        if (due == null) return false
        if (due.phrase == lastPhrase || nowMs() >= due.expiresAtMs) {
            clear()
            return false
        }
        if (!speak(due.phrase, current, urgent = false)) return false
        clear()
        return true
    }

    /**
     * Synthèse + lecture d'une phrase admise ; `true` si parlée (ou confiée
     * au TTS système). Sans clé, ou voix réseau en panne (401/402/429) :
     * repli système ([speakOffline]), jamais de silence. Urgente et [safety]
     * câblée : coupe immédiate, sans synthèse réseau.
     */
    private suspend fun speak(phrase: String, current: Settings, urgent: Boolean): Boolean {
        if (urgent) player.stop()
        coroutineContext.ensureActive()
        if (!current.ttsConfigured) return speakOffline(phrase, current, urgent)
        if (urgent && safety != null) {
            // L'urgence (stop) parle toujours : la sécurité passe avant la
            // priorité du user et avant la porte « GPS désactivé ». (Le rebord
            // imminent parle par la voix de sécurité, hors d'ici.)
            lastPhrase = phrase
            safety.speak(phrase)
            return true
        }
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, phrase)
            .getOrElse { error ->
                return if (isNetworkVoiceDown(error)) speakOffline(phrase, current, urgent) else false
            }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || (!urgent && (!canPlay() || systemBusy()))) return false
        // Mémorisée au moment de parler seulement : un avalement transitoire
        // (chat, route, système, porte, synthèse) ne tait pas les réémissions.
        lastPhrase = phrase
        onSpeaking(phrase)
        player.play(bytes).onFailure { onSpeaking("") }
        return true
    }

    /**
     * Repli TTS système (hors-ligne) : [phrase] dite sans ElevenLabs —
     * urgent en coupe ([safety], sinon [system] en flush), doux en file.
     * Le premier repli de la session est précédé du signal [networkVoiceDown]
     * (combiné en une seule parole : l'ordre est atomique). `true` si confié
     * au moteur (mémorisé, oral seul : ni sous-titre ni fanion — sans fin de
     * lecture, un fanion bloquerait la voix douce) ; `false` si aucun moteur
     * câblé (non mémorisé : réémis, il se dira).
     */
    private fun speakOffline(phrase: String, current: Settings, urgent: Boolean): Boolean {
        val alerted = networkAlertSpoken
        val utterance =
            if (alerted) phrase else "${networkVoiceDown(current.speechLanguage)} $phrase"
        if (urgent) {
            val cut = safety
            if (cut != null) {
                networkAlertSpoken = true
                lastPhrase = phrase
                cut.speak(utterance)
                return true
            }
        }
        val engine = system ?: return false
        val locale = SystemTtsEngine.localeOf(current.speechLanguage)
        if (!engine.speak(utterance, urgent, locale, current.ttsSpeed)) return false
        networkAlertSpoken = true
        lastPhrase = phrase
        return true
    }

    /** Voix réseau en panne durable (clé, crédits, cadence) : le repli système prend le relais. */
    private fun isNetworkVoiceDown(error: Throwable): Boolean = when (error) {
        is ApiError.InvalidKey, is ApiError.PaymentRequired, is ApiError.RateLimited -> true
        else -> false
    }

    /**
     * Mot du guidage (`GuidanceWord`) : clip local quand le pack l'a (< 100
     * ms, zéro réseau, sans clé), sinon synthèse réseau mise en cache pour
     * la suite (repli système sans clé ou sur 401/402/429, comme [onPhrase]).
     * Directions douces redites en continu (ordre user du 31/10) : le même
     * mot reparle à chaque émission, sauf le stop (unique, jamais répété).
     * Mêmes garde-fous que [onPhrase] (urgent coupe, via [safety] si câblée)
     * : seul ce qui a vraiment parlé est mémorisé — un mot avalé par un garde
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
        if (!urgent && (!playerIdle || systemBusy())) {
            if (queue) pending = Pending(text, nowMs() + PENDING_TTL_MS)
            return
        }
        if (urgent) player.stop()
        coroutineContext.ensureActive()
        val file = clips?.clipFor(clipId, language, current.ttsVoiceId)?.takeIf { it.isFile }
        if (file != null) {
            lastPhrase = text
            onSpeaking(text)
            player.playFile(file).onFailure { onSpeaking("") }
            return
        }
        if (!current.ttsConfigured) {
            speakOffline(text, current, urgent)
            return
        }
        if (urgent && safety != null) {
            lastPhrase = text
            safety.speak(text)
            return
        }
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, text)
            .getOrElse { error ->
                if (isNetworkVoiceDown(error)) speakOffline(text, current, urgent)
                return
            }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || (!urgent && (!canPlay() || systemBusy()))) return
        clips?.put(clipId, language, current.ttsVoiceId, bytes)
        lastPhrase = text
        onSpeaking(text)
        player.play(bytes).onFailure { onSpeaking("") }
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
        const val PENDING_TTL_MS = 5_000L

        /**
         * Signal unique de repli (« Voix réseau indisponible. » /
         * « Network voice unavailable. ») : parlé une fois par session devant
         * la première phrase dite au TTS système.
         */
        fun networkVoiceDown(language: SpeechLanguage): String = when (language) {
            SpeechLanguage.Fr -> "Voix réseau indisponible."
            SpeechLanguage.En -> "Network voice unavailable."
        }
    }
}
