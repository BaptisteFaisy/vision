package dev.sam3vision.core.mask

import dev.sam3vision.core.model.MASK_ENCODING_BITS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures dorées des masques : le client doit relire **au pixel près** ce que le serveur a
 * écrit, et sans rien perdre (IoU de 1).
 *
 * Les payloads de [MaskGoldenData] ne viennent pas de l'encodeur Kotlin des tests (`M1`) mais
 * d'une implémentation **Python indépendante** du format `M1` (le côté serveur) : elle réencode
 * à l'octet près six vecteurs réels déjà présents dans le dépôt (`--selftest` de
 * `scripts/gen_mask_golden.py`). C'est ce qui manquait : jusqu'ici, tests et décodeur
 * partageaient la même lecture du format, donc une erreur comprise des deux côtés — ordre des
 * bits, rembourrage de ligne, boutisme — passait inaperçue.
 *
 * Le décodage emprunte le **chemin réel du client** (`decodeMaskAny`, celui de `MaskPainter`),
 * pas la fonction bas niveau : c'est la variante `bits` — la seule compacte que le client sache
 * lire — qui est verrouillée ici. Une seconde variante de codec (RLE, polygones, conteneur
 * auto-décrit) ferait échouer ces deux cas.
 *
 * Chiffres et arbitrage : `docs/MESURES-u11c.txt`, régénérable par
 * `python3 scripts/gen_mask_golden.py --write`.
 */
class MaskGoldenTest {

    /**
     * 6/6 au pixel près : chaque ligne du masque relu est comparée à la ligne attendue de la
     * fixture, et le magic `M1` est vérifié (une seule variante de codec).
     */
    @Test
    fun `les six fixtures dorees se decodent au pixel pres`() {
        var justes = 0
        for (golden in MaskGoldenData.GOLDEN) {
            val octets = base64Bytes(golden.payloadB64)
            assertNotNull("${golden.nom} : payload base64 illisible", octets)
            val brut = octets!!
            assertTrue("${golden.nom} : magic M1 attendu",
                brut[0] == 'M'.code.toByte() && brut[1] == '1'.code.toByte())

            val lu = decodeMaskAny(golden.payloadB64, MASK_ENCODING_BITS)
            assertNotNull("${golden.nom} : payload refuse par le client", lu)
            val mask = lu!!
            assertEquals("${golden.nom} : largeur", golden.largeur, mask.width)
            assertEquals("${golden.nom} : hauteur", golden.hauteur, mask.height)
            for (y in 0 until golden.hauteur) {
                assertEquals("${golden.nom} ligne $y", golden.rangs[y], M1.rowText(mask, y))
            }
            justes++
        }
        assertEquals("six formes dorees", 6, MaskGoldenData.GOLDEN.size)
        assertEquals("formes decodees au pixel pres", MaskGoldenData.GOLDEN.size, justes)
    }

    /** L'IoU du serveur vers le client vaut exactement 1 : le codec `bits` est sans perte. */
    @Test
    fun `l IoU des six fixtures dorees vaut 1`() {
        for (golden in MaskGoldenData.GOLDEN) {
            val lu = decodeMaskAny(golden.payloadB64, MASK_ENCODING_BITS)
            assertNotNull("${golden.nom} : fixture illisible", lu)
            val mask = lu!!
            val iou = iou(mask, golden)
            println("IoU ${golden.nom} : $iou (${pixelsObjet(golden)} pixels objet)")
            assertEquals("${golden.nom} : IoU", 1.0, iou, 0.0)
        }
    }

    /** Intersection sur union des pixels objet, entre le masque relu et la fixture. */
    private fun iou(mask: GrayMask, golden: MaskGoldenData.Golden): Double {
        if (mask.width != golden.largeur || mask.height != golden.hauteur) return 0.0
        var inter = 0
        var union = 0
        for (y in 0 until golden.hauteur) {
            val rang = golden.rangs[y]
            for (x in 0 until golden.largeur) {
                val attendu = rang[x] == '1'
                val vu = mask.isObject(x, y)
                if (attendu && vu) inter++
                if (attendu || vu) union++
            }
        }
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    private fun pixelsObjet(golden: MaskGoldenData.Golden): Int =
        golden.rangs.sumOf { rang -> rang.count { it == '1' } }
}