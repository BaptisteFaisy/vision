package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.AttentionCadence
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.net.ApiError
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
 * Garde-fous du doux : rien par-dessus la réponse du chat (une seule source
 * sonore à la fois — c'est l'appelant qui dit si le lecteur est libre),
 * rien par-dessus la voix route (le doux attend la fin du virage), rien
 * par-dessus le TTS système ([systemBusy] : une alerte de sécurité ne se
 * chevauche pas), et une synthèse annulée (phrase plus fraîche arrivée
 * entre-temps, `collectLatest` côté sonde) n'est jamais jouée. Le stop seul
 * ne se répète jamais ; les directions douces reparlent à chaque émission
 * (ordre user du 31/10).
 *
 * Sans clé ElevenLabs (ou voix réseau en panne : 401/402/429), pas de
 * silence : la phrase douce part au TTS système ([system], en file),
 * précédée une fois par session du signal [networkVoiceDown]. Le repli est
 * oral seul (ni sous-titre, ni fanion — miroir `SafetyVoice` : sans fin de
 * lecture, un fanion bloquerait la voix douce pour toujours), mais mémorisé
 * (pas de répétition).
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
 *
 * Deux ordres user du 1/11 passent par ici : les annonces « Attention … »
 * sont cadencées par [cadence] (même annonce jamais deux fois dans la
 * fenêtre, silence entre deux annonces — le direct ne mitraille plus), et la
 * parole du user ([userSpeaking] : tenue du micro, transcription en cours)
 * passe devant tout ce que dit cette voix — rien ne parle par-dessus lui, les
 * phrases attendent en file ([flushPending]) et se redisent au silence.
 * Seule exception : le stop de Traversée ([GuidanceWord.isStopPhrase]), qui
 * n'attend jamais. La voix de sécurité ([safety]) garde ses propres règles.
 * Le mouvement ([VoicePreempt], § 31) coupe la parole douce en cours au
 * lieu d'attendre le silence, sécurité et parole du user exceptées.
 */
/**
 * Préemption d'une phrase douce par le mouvement : la phrase coupe la
 * parole douce en cours (chat, route, autre annonce — le lecteur partagé
 * est coupé par `play()`) au lieu d'attendre le silence, mais jamais la
 * sécurité (fenêtre P1/P2 ou TTS système en cours : la phrase part en
 * file comme le doux). Dans la file, le rang tient l'ordre (l'ordinal
 * compare, à rang égal la plus fraîche gagne) : une voiture ([VEHICLE])
 * ne se fait pas remplacer par une personne ([PERSON]), qui ne se fait
 * pas remplacer par un doux ([NONE]). Ordre user du 01/11, § 31.
 */
enum class VoicePreempt { NONE, PERSON, VEHICLE }

