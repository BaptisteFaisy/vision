package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceStarvation
import dev.sam3vision.core.live.StarvationEvent
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Émission de la voix de la sonde : [phrase] à dire (`null` = rien),
 * [urgent] vrai = coupe la lecture en cours, [seq] distingue les
 * ré-émissions à l'identique (directions redites en continu).
 */
internal data class SpokenEmission(val phrase: String?, val urgent: Boolean, val seq: Long)

/**
 * Collecteur des phrases de la sonde : l'urgence parle en ligne jusqu'au
 * bout (jamais annulée par une douce plus fraîche — un stop ne se tait
 * pour une direction) ; elle coupe le doux en vol avant de parler. Une
 * répétition douce identique ne ré-annule jamais sa propre synthèse en
 * vol (le `seq` fait passer chaque ré-émission pour rejouer les clips,
 * mais la synthèse réseau d'une même phrase, elle, va au bout — §7),
 * seule une douce fraîche écrase la douce en-cours (§6, dernier-gagnant).
 * [say] parle (clip ou synthèse).
 */
internal fun CoroutineScope.observeSpokenEmissions(
    spoken: Flow<SpokenEmission>,
    say: suspend (text: String, urgent: Boolean) -> Unit,
): Job = launch {
    var soft: Job? = null
    var softKey: Pair<String, Boolean>? = null
    spoken.collect { (phrase, urgent, _) ->
        val text = phrase ?: return@collect
        val key = text to urgent
        if (urgent) {
            soft?.cancelAndJoin()
            soft = null
            softKey = null
            say(text, true)
        } else {
            if (key == softKey) return@collect
            soft?.cancel()
            softKey = key
            soft = launch {
                try {
                    say(text, false)
                } finally {
                    if (softKey == key) softKey = null
                }
            }
        }
    }
}

/**
 * Porte de la voix du guidage (mots right/left/straight) : elle cède au
 * son réel — lecture du chat ([playerIdle]) et voix du user ([userHolding],
 * sinon la consigne pollue sa dictée) — mais jamais aux attentes
 * silencieuses (transcription, réflexion LLM, synthèse réseau) : muette
 * jusqu'à 75 s sans rien jouer, la voix de sécurité se taisait sans raison
 * audible. [ambientAllowed] garde l'ordre « GPS désactivé ».
 */
internal fun canGuideSpeech(playerIdle: Boolean, userHolding: Boolean, ambientAllowed: Boolean): Boolean =
    playerIdle && !userHolding && ambientAllowed

/**
 * Guetteur de famine du guidage : le battement 500 ms dit si la boucle
 * est vivante (`pulse.stale`) ; passé 6 s sans consigne fraîche alors
 * qu'on guide ([guiding] : vidéo, guidage, hors Traversée — sa voix
 * rouge/vert est propriétaire), la voix de sécurité prévient (TTS
 * système hors-ligne : la famine, c'est souvent le réseau), puis annonce
 * la reprise. Une seule alerte par famine, un seul retour par reprise.
 */
internal fun LiveProbe.observeStarvation(
    scope: CoroutineScope,
    safety: SafetyVoice,
    guiding: () -> Boolean,
    language: () -> SpeechLanguage,
    clockMs: () -> Long = { System.currentTimeMillis() },
    watcher: GuidanceStarvation = GuidanceStarvation(),
) {
    scope.launch {
        state.map { it.pulse }.collect { pulse ->
            val event = watcher.onTick(clockMs(), fresh = pulse != null && !pulse.stale, guiding = guiding())
            when (event) {
                StarvationEvent.Warn -> safety.speak(GuidanceStarvation.warnPhrase(language()))
                StarvationEvent.Resumed -> safety.speak(GuidanceStarvation.resumedPhrase(language()))
                null -> Unit
            }
        }
    }
}
