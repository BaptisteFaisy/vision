package dev.sam3vision.app.speech

import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Canal vocal du guidage embarqué : [VoiceNavAnnouncer] décide (quoi, quand),
 * [SystemTtsEngine] prononce (voix système française, file sans chevauchement).
 *
 * Hooks appelés par le moteur de navigation : [onTurn], [onLiteral],
 * [onArrival], [onOffRoute], [onRerouted], [onBackOnRoute] — plus
 * [onStarted]/[onStopped] pour la session et [onHazard] pour le danger
 * obstacle (sonde SAM, déjà débouncé en amont : parlé tel quel, en coupant).
 * Chaque hook renvoie la phrase dite, ou `null` si la politique a gardé le
 * silence (répétition, délai), si le moteur est fermé — ou si l'admise est
 * partie en file (dite au silence, `null` en attendant).
 *
 * Une seule voix : tout le GPS part en file (danger grave devant, jamais
 * de coupe — ordre user du 02/11) — le canal route et le canal obstacle
 * partagent ce moteur, aucune phrase ne parle sur une autre.
 *
 * File bornée (une seule en attente, la plus fraîche, [PENDING_TTL_MS])
 * : une consigne admise pendant une parole n'est ni perdue, ni empilée sans
 * fin dans le moteur — elle se dit au silence, avant l'événement courant ;
 * périmée ou ré-admission refusée, elle tombe sans parler. Le danger prend
 * le créneau (le plus frais), parlé au silence comme le doux — la gravité
 * reste (pitch bas), l'interruption non.
 *
 * Marqué dit après parole moteur ([VoiceNavAnnouncer.admitEvent] puis
 * [VoiceNavAnnouncer.markSpoken]) : un refus moteur (fermé, en panne) ne
 * marque pas — la réémission reparle au lieu d'être avalée par une mémoire
 * mensongère.
 *
 * Comme [NavigationAnnouncer], les réglages ([settings], langue et débit) sont
 * lus à chaque annonce et rien ne lève jamais : un collaborateur qui jette
 * (réglages, politique, moteur) devient un silence (`null`).
 *
 * Cycle de vie : [shutdown] depuis `onCleared`/`onDestroy` (idempotent via le
 * moteur) ; [reset] à chaque nouvelle session pour que le départ se dise.
 * La sérialisation des phrases est déléguée au moteur unique (`QUEUE_ADD`) :
 * pas de mutex ici, `TextToSpeech.speak` est sûr entre fils.
 */
