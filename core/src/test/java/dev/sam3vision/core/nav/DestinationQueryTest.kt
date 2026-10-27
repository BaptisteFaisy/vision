package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille la normalisation d'une destination saisie ou dictée
 * ([DestinationQuery.normalize]).
 *
 * Contrat : le texte est rogné, les blancs internes repliés en une seule espace,
 * les accents et la casse conservés ; une entrée vide ou blanche échoue en [Result]
 * (jamais d'exception). Chaque test échoue si la normalisation cesse de tenir ce contrat.
 */
class DestinationQueryTest {

    private fun queryOf(raw: String): String? = DestinationQuery.normalize(raw).getOrNull()

    private fun assertQuery(raw: String, expected: String) {
        assertEquals("« $raw » normalisé", expected, queryOf(raw))
    }

    private fun assertFails(raw: String) {
        assertTrue("« $raw » doit échouer", DestinationQuery.normalize(raw).isFailure)
    }

    @Test
    fun `une chaine vide echoue sans exception`() {
        assertFails("")
    }

    @Test
    fun `une chaine blanche echoue sans exception`() {
        assertFails("   ")
        assertFails("\t\n ")
    }

    @Test
    fun `les espaces autour sont rognes`() {
        assertQuery("  gare de Lyon  ", "gare de Lyon")
    }

    @Test
    fun `les espaces multiples internes sont replies`() {
        assertQuery("gare   de    Lyon", "gare de Lyon")
    }

    @Test
    fun `tabulations et retours a la ligne sont replies en espaces`() {
        assertQuery("gare\tde\nLyon", "gare de Lyon")
    }

    @Test
    fun `les accents sont conserves`() {
        assertQuery("Hôtel de Ville", "Hôtel de Ville")
    }

    @Test
    fun `cedille tremas et ligatures sont conserves`() {
        assertQuery("François, cœur, façade, Noël", "François, cœur, façade, Noël")
    }

    @Test
    fun `la casse est conservee`() {
        assertQuery("PARIS", "PARIS")
        assertQuery("gare de l'Est", "gare de l'Est")
    }

    @Test
    fun `un texte simple passe tel quel`() {
        assertQuery("Lyon", "Lyon")
    }

    @Test
    fun `chiffres et ponctuation utile passent tels quels`() {
        assertQuery("12 rue de la Paix", "12 rue de la Paix")
    }
}
