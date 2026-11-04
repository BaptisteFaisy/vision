package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PedLampRankTest {

    private val dark = 0xFF101010.toInt()
    private val green = 0xFF00FF00.toInt()
    private val red = 0xFFFF0000.toInt()

    /** Crop 12×12 : fond sombre, pixels [color] aux positions (x, y). */
    private fun crop(color: Int, vararg pts: Pair<Int, Int>): IntArray {
        val argb = IntArray(12 * 12) { dark }
        for ((x, y) in pts) argb[y * 12 + x] = color
        return argb
    }

    /** Bonhomme troué : 22 px dans une boîte 5×10 (remplissage 0,44). */
    private fun man(color: Int): IntArray = crop(
        color,
        5 to 1, 6 to 1,
        5 to 2, 6 to 2,
        5 to 3, 6 to 3,
        3 to 4, 4 to 4, 6 to 4, 7 to 4,
        5 to 5, 6 to 5,
        5 to 6, 6 to 6,
        5 to 7, 6 to 7,
        5 to 8, 6 to 8,
        5 to 9, 6 to 9,
        4 to 10, 6 to 10,
    )

    /** Disque plein : bloc 5×5 (remplissage 1,0). */
    private fun disk(color: Int): IntArray {
        val pts = mutableListOf<Pair<Int, Int>>()
        for (y in 3..7) for (x in 3..7) pts += x to y
        return crop(color, *pts.toTypedArray())
    }

    @Test
    fun `bonhomme vert devant disque vert meme a flux inferieur`() {
        val man = PedLampRank.rank(listOf(0.2f, 0.2f, 0.4f, 0.5f), man(green), 12, 12, 5f)
        val disk = PedLampRank.rank(listOf(0.5f, 0.2f, 0.7f, 0.5f), disk(green), 12, 12, 50f)
        assertTrue(man.ped - disk.ped > PedLampRank.PED_EPS)
        assertTrue(PedLampRank.better(man, disk))
        assertFalse(PedLampRank.better(disk, man))
    }

    @Test
    fun `daltonien - bonhomme rouge devant disque`() {
        val man = PedLampRank.rank(listOf(0.2f, 0.2f, 0.4f, 0.5f), man(red), 12, 12, 5f)
        val disk = PedLampRank.rank(listOf(0.5f, 0.2f, 0.7f, 0.5f), disk(green), 12, 12, 50f)
        assertTrue(PedLampRank.better(man, disk))
    }

    @Test
    fun `bout coupe au bord perd contre vue entiere`() {
        val cut = PedLampRank.rank(listOf(0f, 0.2f, 0.2f, 0.5f), man(green), 12, 12, 50f)
        val full = PedLampRank.rank(listOf(0.5f, 0.2f, 0.7f, 0.5f), disk(green), 12, 12, 5f)
        assertTrue(cut.edgeTouch)
        assertFalse(full.edgeTouch)
        assertTrue(PedLampRank.better(full, cut))
    }

    @Test
    fun `egalite de forme - le flux tranche comme avant`() {
        val dim = PedLampRank.rank(listOf(0.2f, 0.2f, 0.4f, 0.5f), disk(green), 12, 12, 5f)
        val bright = PedLampRank.rank(listOf(0.5f, 0.2f, 0.7f, 0.5f), disk(green), 12, 12, 50f)
        assertTrue(PedLampRank.better(bright, dim))
        assertFalse(PedLampRank.better(dim, bright))
    }

    @Test
    fun `ecart sous EPS - le flux tranche`() {
        val low = PedLampRank.Ranked(edgeTouch = false, ped = 0.50f, flux = 10f)
        val high = PedLampRank.Ranked(edgeTouch = false, ped = 0.60f, flux = 5f)
        assertTrue(PedLampRank.better(low, high))
        val clear = PedLampRank.Ranked(edgeTouch = false, ped = 0.90f, flux = 5f)
        assertFalse(PedLampRank.better(low, clear))
    }

    @Test
    fun `contact au bord - interieur faux, bords et coins vrais`() {
        assertFalse(PedLampRank.isEdgeTouch(listOf(0.2f, 0.2f, 0.4f, 0.5f)))
        assertTrue(PedLampRank.isEdgeTouch(listOf(0f, 0.2f, 0.4f, 0.5f)))
        assertTrue(PedLampRank.isEdgeTouch(listOf(0.2f, 0f, 0.4f, 0.5f)))
        assertTrue(PedLampRank.isEdgeTouch(listOf(0.2f, 0.2f, 1f, 0.5f)))
        assertTrue(PedLampRank.isEdgeTouch(listOf(0.2f, 0.2f, 0.4f, 1f)))
        assertTrue(PedLampRank.isEdgeTouch(listOf(0f, 0f, 1f, 1f)))
    }

    @Test
    fun `contact au bord - boite illisible jamais en contact`() {
        assertFalse(PedLampRank.isEdgeTouch(listOf(0.2f, 0.2f)))
        assertFalse(PedLampRank.isEdgeTouch(listOf(0.4f, 0.2f, 0.2f, 0.5f)))
        assertFalse(PedLampRank.isEdgeTouch(listOf(0f, 0.2f, Float.NaN, 0.5f)))
    }

    @Test
    fun `forme sans tache vaut zero`() {
        assertEquals(0f, PedLampRank.pedScore(IntArray(12 * 12) { dark }, 12, 12), 0f)
        assertEquals(0f, PedLampRank.pedScore(crop(green, 6 to 6), 12, 12), 0f)
        assertEquals(0f, PedLampRank.pedScore(IntArray(0), 0, 0), 0f)
    }

    @Test
    fun `samsung 20261104 - vert pieton portrait gagne sur rouge tricolore`() {
        val loader = javaClass.classLoader
        val (gArgb, gw, gh) = pixelsOf(loader, "feuxrank", "samsung-20261104-green.ppm")
        val (rArgb, rw, rh) = pixelsOf(loader, "feuxrank", "samsung-20261104-red.ppm")
        assertEquals(CrossingState.Green, PedLightGate.verdict(gArgb, gw, gh))
        assertEquals(CrossingState.Red, PedLightGate.verdict(rArgb, rw, rh))
        // Boîtes SAM normalisées (portrait 480x640) : rouge 0,9414, vert
        // 0,8906 — le score seul prendrait le rouge (bug build nogit 04/11).
        val redBox = listOf(7f / 480, 103.1f / 640, 80.6f / 480, 225f / 640)
        val greenBox = listOf(258.8f / 480, 256.2f / 640, 315f / 480, 301.2f / 640)
        val red = PedLampRank.rank(redBox, rArgb, rw, rh, LampLens.flux(rArgb, rw, rh))
        val green = PedLampRank.rank(greenBox, gArgb, gw, gh, LampLens.flux(gArgb, gw, gh))
        assertTrue(PedLampRank.better(green, red))
        assertFalse(PedLampRank.better(red, green))
    }

    /** Crop réel `feuxrank/samsung-101521-[name].ppm` (densité caméra, voir le README). */
    private fun real(name: String) = pixelsOf(javaClass.classLoader, "feuxrank", "samsung-101521-$name.ppm")

    private fun ranked(box: List<Float>, crop: Triple<IntArray, Int, Int>): PedLampRank.Ranked {
        val (argb, w, h) = crop
        return PedLampRank.rank(box, argb, w, h, LampLens.flux(PedLampRank.lampOnly(argb), w, h))
    }

    @Test
    fun `samsung 101521 - densite reelle - le bonhomme vert bat le tricolore vu de cote`() {
        val tricolore = ranked(listOf(0.14f, 0.09f, 0.22f, 0.19f), real("tricolore-cote"))
        val man = ranked(listOf(0.44f, 0.23f, 0.50f, 0.27f), real("vert"))
        assertTrue(PedLampRank.better(man, tricolore))
        assertFalse(PedLampRank.better(tricolore, man))
    }

    @Test
    fun `samsung 101521 - la porte lit juste les trois etats du feu pieton`() {
        val (g, gw, gh) = real("vert")
        val (n, nw, nh) = real("noir")
        val (r, rw, rh) = real("rouge")
        assertEquals(CrossingState.Green, PedLightGate.verdict(g, gw, gh))
        assertEquals(CrossingState.Red, PedLightGate.verdict(n, nw, nh))
        assertEquals(CrossingState.Red, PedLightGate.verdict(r, rw, rh))
        assertFalse(PedLightGate.hasRedLamp(g, gw, gh))
        assertFalse("lampe noire : un non-vert, pas un rouge", PedLightGate.hasRedLamp(n, nw, nh))
        assertTrue(PedLightGate.hasRedLamp(r, rw, rh))
    }

    @Test
    fun `deux points eloignes ne font pas une silhouette`() {
        val specks = crop(red, 1 to 1, 2 to 1, 10 to 10, 9 to 10)
        assertEquals(0f, PedLampRank.pedScore(specks, 12, 12), 0f)
    }

    @Test
    fun `le gris-bleu d'un caisson n'est pas une lampe`() {
        val housing = 0xFF5070A0.toInt()
        val argb = crop(housing, 1 to 1, 1 to 2, 1 to 3, 8 to 3, 8 to 8, 2 to 9, 3 to 10)
        assertEquals(0f, PedLampRank.pedScore(argb, 12, 12), 0f)
        assertEquals(0f, LampLens.flux(PedLampRank.lampOnly(argb), 12, 12), 0f)
    }

    @Test
    fun `lampOnly garde rouge et vert francs`() {
        val argb = man(green)
        assertTrue(PedLampRank.lampOnly(argb).contentEquals(argb.map { if (it == green) green else 0xFF000000.toInt() }.toIntArray()))
    }
}