class VoiceNavSpeaker(
    private val engine: SystemTtsEngine,
    private val settings: () -> Settings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val announcer: VoiceNavAnnouncer = VoiceNavAnnouncer(),
    /**
     * L'utilisateur parle (tenue du doigt en dictée) : le GPS se tait,
     * urgences comprises (miroir du bus — ordre user du 02/11).
     */
    private val userSpeaking: () -> Boolean = { false },
) {

    /** Douce admise en attente de silence (`null` si aucune) : une seule, la plus fraîche. */
    private var pending: PendingEvent? = null

    /** Virage franc à [cap], à [meters] mètres ([street] si connue) : préavis de loin, ordre sec de près. */
    fun onTurn(cap: SteeringCap, meters: Float? = null, street: String? = null): String? =
        announce(VoiceNavEvent.Turn(cap, meters, street))

    /** Arrivée à [destination] (vide = arrivée sans nom). */
    fun onArrival(destination: String): String? =
        announce(VoiceNavEvent.Arrival(destination))

    /** Sortie de trace : prioritaire (voix grave, tête de file), sans couper. */
    fun onOffRoute(): String? = announce(VoiceNavEvent.OffRoute)

    /** Nouvel itinéraire après recalcul : prioritaire, sans couper. */
    fun onRerouted(): String? = announce(VoiceNavEvent.Rerouted)

    /** Guidage démarré vers [destination] ; blanc = silence, comme [NavigationAnnouncer]. */
    fun onStarted(destination: String): String? =
        announce(VoiceNavEvent.Started(destination))

    /**
     * Guidage arrêté sur ordre de l'utilisateur : parle (« Guidage arrêté. »,
     * urgent) puis oublie la session — le prochain départ se dira.
     */
    fun onStopped(): String? {
        val phrase = announce(VoiceNavEvent.Stopped)
        runCatching { announcer.reset() }
        return phrase
    }

    /** Retour sur le tracé : informatif, ne coupe pas la phrase en cours. */
    fun onBackOnRoute(): String? = announce(VoiceNavEvent.BackOnRoute)

    /** Phrase littérale du moteur (demi-tour, giratoire, destination). */
    fun onLiteral(phrase: String): String? = announce(VoiceNavEvent.Literal(phrase))

    /**
     * Rappel périodique (guidage 5 s) : redit volontaire, hors porte
     * anti-répétition — la porte ne sait pas redire deux fois la même clé,
     * et le rappel ne doit ni l'affamer ni s'y noyer. Parlé au silence seul
     * (jamais de file : un rappel en retard est périmé), après la douce en
     * attente s'il y en a une ; le user qui parle passe d'abord. Renvoie la
     * phrase dite, `null` si le silence a été gardé. Ne lève jamais.
     */
    fun onRepeat(phrase: String): String? {
        try {
            val clean = phrase.trim()
            if (clean.isEmpty()) return null
            if (userSpeaking()) return null
            drainPending(clock())
            if (engine.speaking) return null
            return utter(clean, danger = false)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Danger obstacle (« Gauche. ») : coupe
     * la phrase en cours, même une consigne. Vide la file douce (danger
     * frais : l'ancienne consigne est périmée). Blanc = silence, comme [onStarted].
     * Seul coupeur restant du canal (côté obstacle, pas GPS).
     */
    fun onHazard(phrase: String): String? {
        val clean = phrase.trim()
        if (clean.isEmpty()) return null
        pending = null
        return runCatching { utter(clean, danger = true, flush = true) }.getOrNull()
    }

    /** Vrai si une phrase route est en cours de lecture (arbitre vocal : l'obstacle attend). */
    fun isSpeaking(): Boolean = engine.speaking

    /** Coupe la parole en cours (écran quitté, app en arrière-plan). */
    fun stop() = engine.stop()

    /** Libération finale (idempotente) : le canal n'accepte plus rien ensuite. */
    fun shutdown() = engine.close()

    /** Nouvelle session : la mémoire anti-répétition et la file sont oubliées. */
    fun reset() {
        announcer.reset()
        pending = null
    }

    /**
     * Canal commun : politique ([VoiceNavAnnouncer.admitEvent]) puis moteur
     * ([SystemTtsEngine]) ; marquée dite après parole ([markSpoken]) — un
     * refus moteur laisse la phrase ré-admissible. Admise pendant une
     * parole : en file (la plus fraîche remplace, danger compris),
     * `null` en attendant — jamais de coupe (ordre user du 02/11), la
     * gravité reste (pitch bas, pas d'interruption).
     */
    private fun announce(event: VoiceNavEvent): String? {
        try {
            // Le user parle : le GPS se tait, urgences comprises (miroir du bus).
            if (userSpeaking()) return null
            val now = clock()
            val danger = SpeechArbitration.priorityOf(event) == SpeechPriority.Danger
            if (!danger) drainPending(now)
            val language = settings().speechLanguage
            val phrase = announcer.admitEvent(now, event, language) ?: return null
            if (engine.speaking) {
                pending = PendingEvent(event, now)
                return null
            }
            val spoken = utter(phrase, danger)
            if (spoken != null) announcer.markSpoken(event, now, phrase)
            return spoken
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Dit la gardée au silence ([pending], douce ou danger) : ré-admise à
     * l'instant (la porte a pu tourner depuis — refusée, elle tombe),
     * marquée dite après parole. Périmée ([PENDING_TTL_MS]) : jetée sans
     * parler — une consigne de virage périmée est pire que le silence.
     * Moteur occupé : gardée pour le prochain événement. Le danger garde
     * sa voix grave (pitch, pas d'interruption).
     */
    private fun drainPending(now: Long) {
        val due = pending ?: return
        if (engine.speaking) return
        pending = null
        if (now - due.atMs >= PENDING_TTL_MS) return
        val language = settings().speechLanguage
        val phrase = announcer.admitEvent(now, due.event, language) ?: return
        val grave = SpeechArbitration.priorityOf(due.event) == SpeechPriority.Danger
        if (utter(phrase, grave) != null) announcer.markSpoken(due.event, now, phrase)
    }

    /** Parole effective : langue et débit des réglages, danger en grave ([SystemTtsEngine.SAFETY_PITCH], même signature que la voix de sécurité), puis moteur (l'appelant tait les pannes). [flush] seul coupe (réservé au danger obstacle) : le GPS ne coupe jamais. */
    private fun utter(phrase: String, danger: Boolean, flush: Boolean = false): String? {
        val locale = SystemTtsEngine.localeOf(settings().speechLanguage)
        val rate = settings().ttsSpeed
        val pitch = if (danger) SystemTtsEngine.SAFETY_PITCH else 1f
        val spoken = engine.speak(phrase, flush, locale, rate, pitch)
        // Marquée dite même si le moteur a refusé (fermé) : la session est
        // finie, le silence qui suit est voulu — cas documenté, pas une perte.
        return phrase.takeIf { spoken }
    }

    /** Douce admise en attente : [event] à redire avant [atMs] + [PENDING_TTL_MS]. */
    private data class PendingEvent(val event: VoiceNavEvent, val atMs: Long)

    companion object {
        /**
         * Rattrapage borné d'une consigne : au-delà, le virage est passé, la
         * phrase est périmée (miroir `ProximityVoice.PENDING_TTL_MS`).
         */
        const val PENDING_TTL_MS = 5_000L
    }
}
