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
 * [onStarted]/[onStopped] pour la session.
 * Chaque hook renvoie la phrase dite, ou `null` si la politique a gardé le
 * silence (répétition, délai) ou si le moteur est fermé.
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
            val rate = settings().ttsSpeed
            val locale = SystemTtsEngine.localeOf(language)
            val urgent = VoiceNavPolicy.isUrgent(event)
            val spoken = engine.speak(phrase, urgent, locale, rate)
            // Marquée dite même si le moteur a refusé (fermé) : la session est
            // finie, le silence qui suit est voulu — cas documenté, pas une perte.
            return phrase.takeIf { spoken }
        } catch (_: Exception) {
            return null
        }
    }
}
