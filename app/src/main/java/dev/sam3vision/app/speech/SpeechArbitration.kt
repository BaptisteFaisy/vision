package dev.sam3vision.app.speech

/**
 * Priorité d'une phrase sur la voix unique : le danger parle grave et
 * prend la tête de file, la consigne de route se met en file derrière,
 * l'info attend son tour — rien ne coupe (ordre user du 02/11).
 */
enum class SpeechPriority {
    /** Retour au calme, confirmation : ne coupe jamais rien. */
    Info,

    /** Consigne de route (virage, départ, phrase moteur) : en file. */
    Instruction,

    /** Danger (sortie de trace, recalcul, arrêt, arrivée) : voix grave, tête de file, sans couper. */
    Danger,
}

/**
 * Arbitrage de la voix unique : une seule parole à la fois, le danger
 * grave devant. Consulté par [VoiceNavSpeaker] (canal route, TTS système)
 * pour la voix grave ; tout part en file, rien ne coupe (ordre user du
 * 02/11 — la règle de coupe `interrupts` est supprimée avec lui).
 * Le canal obstacle y entre par `onHazard` — même moteur, même file,
 * aucune voix qui parle sur une autre.
 *
 * Pur et testé en JVM : la correspondance événement → priorité ([priorityOf]).
 */
object SpeechArbitration {

    /**
     * Priorité de [event] : sorties de trace, recalculs, arrêts et arrivées
     * en voix grave (danger) ; retour sur trace informatif ; le reste en
     * file — aucun ne coupe (ordre user du 02/11).
     */
    fun priorityOf(event: VoiceNavEvent): SpeechPriority = when (event) {
        VoiceNavEvent.OffRoute,
        VoiceNavEvent.Rerouted,
        VoiceNavEvent.Stopped,
        is VoiceNavEvent.Arrival,
        -> SpeechPriority.Danger
        VoiceNavEvent.BackOnRoute -> SpeechPriority.Info
        else -> SpeechPriority.Instruction
    }
    /**
     * [incoming] coupe-t-elle [current] ? Oui si et seulement si elle est
     * prioritaire — sauf le danger, qui coupe tout, y compris un danger en
     * cours (la plus fraîche gagne) : le danger coupe la consigne, jamais
     * l'inverse, et deux phrases de même niveau s'enchaînent.
     */
    fun interrupts(current: SpeechPriority, incoming: SpeechPriority): Boolean =
        incoming == SpeechPriority.Danger || incoming > current
}
