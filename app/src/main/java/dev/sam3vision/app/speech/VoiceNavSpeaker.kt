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
 * silence (répétition, délai) ou si le moteur est fermé.
 *
 * Une seule voix, dernier gagnant : toute consigne admise coupe la phrase
 * en cours et parle aussitôt (`QUEUE_FLUSH`, ordre user du 03/11 — remplace
 * le jamais-de-coupe du 02/11) — le canal route et le canal obstacle
 * partagent ce moteur, aucune phrase en attente, aucune vieille. Le danger
 * garde sa voix grave (pitch bas).
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
 * La sérialisation des phrases est déléguée au moteur unique (`QUEUE_FLUSH`) :
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
     * (jamais de file : un rappel en retard est périmé) ; le user qui parle
     * passe d'abord. Renvoie la phrase dite, `null` si le silence a été
     * gardé. Ne lève jamais.
     */
    fun onRepeat(phrase: String): String? {
        try {
            val clean = phrase.trim()
            if (clean.isEmpty()) return null
            if (userSpeaking()) return null
            if (engine.speaking) return null
            return utter(clean, danger = false)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Danger obstacle (« Gauche. ») : coupe la phrase en cours, même une
     * consigne — comme toute consigne depuis le 03/11. Blanc = silence,
     * comme [onStarted]. Voix grave ([SystemTtsEngine.SAFETY_PITCH]).
     */
    fun onHazard(phrase: String): String? {
        val clean = phrase.trim()
        if (clean.isEmpty()) return null
        return runCatching { utter(clean, danger = true) }.getOrNull()
    }

    /** Vrai si une phrase route est en cours de lecture (arbitre vocal : l'obstacle attend). */
    fun isSpeaking(): Boolean = engine.speaking

    /** Coupe la parole en cours (écran quitté, app en arrière-plan). */
    fun stop() = engine.stop()

    /** Libération finale (idempotente) : le canal n'accepte plus rien ensuite. */
    fun shutdown() = engine.close()

    /** Nouvelle session : la mémoire anti-répétition est oubliée. */
    fun reset() {
        announcer.reset()
    }

    /**
     * Canal commun : politique ([VoiceNavAnnouncer.admitEvent]) puis moteur
     * ([SystemTtsEngine]) ; marquée dite après parole ([markSpoken]) — un
     * refus moteur laisse la phrase ré-admissible. Admise pendant une
     * parole : coupe et parle aussitôt (dernier gagnant, ordre 03/11) —
     * aucune file, aucune vieille. Le danger garde sa voix grave (pitch).
     */
    private fun announce(event: VoiceNavEvent): String? {
        try {
            // Le user parle : le GPS se tait, urgences comprises (miroir du bus).
            if (userSpeaking()) return null
            val now = clock()
            val danger = SpeechArbitration.priorityOf(event) == SpeechPriority.Danger
            val language = settings().speechLanguage
            val phrase = announcer.admitEvent(now, event, language) ?: return null
            val spoken = utter(phrase, danger)
            if (spoken != null) announcer.markSpoken(event, now, phrase)
            return spoken
        } catch (_: Exception) {
            return null
        }
    }

    /** Parole effective : langue et débit des réglages, danger en grave ([SystemTtsEngine.SAFETY_PITCH], même signature que la voix de sécurité), puis moteur en coupant (l'appelant tait les pannes). */
    private fun utter(phrase: String, danger: Boolean): String? {
        val locale = SystemTtsEngine.localeOf(settings().speechLanguage)
        val rate = settings().ttsSpeed
        val pitch = if (danger) SystemTtsEngine.SAFETY_PITCH else 1f
        val spoken = engine.speak(phrase, true, locale, rate, pitch)
        // Marquée dite même si le moteur a refusé (fermé) : la session est
        // finie, le silence qui suit est voulu — cas documenté, pas une perte.
        return phrase.takeIf { spoken }
    }
}
