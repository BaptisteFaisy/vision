package dev.sam3vision.app.speech

/**
 * Priorité d'une phrase sur la voix unique : le danger coupe tout, la
 * consigne de route se met en file, l'info attend son tour.
 */
enum class SpeechPriority {
    /** Retour au calme, confirmation : ne coupe jamais rien. */
    Info,

    /** Consigne de route (virage, départ, phrase moteur) : en file. */
    Instruction,

    /** Danger (obstacle, sortie de trace) : coupe la phrase en cours. */
    Danger,
}

/**
 * Arbitrage de la voix unique : une seule parole à la fois, le danger
 * d'abord. Consulté par [VoiceNavSpeaker] (canal route, TTS système) pour
 * décider coupe ou file ; le canal obstacle y entre par `onHazard` —
 * même moteur, même file, aucune voix qui parle sur l'autre.
 *
 * Pur et testé en JVM : la correspondance événement → priorité
 * ([priorityOf]) et la règle de coupe ([interrupts]).
 */
object SpeechArbitration {

    /**
     * Priorité de [event] : sorties de trace, recalculs, arrêts et arrivées
     * coupent (danger) ; retour sur trace informatif ; le reste en file.
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
     * cours (la plus fraîche gagne, miroir `SafetyVoice`) : le danger coupe
     * la consigne, jamais
     * l'inverse, et deux phrases de même niveau s'enchaînent.
     */
    fun interrupts(current: SpeechPriority, incoming: SpeechPriority): Boolean =
        incoming == SpeechPriority.Danger || incoming > current
}
