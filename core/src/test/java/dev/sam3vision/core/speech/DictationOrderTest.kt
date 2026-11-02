package dev.sam3vision.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationOrderTest {

    @Test
    fun `mode outdoor passe dehors`() {
        assertEquals(DictationOrder.Outdoor, DictationOrders.parse("mode outdoor"))
    }

    @Test
    fun `mode indoor passe dedans`() {
        assertEquals(DictationOrder.Indoor, DictationOrders.parse("mode indoor"))
    }

    @Test
    fun `mode crossing on active la traversee`() {
        assertEquals(DictationOrder.CrossingOn, DictationOrders.parse("mode crossing on"))
    }

    @Test
    fun `mode crossing off coupe la traversee`() {
        assertEquals(DictationOrder.CrossingOff, DictationOrders.parse("mode crossing off"))
    }

    @Test
    fun `mode find my keys cherche les cles`() {
        assertEquals(DictationOrder.Find("keys"), DictationOrders.parse("mode find my keys"))
    }

    @Test
    fun `mode find my accepte n importe quel objet`() {
        assertEquals(DictationOrder.Find("mug"), DictationOrders.parse("mode find my mug"))
        assertEquals(DictationOrder.Find("tv remote"), DictationOrders.parse("mode find my tv remote"))
    }

    @Test
    fun `mode find my ignore le please final`() {
        assertEquals(DictationOrder.Find("keys"), DictationOrders.parse("mode find my keys please"))
        assertEquals(DictationOrder.Find("mug"), DictationOrders.parse("mode find my mug, please!"))
        assertNull(DictationOrders.parse("mode find my please"))
    }

    @Test
    fun `mode find off arrete la recherche`() {
        assertEquals(DictationOrder.FindOff, DictationOrders.parse("mode find off"))
    }

    @Test
    fun `mode gps i want to go to gare guide vers la gare`() {
        assertEquals(
            DictationOrder.GpsGo("gare Montparnasse"),
            DictationOrders.parse("mode GPS I want to go to gare Montparnasse"),
        )
    }

    @Test
    fun `mode gps off coupe le gps`() {
        assertEquals(DictationOrder.GpsOff, DictationOrders.parse("mode GPS off"))
    }

    @Test
    fun `mode glasses on ouvre les lunettes`() {
        assertEquals(DictationOrder.GlassesOn, DictationOrders.parse("mode glasses on"))
    }

    @Test
    fun `mode glasses off ferme les lunettes`() {
        assertEquals(DictationOrder.GlassesOff, DictationOrders.parse("mode glasses off"))
    }

    @Test
    fun `la casse et les espaces ne comptent pas`() {
        assertEquals(DictationOrder.Outdoor, DictationOrders.parse("  Mode   OUTDOOR  "))
        assertEquals(DictationOrder.GpsOff, DictationOrders.parse("MODE gps OFF"))
        assertEquals(DictationOrder.CrossingOn, DictationOrders.parse("mode Crossing On"))
    }

    @Test
    fun `la ponctuation de bord ne compte pas`() {
        assertEquals(DictationOrder.Indoor, DictationOrders.parse("mode indoor."))
        assertEquals(DictationOrder.FindOff, DictationOrders.parse("mode find off?"))
        assertEquals(DictationOrder.Find("keys"), DictationOrders.parse("mode find my keys!"))
    }

    @Test
    fun `la destination garde son texte brut`() {
        assertEquals(
            DictationOrder.GpsGo("12 rue de l'Hôpital"),
            DictationOrders.parse("mode gps i want to go to 12 rue de l'Hôpital"),
        )
    }

    @Test
    fun `une question ordinaire part au llm`() {
        assertNull(DictationOrders.parse("décris-moi la pièce"))
        assertNull(DictationOrders.parse("quelle heure est-il ?"))
        assertNull(DictationOrders.parse(""))
        assertNull(DictationOrders.parse("   "))
    }

    @Test
    fun `les anciens ordres sans mode partent au llm`() {
        assertNull(DictationOrders.parse("traverser"))
        assertNull(DictationOrders.parse("crossing mode on"))
        assertNull(DictationOrders.parse("maison"))
        assertNull(DictationOrders.parse("va à gauche"))
        assertNull(DictationOrders.parse("je veux aller à la gare"))
        assertNull(DictationOrders.parse("désactive le GPS"))
        assertNull(DictationOrders.parse("iSee"))
    }

    @Test
    fun `ou where et find sans mode partent au llm`() {
        assertNull(DictationOrders.parse("où sont mes clés"))
        assertNull(DictationOrders.parse("où est mon téléphone"))
        assertNull(DictationOrders.parse("où sont mes lunettes"))
        assertNull(DictationOrders.parse("où étaient mes clés"))
        assertNull(DictationOrders.parse("où était mon téléphone"))
        assertNull(DictationOrders.parse("où étaient-elles"))
        assertNull(DictationOrders.parse("where are my keys"))
        assertNull(DictationOrders.parse("Where is my phone?"))
        assertNull(DictationOrders.parse("where is the exit"))
        assertNull(DictationOrders.parse("where is my wallet"))
        assertNull(DictationOrders.parse("where is it?"))
        assertNull(DictationOrders.parse("find my keys"))
        assertNull(DictationOrders.parse("Find the keys"))
        assertNull(DictationOrders.parse("find my shoes please"))
        assertNull(DictationOrders.parse("locate my phone"))
        assertNull(DictationOrders.parse("find the elephant"))
        assertNull(DictationOrders.parse("I found my keys"))
    }

    @Test
    fun `mode hors tete part au llm`() {
        assertNull(DictationOrders.parse("s'il te plaît mode outdoor"))
        assertNull(DictationOrders.parse("passe en mode outdoor"))
    }

    @Test
    fun `un mode inconnu part au llm sans rien deviner`() {
        assertNull(DictationOrders.parse("mode"))
        assertNull(DictationOrders.parse("mode self destruct"))
        assertNull(DictationOrders.parse("mode dehors"))
        assertNull(DictationOrders.parse("mode dedans"))
        assertNull(DictationOrders.parse("mode cross"))
        assertNull(DictationOrders.parse("mode find"))
        assertNull(DictationOrders.parse("mode gps"))
        assertNull(DictationOrders.parse("mode glasses"))
    }

    @Test
    fun `un ordre fixe avec mot supplementaire part au llm`() {
        assertNull(DictationOrders.parse("mode outdoor please"))
        assertNull(DictationOrders.parse("mode crossing on now"))
        assertNull(DictationOrders.parse("mode gps off please"))
    }

    @Test
    fun `un ordre a libelle vide part au llm`() {
        assertNull(DictationOrders.parse("mode find my"))
        assertNull(DictationOrders.parse("mode find my   "))
        assertNull(DictationOrders.parse("mode GPS I want to go to"))
        assertNull(DictationOrders.parse("mode GPS I want to go to   ?"))
    }

    @Test
    fun `isOrder dit vrai pour les ordres, faux pour le reste`() {
        assertTrue(DictationOrders.isOrder("mode outdoor"))
        assertTrue(DictationOrders.isOrder("mode indoor"))
        assertTrue(DictationOrders.isOrder("mode crossing on"))
        assertTrue(DictationOrders.isOrder("mode crossing off"))
        assertTrue(DictationOrders.isOrder("mode find my keys"))
        assertTrue(DictationOrders.isOrder("mode find my mug"))
        assertTrue(DictationOrders.isOrder("mode find off"))
        assertTrue(DictationOrders.isOrder("mode GPS I want to go to la gare"))
        assertTrue(DictationOrders.isOrder("mode GPS off"))
        assertTrue(DictationOrders.isOrder("mode glasses on"))
        assertTrue(DictationOrders.isOrder("mode glasses off"))
        assertFalse(DictationOrders.isOrder("où sont mes clés"))
        assertFalse(DictationOrders.isOrder("where are my keys"))
        assertFalse(DictationOrders.isOrder("find my keys"))
        assertFalse(DictationOrders.isOrder("mode outdoor please"))
        assertFalse(DictationOrders.isOrder("décris-moi la pièce"))
    }

    @Test
    fun `ou pronom part au llm`() {
        assertNull(DictationOrders.parse("où étaient-elles"))
        assertNull(DictationOrders.parse("où étaient-elles ?"))
    }
}
