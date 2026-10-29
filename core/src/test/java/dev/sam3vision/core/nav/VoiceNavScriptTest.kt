package dev.sam3vision.core.nav

import dev.sam3vision.core.crossing.CrossingVoiceCommand
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.SteeringCommand
import dev.sam3vision.core.usecase.NavigationRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrou JVM du script E2E navigation vocale (`scripts/verify-voice-nav.sh`,
 * pas-à-pas `docs/RECETTE-NAVIGATION-VOCALE.md`).
 *
 * Les phrases dictées par l'intégrateur sur le téléphone sont recopiées ici
 * à l'identique (garde anti-dérive : `scripts/test_scripts.sh` § 7 exige
 * chaque phrase dans le script et la fiche) et routées par la vraie chaîne
 * de dispatch (`IseeViewModel.handleDictated` : cap → navigation → traversée
 * → LLM). Si un motif change, ce test casse avant la recette, pas pendant.
 *
 * Ce que le téléphone vérifie en plus (non testable en JVM) : l'overlay et la
 * voix « Navigation vers X » ([NavigationPhrases], module `:app`), l'ouverture
 * de Google Maps, son annonce d'arrivée et son recalcul hors-trace, et le
 * silence d'iSee pendant ces deux phases (iSee ne suit rien, par design).
 */
class VoiceNavScriptTest {

    private val router = NavigationRouter()

    @Test
    fun `commande — « je veux aller à la gare de Lyon » route vers la gare de Lyon`() {
        val spoken = "je veux aller à la gare de Lyon"

        assertNull("ni cap : $spoken", SteeringCommand.parse(spoken))
        assertNull("ni traversée : $spoken", CrossingVoiceCommand.parse(spoken))
        assertEquals("la gare de Lyon", router.route(spoken)?.destination)
    }

    @Test
    fun `commande — « Emmène-moi à la tour Eiffel » route vers la tour Eiffel`() {
        val spoken = "Emmène-moi à la tour Eiffel"

        assertNull("ni cap : $spoken", SteeringCommand.parse(spoken))
        assertNull("ni traversée : $spoken", CrossingVoiceCommand.parse(spoken))
        assertEquals("la tour Eiffel", router.route(spoken)?.destination)
    }

    @Test
    fun `guidage — la destination E2E part en intent navigation pieton`() {
        val uri = MapsUri.navigation("la gare de Lyon", MapsUri.Mode.Walking).getOrThrow()

        assertEquals("google.navigation:q=la%20gare%20de%20Lyon&mode=w", uri)
    }

    @Test
    fun `question — « Où est … » n'est pas un ordre, elle part au LLM`() {
        val spoken = "Où est la pharmacie la plus proche ?"

        assertNull("ni cap : $spoken", SteeringCommand.parse(spoken))
        assertNull("ni navigation : $spoken", router.route(spoken))
        assertNull("ni traversée : $spoken", CrossingVoiceCommand.parse(spoken))
    }

    @Test
    fun `destination vide — « Emmène-moi à » ne route pas, chemin question`() {
        val spoken = "Emmène-moi à"

        assertNull("ni cap : $spoken", SteeringCommand.parse(spoken))
        assertNull("ni navigation : $spoken", router.route(spoken))
    }

    @Test
    fun `piège cap — « va à gauche » est un cap avant d'être une intention Maps`() {
        val spoken = "va à gauche"

        assertEquals(SteeringCap.Left, SteeringCommand.parse(spoken))
        assertNotNull(
            "le piège existe : VA matche aussi, d'où la priorité cap " +
                "(les phrases E2E ci-dessus évitent les mots de direction)",
            NavigationIntentParser.parse(spoken),
        )
    }
}
