package dev.sam3vision.core.live

import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.net.liveBinaryUrl
import dev.sam3vision.core.net.liveFrameJson
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le protocole du direct ne bouge pas : `/live` et `/live/ws` reçoivent **exactement** la
 * même trame qu'avant, et cette trame ne contient **aucun saut de ligne**.
 *
 * Pourquoi cette garde vit dans le périmètre « cadence » : un encodeur base64 qui découpe
 * (l'encodeur MIME de JDK saute tous les 76 caractères) produit une chaîne plus longue,
 * donc un upload plus long, **et** un décodage serveur qui échoue — le tout en silence,
 * frame après frame. C'est un coût de cadence déguisé en bug de protocole. La garde est
 * faite sur le corps de requête réellement assemblé ([liveFrameJson]), côté client : le
 * serveur, lui, décode à la stricte (`Base64.getDecoder()`), sans tolérer les retours
 * ligne.
 */
class LiveWireGuardTest {

    /** JPEG de 6 Ko : la frame 320 px qualité 55 de la page caméra. */
    private val jpeg = ByteArray(6_144) { (it * 37 % 251).toByte() }

    private val jpegB64 = Base64.getEncoder().encodeToString(jpeg)

    @Test
    fun `le corps de la frame ne contient aucun saut de ligne`() {
        val body = liveFrameJson(jpegB64, listOf("personne", "chien"), workEdge = 320, maxObjects = 12)
        assertFalse("un '\\n' dans le base64 casse le décodage serveur", body.contains('\n'))
        assertFalse("un '\\r' non plus", body.contains('\r'))
    }

    @Test
    fun `le base64 de la frame se decode a la stricte et rend le jpeg d origine`() {
        val body = liveFrameJson(jpegB64, listOf("personne"), workEdge = 320, maxObjects = 12)
        val decoded = Base64.getDecoder().decode(org.json.JSONObject(body).getString("image_b64"))
        assertTrue("octets identiques", decoded.contentEquals(jpeg))
    }

    @Test
    fun `l encodage sans repli MIME ajoute exactement quatre caracteres par trois octets`() {
        // Aucun bourrage de ligne : la longueur attendue est celle d'un NO_WRAP pur.
        val expected = (jpeg.size + 2) / 3 * 4
        assertEquals("le base64 ne doit rien aux retours ligne", expected, jpegB64.length)
    }

    /**
     * `/live/ws` = le corps de `POST /live` **plus** l'`id` de corrélation : mêmes champs,
     * mêmes défauts, `mask_encoding` compris. Vérifié ici pour que la cadence ne puisse
     * jamais changer une seule ligne du contrat.
     */
    @Test
    fun `la trame websocket porte les memes champs que le POST plus son id`() {
        val post = org.json.JSONObject(liveFrameJson(jpegB64, PROMPTS, workEdge = 640, maxObjects = 12))
        val ws = org.json.JSONObject(liveFrameJson(jpegB64, PROMPTS, workEdge = 640, maxObjects = 12, id = 7))

        assertEquals(7, ws.getInt("id"))
        assertFalse("le POST n'a pas d'id", post.has("id"))
        for (key in listOf("image_b64", "prompts", "work_edge", "max_objects", "mask_encoding")) {
            assertTrue("`$key` absent du POST", post.has(key))
            assertTrue("`$key` absent de la trame WS", ws.has(key))
            // `JSONArray` n'implémente pas `equals` : on compare la sérialisation.
            assertEquals("`$key` diverge entre POST et WS", post.get(key).toString(), ws.get(key).toString())
        }
        assertEquals("bits", ws.getString("mask_encoding"))
        assertEquals(640, ws.getInt("work_edge"))
        assertEquals(12, ws.getInt("max_objects"))
    }

    @Test
    fun `with_depth demande ajoute le champ, muet ne change rien`() {
        val asked = org.json.JSONObject(
            liveFrameJson(jpegB64, PROMPTS, workEdge = 640, maxObjects = 12, id = 7, withDepth = true),
        )
        assertTrue("with_depth demandé", asked.getBoolean("with_depth"))
        val mutePost = org.json.JSONObject(liveFrameJson(jpegB64, PROMPTS, workEdge = 640, maxObjects = 12))
        val muteWs = org.json.JSONObject(liveFrameJson(jpegB64, PROMPTS, workEdge = 640, maxObjects = 12, id = 7))
        assertFalse("POST muet : pas de with_depth", mutePost.has("with_depth"))
        assertFalse("WS muette : pas de with_depth", muteWs.has("with_depth"))
    }

    @Test
    fun `par defaut les masques sont demandes en bits avec les knobs`() {
        val body = org.json.JSONObject(
            liveFrameJson(jpegB64, PROMPTS, workEdge = 640, maxObjects = 12, maskFormat = "rle", maskEdge = 256),
        )
        assertEquals("bits", body.getString("mask_encoding"))
        assertEquals("rle", body.getString("mask_format"))
        assertEquals(256, body.getInt("mask_edge"))
    }

    @Test
    fun `sans masques le corps demande none et tait les knobs`() {
        val body = org.json.JSONObject(
            liveFrameJson(jpegB64, PROMPTS, workEdge = 640, maxObjects = 12, id = 7, maskFormat = "rle", maskEdge = 256, masks = false),
        )
        assertEquals("none", body.getString("mask_encoding"))
        assertFalse("pas de mask_format sans masque", body.has("mask_format"))
        assertFalse("pas de mask_edge sans masque", body.has("mask_edge"))
        assertEquals(7, body.getInt("id"))
        assertEquals(640, body.getInt("work_edge"))
    }

    @Test
    fun `l url binaire suit le meme encodage que le JSON`() {
        val withMasks = liveBinaryUrl("https://exemple.invalid", PROMPTS, 640, 12, "rle", 256)
        assertTrue(withMasks, withMasks.contains("mask_encoding=bits"))
        assertTrue(withMasks, withMasks.contains("mask_format=rle"))
        val boxesOnly = liveBinaryUrl("https://exemple.invalid", PROMPTS, 640, 12, "rle", 256, masks = false)
        assertTrue(boxesOnly, boxesOnly.contains("mask_encoding=none"))
        assertFalse("pas de mask_format sans masque", boxesOnly.contains("mask_format"))
        assertFalse("pas de mask_edge sans masque", boxesOnly.contains("mask_edge"))
    }

    /**
     * Plafond client dans la trame réelle : c'est bien [ObjectLimits.DEFAULT_ON_SCREEN] (16,
     * patch P0 foule 01/11) qui part en `max_objects`, sans qu'aucune couche réseau ne le
     * réécrive — le serveur, lui, ne clampe qu'à 1..32 et garde donc les 16.
     */
    @Test
    fun `le plafond par defaut part a seize objets dans le JSON`() {
        val body = org.json.JSONObject(
            liveFrameJson(jpegB64, PROMPTS, workEdge = 640, maxObjects = ObjectLimits.DEFAULT_ON_SCREEN),
        )
        assertEquals(16, ObjectLimits.DEFAULT_ON_SCREEN)
        assertEquals(16, body.getInt("max_objects"))
    }

    private companion object {
        val PROMPTS = listOf("personne", "chien")
    }
}