class ProximityVoice(
    private val player: SpeechPlayer,
    private val settings: () -> Settings,
    private val safety: SafetyVoice? = null,
    private val onSpeaking: (String) -> Unit = {},
    private val canPlay: () -> Boolean = { true },
    private val synthesize: suspend (apiKey: String, voiceId: String, text: String) -> Result<ByteArray> =
        { apiKey, voiceId, text ->
            ElevenLabsTtsClient(apiKey, voiceId, cache = ElevenLabsTtsClient.sharedPhrases).synthesize(text)
        },
    private val clips: VoicePack? = null,
    private val routeSpeaking: () -> Boolean = { false },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val system: SystemTtsEngine? = null,
    private val systemBusy: () -> Boolean = { false },
    private val userSpeaking: () -> Boolean = { false },
    private val cadence: AttentionCadence = AttentionCadence(),
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

    /** Signal « voix réseau indisponible » déjà parlé (une fois par session). */
    private var networkAlertSpoken = false

    /** Lecture déjà coupée pour la parole du user (une fois par prise de parole). */
    private var cutForUserDone = false

    /** Nouvelle session : la prochaine phrase sera dite même si elle ressemble à la précédente. */
    fun reset() {
        lastPhrase = null
        lastPhraseAtMs = 0L
        pending = null
        pendingRoute = null
        pendingArrival = null
        networkAlertSpoken = false
        cutForUserDone = false
        cadence.reset()
    }

    /**
     * Parole du user (tenue du micro, transcription) : la voix se tait et coupe
     * ce qu'elle lisait (chat compris). Une seule coupe par prise de parole —
     * le fanion est rendu, sinon le lecteur resterait cru occupé pour toujours.
     * Le stop de Traversée ([GuidanceWord.isStopPhrase]) n'est jamais coupé.
     */
    private fun yieldsToUser(phrase: String): Boolean =
        userSpeaking() && !GuidanceWord.isStopPhrase(phrase)

    /** Coupe la lecture en cours pour la parole du user (une fois), fanion rendu. */
    private fun cutForUser() {
        if (cutForUserDone) return
        cutForUserDone = true
        player.stop()
        onSpeaking("")
    }

    /**
     * Vrai si le user parle à iSee (tenue du micro, transcription) : l'appelant
     * ne fait alors rien couper — les alertes attendent le silence ([flushPending])
     * au lieu de passer par la voix de sécurité.
     */
    fun userIsSpeaking(): Boolean = userSpeaking()

    /**
     * Nouvelle phrase de SAM. [playerIdle] vrai = aucune bulle du chat en cours de lecture ;
     * sinon le doux ne dit rien — mais la phrase avalée par un garde transitoire n'est
     * pas mémorisée : réémise, elle se dira une fois la voie libre (seul ce qui a
     * vraiment parlé est dédupliqué, pendant [REPEAT_TTL_MS]). [urgent] part par
     * la voix de sécurité ([speakUrgent], grave, hors-ligne, coupe tout),
     * dictée ou pas. Tant que la voix route parle ([routeSpeaking]) ou que
     * le TTS système parle ([systemBusy]), le doux se tait aussi, puis se
     * dit à la prochaine émission.
     * [queue] : la phrase douce tuée par le chat, la route, le système ou [busy]
     * est gardée (une seule, la plus fraîche) et redite par [flushPending] dès
     * le silence, dans [PENDING_TTL_MS] — sans file, elle se redit à la
     * prochaine émission. [busy] : P1/P2 en cours, vus par l'appelant — la
     * phrase douce part en file si [queue] (jamais de chevauchement SafetyVoice).
     * [dedupMs] : délai de déduplication de cette phrase ([REPEAT_TTL_MS] par
     * défaut — le compteur de marches y passe 1 s : un compte répété n'est
     * pas un bégaiement, c'est le pas suivant).
     * [preempt] : le mouvement coupe la parole douce en cours (chat,
     * route, annonce — `speak` coupe le lecteur partagé) au lieu
     * d'attendre le silence ; la sécurité et la parole du user gardent
     * le dessus (fenêtre [busy], TTS système ou micro en cours : file
     * comme le doux, rang tenu par [queuePending]). L'urgent garde la
     * main (traité avant).
     */
    suspend fun onPhrase(
        phrase: String,
        playerIdle: Boolean,
        urgent: Boolean = false,
        queue: Boolean = false,
        busy: () -> Boolean = { false },
        dedupMs: Long = REPEAT_TTL_MS,
        preempt: VoicePreempt = VoicePreempt.NONE,
    ) {
        if (saidRecently(phrase, dedupMs)) return
        if (cadence.blocked(phrase, nowMs(), urgent)) return
        if (yieldsToUser(phrase)) {
            cutForUser()
            if (queue) queuePending(phrase, preempt)
            return
        }
        if (urgent) {
            speakUrgent(phrase)
            return
        }
        val current = settings()
        if (preempt != VoicePreempt.NONE) {
            if (busy() || systemBusy()) {
                if (queue) queuePending(phrase, preempt)
                return
            }
            speak(phrase, current)
            return
        }
        if (!playerIdle || routeSpeaking() || busy() || systemBusy()) {
            if (queue) queuePending(phrase, VoicePreempt.NONE)
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
     * en mode Laure la voix route part d'ici, pas du haut-parleur paresseux) ;
     * elle attend le TTS système ([systemBusy], jamais de chevauchement d'alerte).
     */
    suspend fun onRoutePhrase(
        phrase: String,
        playerIdle: Boolean,
        urgent: Boolean = false,
    ) {
        if (saidRecently(phrase)) return
        if (cadence.blocked(phrase, nowMs(), urgent)) return
        if (yieldsToUser(phrase)) {
            cutForUser()
            pendingRoute = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        if (urgent) {
            speakUrgent(phrase)
            return
        }
        val current = settings()
        if (!playerIdle || systemBusy()) {
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
        if (yieldsToUser(phrase)) {
            cutForUser()
            pendingArrival = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        if (!playerIdle || routeSpeaking() || systemBusy()) {
            pendingArrival = Pending(phrase, nowMs() + PENDING_TTL_MS)
            return
        }
        speak(phrase, current)
    }

    /**
     * Redit les phrases gardées dès que plus rien ne parle : chat silencieux
     * ([playerIdle]), route tue, système tu ([systemBusy]) et [busy] faux
     * (P1/P2 en cours, vu par l'appelant) — la consigne route d'abord, puis
     * la douce d'obstacle, puis l'arrivée sur le passage (une seule par appel,
     * jamais de rafale). Périmée ([PENDING_TTL_MS]) ou déjà dite : jetée. En
     * échec (synthèse, porte) : gardée pour le prochain silence — la file ne
     * perd pas. À appeler à chaque frame par le guetteur en file, Traversée
     * comprise.
     */
    suspend fun flushPending(playerIdle: Boolean, busy: () -> Boolean = { false }) {
        if (!playerIdle || routeSpeaking() || busy() || systemBusy() || userSpeaking()) return
        val current = settings()
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
        if (!playerIdle || routeSpeaking() || systemBusy() || userSpeaking()) return
        val current = settings()
        if (hasFresh(pendingRoute) || hasFresh(pending)) return
        flushOne(pendingArrival, current) { pendingArrival = null }
    }

    /** Vrai si [due] attend encore d'être dite (fraîche et jamais parlée). */
    private fun hasFresh(due: Pending?): Boolean =
        due != null && due.phrase != lastPhrase && nowMs() < due.expiresAtMs

    /**
     * Vrai si [phrase] vient d'être dite (moins de [ttlMs]) : la
     * déduplication est bornée — un danger présent se redit, le retour
     * d'une scène parle, et rien ne bégaie. Le stop garde sa règle propre
     * (jamais répété, [speakWord]).
     */
    private fun saidRecently(phrase: String, ttlMs: Long = REPEAT_TTL_MS): Boolean =
        phrase == lastPhrase && nowMs() - lastPhraseAtMs < ttlMs

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
        // Annonce « Attention … » encore dans sa fenêtre : gardée (sa cadence
        // la libérera) — pas dite, pas perdue avant [PENDING_TTL_MS].
        if (cadence.blocked(due.phrase, nowMs())) return false
        if (!speak(due.phrase, current)) return false
        clear()
        return true
    }

    /**
     * Urgence admise (jamais dite récemment, jamais un stop déjà dit) : voix
     * de sécurité aussitôt (grave, hors-ligne, coupe tout — [SafetyVoice]),
     * mémorisée comme le doux (redite après [REPEAT_TTL_MS] si elle dure).
     * Orale seule : aucun sous-titre ni fanion (le TTS système ne rend pas
     * de fin, un fanion sans fin muterait la voix douce — voir `cutSpeaking`).
     * Sans sécurité câblée (tests) : synthèse immédiate, sans garde — une
     * urgence ne se perd jamais.
     */
    private suspend fun speakUrgent(phrase: String) {
        if (phrase.isBlank()) return
        val cut = safety
        if (cut != null) {
            cut.speak(phrase)
            lastPhrase = phrase
            lastPhraseAtMs = nowMs()
            cadence.record(phrase, nowMs(), urgent = true)
            return
        }
        player.stop()
        coroutineContext.ensureActive()
        val current = settings()
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, phrase).getOrElse { return }
        coroutineContext.ensureActive()
        if (bytes.isEmpty()) return
        lastPhrase = phrase
        lastPhraseAtMs = nowMs()
        cadence.record(phrase, nowMs(), urgent = true)
        cutForUserDone = false
        onSpeaking(phrase)
        player.play(bytes).onFailure { onSpeaking("") }
    }

    /**
     * Synthèse + lecture d'une phrase douce admise ; `true` si parlée (ou
     * confiée au TTS système). Sans clé, ou voix réseau en panne (401/402/429) :
     * repli système ([speakOffline]), jamais de silence quand un moteur est
     * câblé. Mémorisée au moment de parler seulement.
     */
    private suspend fun speak(phrase: String, current: Settings): Boolean {
        coroutineContext.ensureActive()
        if (!current.ttsConfigured) return speakOffline(phrase, current)
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, phrase)
            .getOrElse { error ->
                return if (isNetworkVoiceDown(error)) speakOffline(phrase, current) else false
            }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || !canPlay() || systemBusy()) return false
        // Mémorisée au moment de parler seulement : un avalement transitoire
        // (chat, route, système, porte, synthèse) ne tait pas les réémissions.
        lastPhrase = phrase
        lastPhraseAtMs = nowMs()
        cadence.record(phrase, nowMs())
        cutForUserDone = false
        onSpeaking(phrase)
        player.play(bytes).onFailure { onSpeaking("") }
        return true
    }

    /**
     * Repli TTS système (hors-ligne) : [phrase] douce dite sans ElevenLabs,
     * en file. Le premier repli de la session est précédé du signal
     * [networkVoiceDown] (combiné en une seule parole : l'ordre est
     * atomique). `true` si confié au moteur (mémorisé, oral seul : ni
     * sous-titre ni fanion — sans fin de lecture, un fanion bloquerait la
     * voix douce) ; `false` si aucun moteur câblé (non mémorisé : réémis,
     * il se dira).
     */
    private fun speakOffline(phrase: String, current: Settings): Boolean {
        val alerted = networkAlertSpoken
        val utterance =
            if (alerted) phrase else "${networkVoiceDown(current.speechLanguage)} $phrase"
        val engine = system ?: return false
        val locale = SystemTtsEngine.localeOf(current.speechLanguage)
        if (!engine.speak(utterance, false, locale, current.ttsSpeed, 1f)) return false
        networkAlertSpoken = true
        lastPhrase = phrase
        lastPhraseAtMs = nowMs()
        cadence.record(phrase, nowMs())
        cutForUserDone = false
        return true
    }

    /** Voix réseau en panne durable (clé, crédits, cadence) : le repli système prend le relais. */
    private fun isNetworkVoiceDown(error: Throwable): Boolean = when (error) {
        is ApiError.InvalidKey, is ApiError.PaymentRequired, is ApiError.RateLimited -> true
        else -> false
    }

    /**
     * Mot du guidage (`GuidanceWord`) : doux en clip local quand le pack l'a
     * (< 100 ms, zéro réseau), sinon synthèse réseau mise en cache pour la
     * suite (repli système sans clé ou sur 401/402/429, comme [onPhrase]).
     * Directions douces redites en continu (ordre user du 31/10) : le même
     * mot reparle à chaque émission ; le stop part en voix de sécurité
     * ([speakUrgent], unique, jamais répété — ses clips sont l'héritage
     * d'avant, l'urgent ne les joue plus). Mêmes garde-fous que [onPhrase]
     * (urgent coupe) : seul ce qui a vraiment parlé est mémorisé — un mot
     * avalé par un garde transitoire ne tait pas ses réémissions. [queue] :
     * le mot doux tué part en file et se redit par [flushPending] (en
     * synthèse, pas en clip : parlé lentement vaut mieux que perdu).
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
        if (yieldsToUser(text)) {
            cutForUser()
            if (queue) queuePending(text, VoicePreempt.NONE)
            return
        }
        if (urgent) {
            speakUrgent(text)
            return
        }
        val current = settings()
        if (!playerIdle || systemBusy()) {
            if (queue) queuePending(text, VoicePreempt.NONE)
            return
        }
        val file = clips?.clipFor(clipId, language, current.ttsVoiceId)?.takeIf { it.isFile }
        if (file != null) {
            lastPhrase = text
            lastPhraseAtMs = nowMs()
            onSpeaking(text)
            player.playFile(file).onFailure { onSpeaking("") }
            return
        }
        if (!current.ttsConfigured) {
            speakOffline(text, current)
            return
        }
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, text)
            .getOrElse { error ->
                if (isNetworkVoiceDown(error)) speakOffline(text, current)
                return
            }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || !canPlay() || systemBusy()) return
        clips?.put(clipId, language, current.ttsVoiceId, bytes)
        lastPhrase = text
        lastPhraseAtMs = nowMs()
        onSpeaking(text)
        player.play(bytes).onFailure { onSpeaking("") }
    }

    /** Phrase douce en attente de silence : [phrase] à redire avant [expiresAtMs], [preempt] son rang (la file tient l'ordre du mouvement). */
    private data class Pending(val phrase: String, val expiresAtMs: Long, val preempt: VoicePreempt = VoicePreempt.NONE)

    /**
     * Garde [phrase] de rang [preempt] en file douce : une voiture en
     * mouvement ne se fait pas remplacer par une personne, ni une
     * personne par un doux (l'ordinal compare, à rang égal la plus
     * fraîche gagne comme avant).
     */
    private fun queuePending(phrase: String, preempt: VoicePreempt) {
        val old = pending
        if (old == null || preempt.ordinal >= old.preempt.ordinal) {
            pending = Pending(phrase, nowMs() + PENDING_TTL_MS, preempt)
        }
    }

    companion object {
        /** Rattrapage borné : au-delà, la scène a changé, la phrase est périmée. */
        const val PENDING_TTL_MS = 5_000L

        /**
         * Redite d'une phrase identique : 4 s, le temps de dire une phrase
         * courte (comme le délai amont) — les directions en clips tournent
         * à 2 s, la synthèse réseau coûte plus cher. Au-delà, un danger
         * présent se redit et le retour d'une scène parle.
         */
        const val REPEAT_TTL_MS = 4_000L

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
