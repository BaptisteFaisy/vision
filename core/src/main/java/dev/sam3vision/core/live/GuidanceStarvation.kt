package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Famine du guidage : ce que le câblage dit quand la boucle meurt.
 */
enum class StarvationEvent {
    /** Plus de consigne fraîche depuis [GuidanceStarvation.DEFAULT_WARN_AFTER_MS]. */
    Warn,

    /** Consigne fraîche de nouveau, après un [Warn]. */
    Resumed,
}

/**
 * Guetteur de famine du guidage : tant qu'on guide, une boucle sans
 * consigne fraîche finit par prévenir — l'aveugle marche sans voir que
 * la voix s'est tue (panne SAM, réseau, conteneur froid), il doit
 * l'entendre. Un seul avertissement par famine, un seul retour par
 * reprise ; guidage coupé = reset silencieux (le silence est voulu).
 *
 * Pur et testé en JVM (aucun Android, aucune horloge implicite : [nowMs]
 * est fourni). Le câblage (`ProbeStarvation`) y lit [StarvationEvent] à
 * chaque battement et parle par la voix de sécurité (TTS système
 * hors-ligne : la famine, c'est souvent le réseau qui manque).
 */
class GuidanceStarvation(
    private val warnAfterMs: Long = DEFAULT_WARN_AFTER_MS,
) {
    private var unfreshSinceMs: Long? = null
    private var warned = false

    /**
     * Battement à [nowMs] : [fresh] vrai = consigne fraîche vue (boucle
     * vivante), [guiding] vrai = on guide (caméra, guidage, hors Traversée).
     * L'événement à dire, ou `null` (silence).
     */
    fun onTick(nowMs: Long, fresh: Boolean, guiding: Boolean): StarvationEvent? {
        if (!guiding) {
            unfreshSinceMs = null
            warned = false
            return null
        }
        if (fresh) {
            unfreshSinceMs = null
            if (!warned) return null
            warned = false
            return StarvationEvent.Resumed
        }
        val since = unfreshSinceMs ?: nowMs.also { unfreshSinceMs = it }
        if (!warned && nowMs - since >= warnAfterMs) {
            warned = true
            return StarvationEvent.Warn
        }
        return null
    }

    /** Nouvelle séance : famine et avertissement oubliés. */
    fun reset() {
        unfreshSinceMs = null
        warned = false
    }

    companion object {
        /**
         * Famine déclarée après 6 s sans consigne fraîche : un trou de 3 s
         * (réseau, frame lente) reste silencieux, un conteneur froid (30 à
         * 50 s) prévient au lieu de taire la marche.
         */
        const val DEFAULT_WARN_AFTER_MS = 6_000L

        /** « Guidage en pause. Reconnexion en cours. », langue de la voix. */
        fun warnPhrase(language: SpeechLanguage): String = when (language) {
            SpeechLanguage.En -> "Guidance paused. Reconnecting."
            else -> "Guidage en pause. Reconnexion en cours."
        }

        /** « Guidage repris. », langue de la voix. */
        fun resumedPhrase(language: SpeechLanguage): String = when (language) {
            SpeechLanguage.En -> "Guidance resumed."
            else -> "Guidage repris."
        }
    }
}
