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
 * Une seule voix : [SpeechArbitration] décide coupe ou file (danger >
 * consigne > info) — le canal route et le canal obstacle partagent ce
 * moteur, aucune phrase ne parle sur une autre.
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
) {

    /** Virage à [cap] dans [meters] mètres ([street] si connue). */
    fun onTurn(cap: SteeringCap, meters: Float? = null, street: String? = null): String? =
        announce(VoiceNavEvent.Turn(cap, meters, street))

    /** Arrivée à [destination] (vide = arrivée sans nom). */
    fun onArrival(destination: String): String? =
        announce(VoiceNavEvent.Arrival(destination))

    /** Sortie de trace : urgent, coupe la phrase en cours. */
    fun onOffRoute(): String? = announce(VoiceNavEvent.OffRoute)

    /** Nouvel itinéraire après recalcul : urgent. */
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
     * Danger obstacle (« Personne à 2 mètres à midi, va à gauche ») : coupe
     * la phrase en cours, même une consigne. Blanc = silence, comme [onStarted].
     */
    fun onHazard(phrase: String): String? {
        val clean = phrase.trim()
        if (clean.isEmpty()) return null
        return runCatching { utter(clean, flush = true) }.getOrNull()
    }

    /** Coupe la parole en cours (écran quitté, app en arrière-plan). */
    fun stop() = engine.stop()

    /** Libération finale (idempotente) : le canal n'accepte plus rien ensuite. */
    fun shutdown() = engine.close()

    /** Nouvelle session : la mémoire anti-répétition est oubliée. */
    fun reset() = announcer.reset()

    /** Canal commun : politique ([VoiceNavAnnouncer]) puis moteur ([SystemTtsEngine]). */
    private fun announce(event: VoiceNavEvent): String? {
        try {
            val language = settings().speechLanguage
            val phrase = announcer.onEvent(clock(), event, language) ?: return null
            val danger = SpeechArbitration.priorityOf(event) == SpeechPriority.Danger
            return utter(phrase, danger)
        } catch (_: Exception) {
            return null
        }
    }

    /** Parole effective : langue et débit des réglages, puis moteur (l'appelant tait les pannes). */
    private fun utter(phrase: String, flush: Boolean): String? {
        val locale = SystemTtsEngine.localeOf(settings().speechLanguage)
        val rate = settings().ttsSpeed
        val spoken = engine.speak(phrase, flush, locale, rate)
        // Marquée dite même si le moteur a refusé (fermé) : la session est
        // finie, le silence qui suit est voulu — cas documenté, pas une perte.
        return phrase.takeIf { spoken }
    }
}
