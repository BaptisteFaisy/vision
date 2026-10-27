package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bout-en-bout fake : transcript FR → intention → URIs Maps → launcher simulé.
 *
 * Chaîne testée (JVM pure, sans Android ni réseau) :
 * [NavigationIntentParser] (transcript → intention) →
 * [DestinationQuery.normalize] (destination brute → requête propre) →
 * [MapsUri] (requête → URIs candidates) → [FakeUriLauncher] (ordre simulé).
 *
 * L'ordre des tentatives miroir le launcher réel : intent `google.navigation`
 * (app Maps) d'abord, URL universelle `dir/?api=1` ensuite. Le flux ne lève
 * jamais : l'ignoré (pas d'intention, vide) rend un succès [FlowOutcome.Ignored],
 * jamais une exception ni un échec — il n'y a rien à lancer, pas d'erreur.
 */
class NavigationFlowTest {

    private val transcriptOk = "Emmène-moi à la gare"

    private val navigationUri = "google.navigation:q=la%20gare&mode=w"

    private val directionsUri =
        "https://www.google.com/maps/dir/?api=1&destination=la%20gare&travelmode=walking"

    @Test
    fun `ok — transcript FR lance l'URI navigation attendue en premier`() {
        val launcher = FakeUriLauncher()

        val outcome = runNavigationFlow(transcriptOk, launcher)

        assertTrue(outcome.isSuccess)
        assertEquals(FlowOutcome.Launched(navigationUri), outcome.getOrNull())
        assertEquals(listOf(navigationUri), launcher.launches())
    }

    @Test
    fun `ok — l'URI de repli universelle est celle attendue`() {
        val launcher = FakeUriLauncher()
        launcher.failing = setOf(navigationUri)

        val outcome = runNavigationFlow(transcriptOk, launcher)

        assertTrue(outcome.isSuccess)
        assertEquals(FlowOutcome.Launched(directionsUri), outcome.getOrNull())
        assertEquals(listOf(navigationUri, directionsUri), launcher.launches())
    }

    @Test
    fun `ignore — sans intention navigation rien n'est lance`() {
        val launcher = FakeUriLauncher()

        val outcome = runNavigationFlow("quelle heure est-il ?", launcher)

        assertTrue(outcome.isSuccess)
        assertEquals(FlowOutcome.Ignored, outcome.getOrNull())
        assertTrue(launcher.launches().isEmpty())
    }

    @Test
    fun `vide — transcript vide ou blanc ignore sans exception`() {
        for (blank in listOf("", "   ", "\t\n ")) {
            val launcher = FakeUriLauncher()

            val outcome = runNavigationFlow(blank, launcher)

            assertTrue("« $blank » doit s'ignorer", outcome.isSuccess)
            assertEquals(FlowOutcome.Ignored, outcome.getOrNull())
            assertTrue(launcher.launches().isEmpty())
        }
    }
}

/** Issue du flux : URI partie, ou transcript ignoré (pas d'intention). */
private sealed interface FlowOutcome {
    data class Launched(val uri: String) : FlowOutcome
    data object Ignored : FlowOutcome
}

/**
 * Fake artisanal du launcher, sans Android ni mock : enregistre les URIs
 * tentées dans l'ordre et répond en [Result]. Une URI blanche est refusée
 * sans tentative enregistrée ; une URI programmée en échec échoue tout en
 * restant visible dans l'historique (c'est ce qui prouve l'ordre des replis).
 */
private class FakeUriLauncher {

    private val attempted = mutableListOf<String>()

    /** URIs qui échouent quand on les tente (programmation du test). */
    var failing: Set<String> = emptySet()

    /** URIs tentées, dans l'ordre des appels à [launch]. */
    fun launches(): List<String> = attempted.toList()

    fun launch(uri: String): Result<Unit> {
        if (uri.isBlank()) {
            return Result.failure(IllegalArgumentException("URI vide"))
        }
        attempted += uri
        if (uri in failing) {
            return Result.failure(IllegalStateException("lancement refusé : $uri"))
        }
        return Result.success(Unit)
    }
}

/**
 * Flux navigation complet : parse → normalise → construit les URIs candidates
 * (navigation puis repli universel) → tente dans l'ordre jusqu'au premier succès.
 * Ne lève jamais : intention absente → [FlowOutcome.Ignored], collaborateur qui
 * jetterait → `Result.failure`.
 */
private fun runNavigationFlow(transcript: String, launcher: FakeUriLauncher): Result<FlowOutcome> {
    val intent = NavigationIntentParser.parse(transcript) ?: return Result.success(FlowOutcome.Ignored)
    val query = DestinationQuery.normalize(intent.destination).getOrElse { return Result.failure(it) }
    val uris = listOf(
        MapsUri.navigation(query).getOrElse { return Result.failure(it) },
        MapsUri.directions(query).getOrElse { return Result.failure(it) },
    )
    var lastError: Throwable? = null
    for (uri in uris) {
        val outcome = runCatching { launcher.launch(uri) }.getOrElse { Result.failure(it) }
        if (outcome.isSuccess) return Result.success(FlowOutcome.Launched(uri))
        lastError = outcome.exceptionOrNull()
    }
    return Result.failure(lastError ?: IllegalStateException("aucune URI candidate"))
}
