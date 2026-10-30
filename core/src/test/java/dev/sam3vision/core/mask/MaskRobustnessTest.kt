package dev.sam3vision.core.mask

import dev.sam3vision.core.model.MASK_ENCODING_BITS
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robustesse du décodeur « M1 » (`MaskCore.kt::decodeBitsMask`), chemin chaud du direct :
 * chaque entrée hostile doit rendre **`null`** (l'appelant dessine alors la boîte) ou un
 * masque valide — jamais une exception qui remonterait à l'UI, jamais une allocation
 * proportionnée à un en-tête mensonger, jamais un masque faux accepté en silence.
 *
 * Les vecteurs marqués « spec » viennent du backend réel :
 * `imaging.py::encode_mask_bits_b64` et `imaging.py::decode_mask_bits_b64`, exécutés
 * localement (script hors dépôt, aucun réseau). Le verdict Kotlin doit être le verdict
 * Python, cas par cas : c'est le contrat entre les deux langages.
 */
class MaskRobustnessTest {

    // ---------------------------------------------------------------- base64 & en-tête

    @Test
    fun `un base64 invalide ou tronque rend null`() {
        assertNull(decodeBitsMask(""))
        assertNull(decodeBitsMask("    "))
        assertNull(decodeBitsMask("pas du base64 !!!"))
        assertNull(decodeBitsMask("!!!!"))
        assertNull(decodeBitsMask(BITS_5X3.replace('=', '%')))
        // troncature franche : plus assez de caractères pour un corps zlib exploitable
        assertNull(decodeBitsMask(BITS_5X3.take(16)))
        assertNull(decodeBitsMask(BITS_5X3.take(12)))
        assertNull(decodeBitsMask(BITS_5X3.dropLast(3)))
    }

    /**
     * Tolérance voulue, à la limite de la spec : Java accepte un flux zlib privé de la fin de
     * son sceau adler32 quand tout le deflate est là, alors que `zlib.decompress` (Python)
     * rend `Error -5`. Les pixels décodés sont **complets et justes** : on préfère un overlay
     * correct à un masque absent quand la réponse a été coupée dans son dernier octet.
     */
    @Test
    fun `une queue zlib amputee n empeche pas de lire les pixels`() {
        val complet = decodeBitsMask(BITS_5X3)!!
        val ampute = decodeBitsMask(BITS_5X3.dropLast(1)) // 1 octet de sceau en moins
        assertNotNull(ampute)
        assertArrayEquals(complet.pixels, ampute!!.pixels)
    }

    @Test
    fun `le meme masque se lit avec un prefixe data ou des retours ligne`() {
        val propre = decodeBitsMask(BITS_15X4)!!
        val avecPrefixe = decodeBitsMask("data:application/octet-stream;base64,$BITS_15X4")
        val avecSauts = decodeBitsMask(BITS_15X4.chunked(4).joinToString("\r\n"))
        assertNotNull(avecPrefixe)
        assertNotNull(avecSauts)
        assertArrayEquals(propre.pixels, avecPrefixe!!.pixels)
        assertArrayEquals(propre.pixels, avecSauts!!.pixels)
    }

    @Test
    fun `un en-tete trop court rend null`() {
        assertNull(decodeBitsMask("QUJDRA==")) // 4 octets
        assertNull(decodeBitsMask(Base64.getEncoder().encodeToString(ByteArray(5) { 'M'.code.toByte() })))
        // en-tête 8x2 valide, mais strictement rien après : pas de corps zlib
        assertNull(decodeBitsMask(Base64.getEncoder().encodeToString(M1.header(8, 2))))
        assertNull(decodeBitsMask(Base64.getEncoder().encodeToString(M1.header(8, 2) + 0x78.toByte())))
    }

    @Test
    fun `un magic inconnu rend null`() {
        assertNull(decodeBitsMask(MAGIC_INCONNU)) // vecteur spec : « X1 », refusé aussi par le backend
        assertNull(decodeBitsMask(M1.b64(M1.header(8, 2, magic = byteArrayOf('M'.code.toByte(), '2'.code.toByte())), ByteArray(2))))
        assertNull(decodeBitsMask(M1.b64(M1.header(8, 2, magic = byteArrayOf('m'.code.toByte(), '1'.code.toByte())), ByteArray(2))))
        assertNull(decodeBitsMask(M1.b64(M1.header(8, 2, magic = byteArrayOf(0, 0)), ByteArray(2))))
    }

    // ---------------------------------------------------------------- taille du corps

    /**
     * **Parité backend — bug corrigé.** `decode_mask_bits_b64` exige
     * `bits.size == row_bytes * height` : un corps plus long que l'en-tête est une erreur.
     * L'ancien Kotlin s'arrêtait à la longueur annoncée et acceptait donc un corps trop long
     * en le tronquant en silence — un masque faux, pas une erreur, et pire qu'un `null`.
     */
    @Test
    fun `un corps plus long que la taille annoncee est refuse`() {
        assertNull(decodeBitsMask(CORPS_TROP_LONG_8X2)) // vecteur spec : en-tête 8x2, corps de 3 lignes
        assertNull(decodeBitsMask(CORPS_TROP_LONG_8X2_GROS)) // idem, corps de 4 lignes
        assertNull(decodeBitsMask(M1.b64(M1.header(13, 3), ByteArray(8)))) // 13x3 réclame 6 octets
        assertNull(decodeBitsMask(M1.b64(M1.header(640, 360), ByteArray(28801)))) // un octet de trop
    }

    @Test
    fun `un corps plus court que la taille annoncee est refuse`() {
        assertNull(decodeBitsMask(CORPS_TROP_COURT_8X2)) // vecteur spec
        assertNull(decodeBitsMask(M1.b64(M1.header(640, 360), ByteArray(100)))) // 28 800 attendus
        assertNull(decodeBitsMask(M1.b64(M1.header(8, 2), ByteArray(1)))) // une ligne au lieu de deux
    }

    @Test
    fun `une dimension nulle rend null`() {
        assertNull(decodeBitsMask(M1.b64(M1.header(0, 2), ByteArray(0))))
        assertNull(decodeBitsMask(M1.b64(M1.header(8, 0), ByteArray(0))))
        assertNull(decodeBitsMask(M1.b64(M1.header(0, 0), ByteArray(0))))
    }

    // ---------------------------------------------------------------- garde-fous d'allocation

    /**
     * Un en-tête `65535×65535` réclame un corps de 512 Mo (et 4 Go de niveaux 8 bits) :
     * il est écarté **avant** tout décompressage. La sonde vérifie que refuser ne coûte pas
     * plus que la lecture du base64 lui-même.
     */
    @Test
    fun `un en-tete enorme est refuse sans rien allouer`() {
        assertNull(decodeBitsMask(ENORME_65535))
        assertNull(decodeBitsMask(M1.b64(M1.header(65535, 65535), ByteArray(0))))
        repeat(4) { decodeBitsMask(ENORME_65535) } // hors coût de compilation JIT
        val octets = JvmAllocations.netAllocated { decodeBitsMask(ENORME_65535) }
        println("en-tête 65535×65535 refusé : $octets octet(s) alloués")
        // Plafond seul, sans plancher (30/10) : le compteur thread est monotone, donc un net
        // négatif est un refill TLAB tombé dans la mesure vide (−9568 observés en suite complète
        // avec d'autres tests avant), pas une allocation — seul un dépassement du plafond
        // prouverait un vrai coût (miroir du garde 8 Mo ci-dessous, déjà sans plancher).
        assertTrue("allocation = $octets", !JvmAllocations.available || octets <= 4096)
    }

    /**
     * Deuxième garde : une taille qui **entre** dans le plafond (8 Mo exactement) ne doit
     * pas non plus allouer 8 Mo sur parole — le tampon de départ est plafonné, et un corps
     * mensonger est rejeté sur sa longueur.
     */
    @Test
    fun `un en-tete qui reclame 8 Mo n alloue que son tampon de depart`() {
        val payload = M1.b64(M1.header(65535, 1024), ByteArray(16)) // 8192 × 1024 = 8 MiB
        assertNull(decodeBitsMask(payload))
        repeat(4) { decodeBitsMask(payload) }
        val octets = JvmAllocations.netAllocated { decodeBitsMask(payload) }
        println("en-tête 65535×1024 (corps de 16 octets) : $octets octet(s) alloués")
        assertTrue("$octets octets pour une taille annoncée de 8 MiB", !JvmAllocations.available || octets < 128 * 1024)
    }

    @Test
    fun `un flux zlib invalide rend null`() {
        // en-tête correct, corps qui n'est pas un flux zlib
        assertNull(decodeBitsMask(Base64.getEncoder().encodeToString(M1.header(8, 2) + "coucou".toByteArray())))
        assertNull(decodeBitsMask(Base64.getEncoder().encodeToString(M1.header(8, 2) + ByteArray(16) { 0x7F })))
        // un deflate « nu » (sans en-tête zlib) : le backend n'en écrit jamais, Python le refuse
        assertNull(decodeBitsMask(M1.b64Raw(M1.header(8, 2), M1.zlib(ByteArray(2), nowrap = true))))
    }

    /**
     * Parité spec : `zlib.decompress` **ignore** les octets qui suivent le flux (il n'y a
     * pas d'erreur sur `unused_data`). Le Kotlin fait de même — un payload collé derrière ne
     * doit pas faire jeter un masque pourtant lisible.
     */
    @Test
    fun `les octets apres le flux zlib sont ignores comme côté serveur`() {
        val corps = M1.packed(8, 2) { x, _ -> x % 2 == 0 }
        val propre = decodeBitsMask(M1.b64(M1.header(8, 2), corps))!!
        val gonfle = M1.b64Raw(M1.header(8, 2), M1.zlib(corps) + byteArrayOf(0, 7, 42, 99))
        val le = decodeBitsMask(gonfle)
        assertNotNull(le)
        assertArrayEquals(propre.pixels, le!!.pixels)
    }

    // ---------------------------------------------------------------- bits & dimensions

    /** 13 de large = 2 octets par ligne, donc 3 bits de bourrage : la spec les ignore. */
    @Test
    fun `les bits de bourrage de la ligne sont ignores`() {
        val mask = decodeBitsMask(PAD_13X3)!! // vecteur spec, lignes remplies à 1 au-delà de 13
        assertEquals(13, mask.width)
        assertEquals(3, mask.height)
        for (y in 0 until mask.height) assertEquals("ligne $y", "1010101011111", M1.rowText(mask, y))
    }

    @Test
    fun `quatre vecteurs reels du backend se decodent pixel par pixel`() {
        val attendu = mapOf(
            BITS_5X3 to listOf("10010", "01001", "00100"),
            BITS_1X5 to listOf("1", "0", "1", "0", "1"),
            BITS_15X4 to listOf("111111111111111", "110011001100110", "101010101010101", "100110011001100"),
        )
        for ((payload, lignes) in attendu) {
            val mask = decodeBitsMask(payload)
            assertNotNull(payload, mask)
            assertEquals("hauteur", lignes.size, mask!!.height)
            for (y in lignes.indices) assertEquals("ligne $y", lignes[y], M1.rowText(mask, y))
        }
    }

    @Test
    fun `un masque 1x1 se lit dans les deux etats`() {
        val objet = decodeBitsMask(M1.b64(1, 1, { _, _ -> true }))!!
        val fond = decodeBitsMask(M1.b64(1, 1, { _, _ -> false }))!!
        assertEquals(1 to 1, objet.width to objet.height)
        assertEquals(1, objet.size)
        assertEquals(255, objet.levelAt(0, 0))
        assertEquals(0, fond.levelAt(0, 0))
    }

    @Test
    fun `un masque vide et un masque plein de 320x180 se lisent`() {
        val vide = decodeBitsMask(M1.b64(320, 180, { _, _ -> false }))!!
        val plein = decodeBitsMask(M1.b64(320, 180, { _, _ -> true }))!!
        assertEquals(57600, vide.size)
        assertTrue((0 until vide.size).all { vide.pixels[it].toInt() == 0 })
        assertTrue((0 until plein.size).all { plein.pixels[it].toInt() and 0xFF == 255 })
    }

    /** Le cas réel du direct : 640×360, soit 28 800 octets de corps, vérifié pixel à pixel. */
    @Test
    fun `un masque 640x360 fait l aller-retour complet`() {
        val motif = { x: Int, y: Int -> (x * 3 + y * 7) % 11 < 4 }
        val mask = decodeBitsMask(M1.b64(640, 360, motif))
        assertNotNull(mask)
        var faux = 0
        for (y in 0 until 360) {
            for (x in 0 until 640) if (mask!!.isObject(x, y) != motif(x, y)) faux++
        }
        assertEquals(0, faux)
    }

    // ---------------------------------------------------------------- leçons de l en-tête

    /**
     * Repli de compat : le corps décompressé **départage** les deux leçons. Big-endian n'est
     * retenu que si c'est lui qui réclame exactement la longueur du corps — une lecture
     * little-endian de 61×37 en demanderait 18 Mo, donc impossible ici.
     */
    @Test
    fun `l en-tete big-endian est departage par la taille du corps`() {
        val be = decodeBitsMask(M1.b64(61, 37, { x, y -> (x * 7 + y * 3) % 5 < 2 }, littleEndian = false))
        assertNotNull(be)
        assertEquals(61 to 37, be!!.width to be.height)
        assertArrayEquals(decodeBitsMask(BITS_61X37)!!.pixels, be.pixels)
    }

    /**
     * Cas limite documenté : quand les deux leçons réclament la **même** longueur de corps
     * (ici 8×256 et 2048×1 font tous deux 256 octets), le contrat little-endian gagne — un
     * backend actuel ne peut pas être mal lu par un ancien. Le repli big-endian ne sert que
     * quand la leçon du contrat est impossible.
     */
    @Test
    fun `a taille egale la lecon little-endian du contrat gagne`() {
        val little = decodeBitsMask(M1.b64(8, 256, { _, _ -> true }))!!
        assertEquals(8 to 256, little.width to little.height)
        val ambigue = decodeBitsMask(M1.b64(8, 256, { _, _ -> true }, littleEndian = false))!!
        assertEquals(2048 to 1, ambigue.width to ambigue.height)
        assertTrue(ambigue.isObject(0, 0))
    }

    @Test
    fun `decodeMaskAny sur bits rend null au lieu de remonter une exception`() {
        val hostiles = listOf("", "    ", MAGIC_INCONNU, ENORME_65535, CORPS_TROP_LONG_8X2, BITS_5X3.take(12))
        for (payload in hostiles) {
            val issue = runCatching { decodeMaskAny(payload, MASK_ENCODING_BITS) }
            assertTrue("$payload : ${issue.exceptionOrNull()}", issue.isSuccess)
            assertNull(payload, issue.getOrNull())
        }
    }

    private companion object {
        // Vecteurs produits par le backend réel (`imaging.py`), en-tête little-endian.
        const val BITS_5X3 = "TTEFAAMAeJyb4KEAAAJjAPk="
        const val BITS_1X5 = "TTEBAAUAeJxrYGhgaAAABIUBgQ=="
        const val BITS_15X4 = "TTEPAAQAeJz7/+/MmVWrZs4AAB4mBhs="
        const val BITS_61X37 = "TTE9ACUAeJybslTTK2jKUg0QAWRNgHA1PSDcKQsgXK+AKSNcHQBoeHW4"
        // Lignes de 13 bits remises à 1 au-delà de la largeur : bourrage que le serveur met à 0.
        const val PAD_13X3 = "TTENAAMAeJxb9X8VEAIAEPUE/A=="
        // En-têtes 8x2 (2 octets de corps attendus) avec 3 ou 4 lignes de corps : refusés par Python.
        const val CORPS_TROP_LONG_8X2 = "TTEIAAIAeJxjZGIGAAANAAc="
        const val CORPS_TROP_LONG_8X2_GROS = "TTEIAAIAeJxjZGJmAQAAGAAL"
        // En-tête 8x2 avec 1 seul octet de corps : refusé par Python (`size(1 != 2)`).
        const val CORPS_TROP_COURT_8X2 = "TTEIAAIAeJxjBAAAAgAC"
        // Magic « X1 » : `unknown mask magic`, même refus des deux côtés.
        const val MAGIC_INCONNU = "WDEIAAIAeJxjZAIAAAYABA=="
        // 65535×65535 : les quatre octets d'en-tête sont FF, donc identiques dans les deux leçons.
        const val ENORME_65535 = "TTH/////eJxjBAAAAgAC"
    }
}
