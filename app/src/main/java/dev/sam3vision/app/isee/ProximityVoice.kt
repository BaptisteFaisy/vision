package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.AttentionCadence
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Voix de proximité de la fenêtre caméra : directions douces dites en continu
 * ([speakWord]), positions et obstacles doux redits après [REPEAT_TTL_MS]
 * tant qu'ils durent ([onPhrase] : un danger présent ne se tait pas, le retour
 * d'une scène parle) — en voix Laure (ElevenLabs).
 *
 * L'urgence ([urgent] : stop, danger qui s'approche, sortie de trace) ne parle
 * jamais Laure : elle part par [safety] (voix de sécurité, système grave,
 * hors-ligne — une urgence ne sonne jamais comme une phrase normale), coupe
 * tout (chat compris, dictée ou pas) et reste orale seule (aucun sous-titre ni
 * fanion parlant : le TTS système ne rend pas de fin). Les clips stop
 * (`guide_stop`, `tl_stop`) sont l'héritage d'avant : l'urgent ne les joue plus.
 *
 * Garde-fous du doux : rien par-dessus la réponse du chat (une seule source
 * sonore à la fois — l'appelant dit si le lecteur est libre), rien par-dessus la
 * voix route, rien par-dessus le TTS système ([systemBusy] : une alerte de
 * sécurité ne se chevauche pas), et une synthèse annulée (`collectLatest` côté
 * sonde) n'est jamais jouée. Le stop seul ne se répète jamais ; les directions
 * douces reparlent à chaque émission (ordre user du 31/10). Sans clé ElevenLabs
 * (ou voix réseau en panne : 401/402/429), pas de silence : la phrase douce
 * part au TTS système ([system], en file), précédée une fois par session du
 * signal [VoiceOfflineFallback].
 *
 * [synthesize] injecte le moteur doux (ElevenLabs en prod, faux en test —
 * miroir `IseePlayback`), [clips] le pack voix des mots doux (clip local
 * < 100 ms, synthèse + cache sinon), [routeSpeaking] dit si la voix route
 * parle. [flushPending] redit la phrase gardée dès le silence, dans
 * [PENDING_TTL_MS] ; l'arrivée sur le passage ([onArrivalPhrase]) passe en
 * dernier — la plus basse priorité de toutes les annonces.
 *
 * Point 4 du patch P0 (« la phrase bloquée doit attendre son tour, pas
 * disparaître ») : la cadence reçoit le `trackId` de la personne suivie
 * ([onPhrase], [speakWord] : `null` par défaut, aucun appelant cassé — une
 * personne = un tour), donc deux passants à 1 s d'intervalle parlent tous les
 * deux ; et l'annonce bloquée par la redite n'est pas jetée : elle sort au
 * silence ([flushPending]), une seule par silence, avec la voix du doux.
 *
 * Ordres user du 1/11 : les annonces « Attention … » sont cadencées par
 * [cadence] (jamais deux fois dans la fenêtre, silence entre deux annonces), et
 * la parole du user ([userSpeaking] : micro tenu, transcription en cours) passe
 * devant tout ce que dit cette voix — les phrases attendent en file
 * ([flushPending]) et se redisent au silence, sauf le stop de Traversée
 * ([GuidanceWord.isStopPhrase]). Le mouvement ([VoicePreempt], § 31) coupe la
 * parole douce en cours au lieu d'attendre, sécurité et user exceptés. Les
 * files ([queues], [VoicePendingQueue]) ne disent rien : elles gardent,
 * [onPhrase] et [flushPending] décident.
 */
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

    /**
     * Phrases gardées pour le silence : file douce, consigne route, arrivée
     * sur le passage, annonces que la redite avait bloquées ([flushRedite]) —
     * mécanique et rangs dans [VoicePendingQueue].
     */
    internal val queues = VoicePendingQueue()

    /** Lecture déjà coupée pour la parole du user (une fois par prise de parole). */
    private var cutForUserDone = false

    /** Repli hors-ligne (voix réseau en panne) : dit au TTS système, mémorisé comme le doux. */
    private val offline = VoiceOfflineFallback(system) {
        lastPhrase = it
        lastPhraseAtMs = nowMs()
        cadence.record(it, nowMs())
        cutForUserDone = false
    }

    /** Nouvelle session : la prochaine phrase sera dite même si elle ressemble à la précédente. */
    fun reset() {
        lastPhrase = null
        lastPhraseAtMs = 0L
        queues.clearAll()
        offline.reset()
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
     * comme le doux, rang tenu par [queues]). L'urgent garde la
     * main (traité avant).
     * [trackId] : la personne suivie que [phrase] concerne (pisteur de
     * foule) — la cadence s'en sert comme clé (une personne = un tour),
     * donc deux passants à 1 s d'intervalle parlent tous les deux. `null`
     * (défaut) = comportement d'avant le patch P0 point 4. Une annonce
     * bloquée par la redite n'est pas perdue : elle attend son tour et
     * sort au silence ([flushPending], une seule par silence).
     */
    suspend fun onPhrase(
        phrase: String,
        playerIdle: Boolean,
        urgent: Boolean = false,
        queue: Boolean = false,
        busy: () -> Boolean = { false },
        dedupMs: Long = REPEAT_TTL_MS,
        preempt: VoicePreempt = VoicePreempt.NONE,
        trackId: Int? = null,
    ) {
        if (saidRecently(phrase, dedupMs)) return
        if (cadence.blocked(phrase, nowMs(), urgent, trackId)) return
        if (yieldsToUser(phrase)) {
            cutForUser()
            if (queue) queues.queueSoft(phrase, preempt, trackId, nowMs())
            return
        }
        if (urgent) {
            speakUrgent(phrase, trackId)
            return
        }
        val current = settings()
        if (preempt != VoicePreempt.NONE) {
            if (busy() || systemBusy()) {
                if (queue) queues.queueSoft(phrase, preempt, trackId, nowMs())
                return
            }
            speak(phrase, current, trackId)
            return
        }
        if (!playerIdle || routeSpeaking() || busy() || systemBusy()) {
            if (queue) queues.queueSoft(phrase, VoicePreempt.NONE, trackId, nowMs())
            return
        }
        speak(phrase, current, trackId)
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
            queues.queueRoute(phrase, nowMs())
            return
        }
        if (urgent) {
            speakUrgent(phrase)
            return
        }
        val current = settings()
        if (!playerIdle || systemBusy()) {
            queues.queueRoute(phrase, nowMs())
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
            queues.queueArrival(phrase, nowMs())
            return
        }
        if (!playerIdle || routeSpeaking() || systemBusy()) {
            queues.queueArrival(phrase, nowMs())
            return
        }
        speak(phrase, current)
    }

    /**
     * Redit les phrases gardées dès que plus rien ne parle : chat silencieux
     * ([playerIdle]), route tue, système tu ([systemBusy]) et [busy] faux
     * (P1/P2 en cours, vu par l'appelant) — la consigne route d'abord, puis
     * la douce d'obstacle, puis l'annonce que la redite avait bloquée
     * ([flushRedite], une seule par silence), puis l'arrivée sur le passage
     * (une seule phrase par appel, jamais de rafale). Périmée
     * ([PENDING_TTL_MS]) ou déjà dite : jetée. En échec (synthèse, porte) :
     * gardée pour le prochain silence — la file ne perd pas. À appeler à
     * chaque frame par le guetteur en file, Traversée comprise.
     */
    suspend fun flushPending(playerIdle: Boolean, busy: () -> Boolean = { false }) {
        if (!playerIdle || routeSpeaking() || busy() || systemBusy() || userSpeaking()) return
        val current = settings()
        if (flushOne(queues.routeDue(), current) { queues.dropRoute() }) return
        if (flushOne(queues.softDue(), current) { queues.dropSoft() }) return
        if (flushRedite(current)) return
        flushOne(queues.arrivalDue(), current) { queues.dropArrival() }
    }

    /**
     * Redit au silence l'annonce que la redite avait bloquée (« la phrase
     * bloquée doit attendre son tour, pas disparaître », patch P0 point 4) :
     * [VoicePendingQueue.drainCadence] sort les phrases dues
     * ([AttentionCadence.pending]), une seule fois chacune, et le doux les dit
     * de la même voix que [speak] (Laure, repli TTS système) — **une seule par
     * silence**, le reste tenant dans la file d'un silence à l'autre. Une
     * phrase redite entre-temps est sautée ; un échec de synthèse la remet en
     * tête (la file ne perd pas). `true` si une phrase est sortie.
     */
    private suspend fun flushRedite(current: Settings): Boolean =
        queues.drainCadence({ cadence.pending(nowMs()) }, { saidRecently(it) }) { speak(it, current) }

    /**
     * Redit l'arrivée gardée seule, au silence (chat tu, route tue) et sans
     * route ni doux frais devant elle : l'arrivée reste la dernière, même
     * hors [flushPending] (Traversée, où la file obstacles est coupée mais
     * pas le rattrapage latéral). Périmée ou déjà dite : jetée.
     */
    suspend fun flushArrival(playerIdle: Boolean) {
        if (!playerIdle || routeSpeaking() || systemBusy() || userSpeaking()) return
        val current = settings()
        if (hasFresh(queues.routeDue()) || hasFresh(queues.softDue())) return
        flushOne(queues.arrivalDue(), current) { queues.dropArrival() }
    }

    /** Vrai si [due] attend encore d'être dite : la règle de fraîcheur vit dans [VoicePendingQueue]. */
    private fun hasFresh(due: VoicePendingQueue.Pending?): Boolean =
        queues.hasFresh(due, lastPhrase, nowMs())

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
     * (l'appelant garde son `Pending`) au lieu d'être perdue. La personne
     * suivie ([VoicePendingQueue.Pending.trackId]) garde sa clé de cadence.
     */
    private suspend fun flushOne(due: VoicePendingQueue.Pending?, current: Settings, clear: () -> Unit): Boolean {
        if (due == null) return false
        if (due.phrase == lastPhrase || nowMs() >= due.expiresAtMs) {
            clear()
            return false
        }
        // Annonce « Attention … » encore dans sa fenêtre : gardée (sa cadence
        // la libérera) — pas dite, pas perdue avant [PENDING_TTL_MS].
        if (cadence.blocked(due.phrase, nowMs(), trackId = due.trackId)) return false
        if (!speak(due.phrase, current, due.trackId)) return false
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
     * urgence ne se perd jamais. L'urgent échappe à la redite ([cadence] :
     * `urgent = true` court-circuite la fenêtre de 20 s, seul son propre
     * silence de 2 s est tenu) ; [trackId] garde la clé de la personne.
     */
    private suspend fun speakUrgent(phrase: String, trackId: Int? = null) {
        if (phrase.isBlank()) return
        val cut = safety
        if (cut != null) {
            cut.speak(phrase)
            lastPhrase = phrase
            lastPhraseAtMs = nowMs()
            cadence.record(phrase, nowMs(), urgent = true, trackId = trackId)
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
        cadence.record(phrase, nowMs(), urgent = true, trackId = trackId)
        cutForUserDone = false
        onSpeaking(phrase)
        player.play(bytes).onFailure { onSpeaking("") }
    }

    /**
     * Synthèse + lecture d'une phrase douce admise ; `true` si parlée (ou
     * confiée au TTS système). Sans clé, ou voix réseau en panne (401/402/429) :
     * repli système ([VoiceOfflineFallback]), jamais de silence quand un moteur est
     * câblé. Mémorisée au moment de parler seulement. [trackId] : la personne
     * que la phrase concerne, pour la clé de [cadence].
     */
    private suspend fun speak(phrase: String, current: Settings, trackId: Int? = null): Boolean {
        coroutineContext.ensureActive()
        if (!current.ttsConfigured) return offline.speak(phrase, current)
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, phrase)
            .getOrElse { error ->
                return if (VoiceOfflineFallback.isNetworkVoiceDown(error)) offline.speak(phrase, current) else false
            }
        coroutineContext.ensureActive()
        if (bytes.isEmpty() || !canPlay() || systemBusy()) return false
        // Mémorisée au moment de parler seulement : un avalement transitoire
        // (chat, route, système, porte, synthèse) ne tait pas les réémissions.
        lastPhrase = phrase
        lastPhraseAtMs = nowMs()
        cadence.record(phrase, nowMs(), trackId = trackId)
        cutForUserDone = false
        onSpeaking(phrase)
        player.play(bytes).onFailure { onSpeaking("") }
        return true
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
     * [trackId] : la personne suivie que [text] concerne (même clé de
     * cadence que [onPhrase], une personne = un tour) ; `null` par défaut,
     * aucun appelant cassé.
     */
    suspend fun speakWord(
        clipId: String,
        text: String,
        language: SpeechLanguage,
        playerIdle: Boolean,
        urgent: Boolean = false,
        queue: Boolean = false,
        trackId: Int? = null,
    ) {
        if (text == lastPhrase && GuidanceWord.isStopPhrase(text)) return
        if (yieldsToUser(text)) {
            cutForUser()
            if (queue) queues.queueSoft(text, VoicePreempt.NONE, trackId, nowMs())
            return
        }
        if (urgent) {
            speakUrgent(text, trackId)
            return
        }
        val current = settings()
        if (!playerIdle || systemBusy()) {
            if (queue) queues.queueSoft(text, VoicePreempt.NONE, trackId, nowMs())
            return
        }
        playWord(clipId, text, language, current)
    }

    /**
     * Rend [text] en clip local quand le pack l'a (le plus rapide), sinon en
     * synthèse réseau mise en cache pour la suite — repli système sans clé ou
     * sur 401/402/429. Corps historique de [speakWord] : seul ce qui a
     * vraiment parlé est mémorisé (un mot avalé par un garde transitoire ne
     * tait pas ses réémissions).
     */
    private suspend fun playWord(clipId: String, text: String, language: SpeechLanguage, current: Settings) {
        val file = clips?.clipFor(clipId, language, current.ttsVoiceId)?.takeIf { it.isFile }
        if (file != null) {
            lastPhrase = text
            lastPhraseAtMs = nowMs()
            onSpeaking(text)
            player.playFile(file).onFailure { onSpeaking("") }
            return
        }
        if (!current.ttsConfigured) {
            offline.speak(text, current)
            return
        }
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, text)
            .getOrElse { error ->
                if (VoiceOfflineFallback.isNetworkVoiceDown(error)) offline.speak(text, current)
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

    companion object {
        /** Rattrapage borné : au-delà, la scène a changé, la phrase est périmée. */
        const val PENDING_TTL_MS = 5_000L

        /**
         * Annonces dues gardées d'un silence à l'autre ([flushRedite]) : au-delà,
         * la plus ancienne est lâchée — une file de rattrapage ne bloque jamais
         * la parole fraîche.
         */
        const val CADENCE_QUEUE_MAX = 4

        /**
         * Redite d'une phrase identique : 4 s, le temps de dire une phrase
         * courte (comme le délai amont) — les directions en clips tournent
         * à 2 s, la synthèse réseau coûte plus cher. Au-delà, un danger
         * présent se redit et le retour d'une scène parle.
         */
        const val REPEAT_TTL_MS = 4_000L
    }
}
