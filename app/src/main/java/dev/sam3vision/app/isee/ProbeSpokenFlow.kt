package dev.sam3vision.app.isee

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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

