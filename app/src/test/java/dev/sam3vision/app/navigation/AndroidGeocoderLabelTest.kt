package dev.sam3vision.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verrouille le libellé d'une adresse Android ([pickAddressLabel]) : ligne
 * postale 0, sinon localité (+ région), sinon requête d'origine.
 */
class AndroidGeocoderLabelTest {

    @Test
    fun `la ligne postale prime`() {
        assertEquals(
            "12 rue de Rivoli, 75001 Paris",
            pickAddressLabel("12 rue de Rivoli, 75001 Paris", "Paris", "Île-de-France", "rivoli"),
        )
    }

    @Test
    fun `sans ligne la localite et la region composent`() {
        assertEquals(
            "Lyon, Auvergne-Rhône-Alpes",
            pickAddressLabel(null, "Lyon", "Auvergne-Rhône-Alpes", "gare de lyon"),
        )
    }

    @Test
    fun `une ligne blanche vaut absence de ligne`() {
        assertEquals("Paris", pickAddressLabel("   ", "Paris", null, "requête"))
    }

    @Test
    fun `sans rien la requete d'origine est rendue`() {
        assertEquals("mairie", pickAddressLabel(null, null, null, "mairie"))
        assertEquals("mairie", pickAddressLabel("", " ", null, "mairie"))
    }
}
