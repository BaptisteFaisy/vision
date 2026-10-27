package dev.sam3vision.core.usecase

import dev.sam3vision.core.nav.DestinationQuery
import dev.sam3vision.core.nav.NavigationIntent
import dev.sam3vision.core.nav.NavigationIntentParser

/**
 * Demande de guidage : une destination dictée, reconnue et normalisée,
 * prête à partir vers Google Maps.
 */
data class NavigationRequest(val destination: String)

/**
 * Routage vocal : transcript brut → demande de guidage, ou `null` (pas une
 * navigation, texte vide ou blanc).
 *
 * Combine [NavigationIntentParser] (détection déterministe) et
 * [DestinationQuery.normalize] (requête propre), injectés avec leurs défauts :
 * les tests branchent leurs doublures au constructeur sans toucher ce fichier.
 *
 * Fonction pure et synchrone, jamais d'exception : l'ignoré se rend par `null`,
 * pas par `Result.failure` ni `throw` (rien d'asynchrone à échouer ici).
 */
class NavigationRouter(
    private val parse: (String) -> NavigationIntent? = NavigationIntentParser::parse,
    private val normalize: (String) -> Result<String> = DestinationQuery::normalize,
) {
    fun route(transcript: String): NavigationRequest? {
        if (transcript.isBlank()) return null
        val intent = runCatching { parse(transcript) }.getOrNull() ?: return null
        val clean = runCatching { normalize(intent.destination).getOrThrow() }.getOrNull()
        if (clean.isNullOrBlank()) return null
        return NavigationRequest(clean)
    }
}
