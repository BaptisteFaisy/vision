package dev.sam3vision.core.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Politique du voile : le décor suivi (mur, sol dehors) garde pastille et
 * voix mais ne voile pas la caméra — non-régression du 01/11 (voile bleu à
 * mi-écran, rue « coupée en deux »), étendue au sol plein-champ le 02/11
 * (trottoir, passage piéton : même trait horizontal persistant). La bordure
 * voile (ordre user du 02/11 : détectée, visible à la caméra).
 * Tout le reste voile, par défaut aussi.
 */
class VeilPolicyTest {

    @Test
    fun `le mur ne voile pas`() {
        assertFalse(VeilPolicy.showsVeil("wall"))
    }

    @Test
    fun `la casse et les espaces ne changent rien`() {
        assertFalse(VeilPolicy.showsVeil("  Wall "))
    }

    @Test
    fun `le mur ne voile dans aucune langue du contrat`() {
        // Le label renvoyé est le prompt tel que saisi (contrat serveur) :
        // le mur tapé en français voilait encore la rue en deux (01/11).
        assertFalse(VeilPolicy.showsVeil("mur"))
        assertFalse(VeilPolicy.showsVeil("murs"))
        assertFalse(VeilPolicy.showsVeil("muret"))
        assertFalse(VeilPolicy.showsVeil("murets"))
        assertFalse(VeilPolicy.showsVeil("walls"))
    }

    @Test
    fun `la bordure detectee voile, visible a la camera`() {
        assertTrue(VeilPolicy.showsVeil("curb"))
        assertTrue(VeilPolicy.showsVeil("curbs"))
        assertTrue(VeilPolicy.showsVeil("bordure"))
        assertTrue(VeilPolicy.showsVeil("bordures"))
    }

    @Test
    fun `le sol dehors ne voile pas`() {
        assertFalse(VeilPolicy.showsVeil("sidewalk"))
        assertFalse(VeilPolicy.showsVeil("sidewalks"))
        assertFalse(VeilPolicy.showsVeil("trottoir"))
        assertFalse(VeilPolicy.showsVeil("trottoirs"))
        assertFalse(VeilPolicy.showsVeil("crosswalk"))
        assertFalse(VeilPolicy.showsVeil("crosswalks"))
        assertFalse(VeilPolicy.showsVeil("passage piéton"))
        assertFalse(VeilPolicy.showsVeil("passages piétons"))
        assertFalse(VeilPolicy.showsVeil("passage pieton"))
        assertFalse(VeilPolicy.showsVeil("passages pietons"))
    }

    @Test
    fun `les objets ordinaires voilent`() {
        assertTrue(VeilPolicy.showsVeil("person"))
        assertTrue(VeilPolicy.showsVeil("door"))
        assertTrue(VeilPolicy.showsVeil("fence"))
    }

    @Test
    fun `un label inconnu voile par defaut`() {
        assertTrue(VeilPolicy.showsVeil("zebra"))
    }
}
