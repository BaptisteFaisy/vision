package dev.sam3vision.core.share

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.picto.PictoHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePayloadTest {

    @Test
    fun `le centre de l'image donne un angle nul`() {
        // Boîte centrée sur une image de 1 000 px, champ de 60° : pas d'écart.
        assertEquals(0f, angleDegFor(listOf(400f, 0f, 600f, 100f), 1000, 60f)!!, 0.001f)
    }

    @Test
    fun `le bord gauche donne la moitie du champ en negatif`() {
        // Boîte dont le centre tombe exactement au bord gauche de l'image (0 px) :
        // (−0,5) × champ 60° = −30°.
        assertEquals(-30f, angleDegFor(listOf(-100f, 0f, 100f, 100f), 1000, 60f)!!, 0.001f)
    }

    @Test
    fun `les entrees inexploitables donnent null`() {
        assertNull(angleDegFor(emptyList(), 1000, 60f))
        assertNull(angleDegFor(listOf(0f, 0f, 10f, 10f), 0, 60f))
        assertNull(angleDegFor(listOf(0f, 0f, 10f, 10f), 1000, 0f))
    }

    @Test
    fun `le cap absolu additionne le cap telephone`() {
        assertEquals(40f, absoluteAngleDeg(10f, 30f), 0.001f)
        // Sans boussole, l'angle relatif est conservé tel quel.
        assertEquals(-12f, absoluteAngleDeg(-12f, null), 0.001f)
        // Le résultat reste dans ]-180, 180].
        assertEquals(-170f, absoluteAngleDeg(20f, 170f), 0.001f)
    }

    @Test
    fun `les objets partages portent le cap absolu et sont plafonnes`() {
        val readings = (1..10).map {
            DistanceReading(it, "personne", it.toFloat(), it.toFloat(), listOf(400f, 0f, 600f, 100f))
        }
        val shared = sharedObjectsOf(readings, 1000, 500, 60f, 90f)
        assertEquals(MAX_SHARED_OBJECTS, shared.size)
        assertTrue(shared.all { it.angleDeg == 90f })
        assertTrue(shared.all { it.label == "personne" })
    }

    @Test
    fun `le json du relais porte l'identifiant la position et les objets`() {
        val fix = SharedFix(48.85, 2.35, 12f, 90f, 1_700_000_000_000L, false)
        val json = shareJson("leo", "cle", fix, listOf(SharedObject("personne", 1.4f, 18f)))
        assertEquals("leo", json.getString("shareId"))
        assertEquals("cle", json.getString("writeKey"))
        assertEquals(48.85, json.getJSONObject("fix").getDouble("lat"), 1e-9)
        assertEquals(90.0, json.getJSONObject("fix").getDouble("headingDeg"), 1e-9)
        assertEquals(1, json.getJSONArray("objects").length())
        assertEquals("personne", json.getJSONArray("objects").getJSONObject(0).getString("label"))
    }

    @Test
    fun `le json du relais porte l'image de la camera et le trajet en cours`() {
        val fix = SharedFix(48.85, 2.35, 12f, 90f, 1_700_000_000_000L, false)
        val json = shareJson(
            "leo",
            "cle",
            fix,
            emptyList(),
            photo = SharedPhoto("AAAA", 1_700_000_000_500L),
            trip = SharedTrip("Gare de Lyon", 1_200, 300, 240, 0.75f),
        )
        assertEquals("AAAA", json.getJSONObject("photo").getString("jpeg"))
        assertEquals(1_700_000_000_500L, json.getJSONObject("photo").getLong("atMs"))
        val trip = json.getJSONObject("trip")
        assertEquals("Gare de Lyon", trip.getString("destination"))
        assertEquals(1_200L, trip.getLong("totalM"))
        assertEquals(300L, trip.getLong("remainingM"))
        assertEquals(0.75, trip.getDouble("progress"), 1e-9)
    }

    @Test
    fun `une image illisible ou un trajet hors bornes ne partent pas`() {
        val fix = SharedFix(48.85, 2.35, 12f, null, 1L, false)
        val sansImage = shareJson("leo", "", fix, emptyList(), photo = SharedPhoto("<script>", 1L))
        assertNull(sansImage.optJSONObject("photo"))
        val progresse = shareJson(
            "leo",
            "",
            fix,
            emptyList(),
            trip = SharedTrip("Arrivée", 0, 0, 0, 4f),
        ).getJSONObject("trip")
        assertEquals(1.0, progresse.getDouble("progress"), 1e-9)
    }

    @Test
    fun `le seuil interieur marque les positions imprecises`() {
        assertTrue(INDOOR_ACCURACY_M == 25f)
    }

    @Test
    fun `les reperes partagent nom dessin et instant, plafonnes`() {
        val hits = (1..6).map { PictoHit("id$it", "objet$it", 0f, 0f, 0.1f, 0.1f, 1f) }
        val shared = sharedPictosOf(hits, { byteArrayOf(1, 2, 3) }, 1_700_000_000_000L)!!
        assertEquals(1_700_000_000_000L, shared.atMs)
        assertEquals(MAX_SHARED_PICTOS, shared.items.size)
        assertEquals("objet1", shared.items.first().label)
        assertEquals("AQID", shared.items.first().pngBase64)
    }

    @Test
    fun `sans dessin partageable aucun repere ne part`() {
        assertNull(sharedPictosOf(emptyList(), { null }, 1L))
        val demo = listOf(PictoHit("demo", "mur", 0f, 0f, 1f, 1f, 1f))
        assertNull(sharedPictosOf(demo, { null }, 1L))
    }

    @Test
    fun `le json du relais porte les reperes en dessins`() {
        val fix = SharedFix(48.85, 2.35, 12f, 90f, 1_700_000_000_000L, false)
        val picto = SharedPictos(1L, listOf(SharedPicto("medics", "AQID")))
        val json = shareJson("leo", "cle", fix, emptyList(), pictos = picto)
        val pictos = json.getJSONObject("pictos")
        assertEquals(1L, pictos.getLong("atMs"))
        assertEquals("medics", pictos.getJSONArray("items").getJSONObject(0).getString("label"))
        assertEquals("AQID", pictos.getJSONArray("items").getJSONObject(0).getString("png"))
    }

    @Test
    fun `un dessin trop gros ou un nom vide ne partent pas`() {
        val fix = SharedFix(48.85, 2.35, 12f, null, 1L, false)
        val gros = SharedPicto("medics", "A".repeat(MAX_SHARED_PICTO_PNG + 1))
        val picto = SharedPictos(1L, listOf(SharedPicto("", "AQID"), gros))
        val json = shareJson("leo", "", fix, emptyList(), pictos = picto)
        assertNull(json.optJSONObject("pictos"))
    }
}
