package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preuve « garée » sur fenêtre : une garée filmée à la main se prouve malgré le
 * tremblé SAM, et rien de ce qui bouge n'hérite de la preuve. Frames 640×480 à
 * la cadence du direct (200 ms). Les trajectoires réelles sont rejouées côté
 * `:app` (`CrossingParkedVideoTest`).
 */
class ParkedProofTest {

    private val step = 200L

    private fun det(box: List<Float>, label: String = "car", score: Float = 0.9f): Detection =
        Detection(label, score, box, width = 640, height = 480)

    /** Boîte de centre [cx] : 200 × 120 px par défaut. */
    private fun box(cx: Float, width: Float = 200f, height: Float = 120f, cy: Float = 240f): List<Float> =
        listOf(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)

    /** Tremblé réel d'une boîte SAM garée : ±2 px d'une frame à l'autre. */
    private fun shaky(index: Int): Float = if (index % 2 == 0) 320f - 2f else 320f + 2f

    /** Nourrit [frames] frames d'une boîte calculée par [boxAt], rend la dernière détection. */
    private fun ParkedProof.feed(frames: Int, startMs: Long = 0L, boxAt: (Int) -> List<Float>): Detection {
        var last = det(boxAt(0))
        repeat(frames) { index ->
            last = det(boxAt(index))
            update(listOf(last), startMs + index * step)
        }
        return last
    }

    @Test
    fun `garee tremblante - prouvee a 600 ms, pas avant`() {
        val proof = ParkedProof()
        val early = proof.feed(3) { box(shaky(it)) }
        assertFalse("400 ms : trop tôt", proof.isParked(early))
        val fourth = det(box(shaky(3)))
        proof.update(listOf(fourth), 3 * step)
        assertTrue("600 ms, 4 vues : prouvée", proof.isParked(fourth))
    }

    @Test
    fun `moins de quatre vues - rien n est prouve`() {
        val proof = ParkedProof()
        val last = det(box(320f))
        listOf(0L, 400L, 800L).forEach { proof.update(listOf(last), it) }
        assertFalse("cadence trop lente : pas de preuve", proof.isParked(last))
    }

    @Test
    fun `dates epoch - la preuve tient`() {
        // Les frames sont datées en epoch : en Float, l'écoulé y tomberait à zéro.
        val proof = ParkedProof()
        val last = proof.feed(5, startMs = 1_793_789_109_000L) { box(shaky(it)) }
        assertTrue(proof.isParked(last))
    }

    @Test
    fun `voiture qui roule - jamais prouvee`() {
        // 0,4 image/s : le bas de ce qu'on mesure sur les vraies voitures.
        val proof = ParkedProof()
        repeat(12) { index ->
            val moving = det(box(100f + index * 51f))
            proof.update(listOf(moving), index * step)
            assertFalse("frame $index", proof.isParked(moving))
        }
    }

    @Test
    fun `derive lente continue - jamais prouvee`() {
        // 0,08 image/s, juste au-dessus du seuil : sans fond, le doute bloque.
        val proof = ParkedProof()
        repeat(12) { index ->
            val drifting = det(box(200f + index * 10f))
            proof.update(listOf(drifting), index * step)
            assertFalse("frame $index", proof.isParked(drifting))
        }
    }

    @Test
    fun `pan lent de la main - prouvee quand meme`() {
        // 0,05 image/s : le pan mesuré sur la vidéo Samsung 20261104_101016.
        val proof = ParkedProof()
        val last = proof.feed(8) { box(200f + it * 6.4f) }
        assertTrue(proof.isParked(last))
    }

    @Test
    fun `approche de face - jamais prouvee`() {
        // 2 m/s à 12 m : la hauteur gagne 17 %/s, le centre ne bouge pas.
        val proof = ParkedProof()
        repeat(12) { index ->
            val height = 120f * (1f + 0.034f * index)
            val coming = det(box(320f, width = height * 1.6f, height = height))
            proof.update(listOf(coming), index * step)
            assertFalse("frame $index", proof.isParked(coming))
        }
    }

    @Test
    fun `garee qui demarre - preuve perdue en moins de 1,2 s`() {
        // 2 m/s² à 10 m : un démarrage ordinaire, 0,11 t² image.
        val proof = ParkedProof()
        assertTrue(proof.isParked(proof.feed(6) { box(shaky(it)) }))
        var lostAtMs = Long.MAX_VALUE
        repeat(8) { index ->
            val seconds = (index + 1) * step / 1000f
            val leaving = det(box(320f + 640f * 0.11f * seconds * seconds))
            proof.update(listOf(leaving), 6 * step + index * step)
            if (!proof.isParked(leaving) && lostAtMs == Long.MAX_VALUE) lostAtMs = (index + 1) * step
        }
        assertTrue("perdue après $lostAtMs ms", lostAtMs <= 1_200L)
    }

    @Test
    fun `garee qui se rapproche - preuve perdue`() {
        val proof = ParkedProof()
        assertTrue(proof.isParked(proof.feed(6) { box(320f) }))
        var last = det(box(320f))
        repeat(10) { index ->
            val height = 120f * (1f + 0.06f * (index + 1))
            last = det(box(320f, width = height * 1.6f, height = height))
            proof.update(listOf(last), (6 + index) * step)
        }
        assertFalse(proof.isParked(last))
    }

    @Test
    fun `approche tres lente apres la preuve - la memoire longue la lache`() {
        // 5 %/s de hauteur : invisible sur 1 s (sous la variation tolérée), mais
        // pas sur 3 s — et la piste ne se reprouve pas tant que ça dure.
        val proof = ParkedProof()
        assertTrue(proof.isParked(proof.feed(6) { box(320f) }))
        var last = det(box(320f))
        repeat(30) { index ->
            val height = 120f * (1f + 0.01f * (index + 1))
            last = det(box(320f, width = height * 1.6f, height = height))
            proof.update(listOf(last), (6 + index) * step)
            if (index >= 15) assertFalse("frame $index : toujours pas garée", proof.isParked(last))
        }
    }

    @Test
    fun `hysteresis - le tremble fort d une garee ne casse pas la preuve`() {
        val proof = ParkedProof()
        proof.feed(6) { box(320f) }
        // 0,1 image/s en dents de scie : au-dessus de la preuve, sous la chute.
        var last = det(box(320f))
        repeat(6) { index ->
            last = det(box(320f + (index % 3) * 13f))
            proof.update(listOf(last), (6 + index) * step)
            assertTrue("frame $index", proof.isParked(last))
        }
    }

    @Test
    fun `labels qui basculent - un seul vehicule`() {
        // Mesuré : `van`, `truck` et `car` sur le même véhicule, qui alternent.
        val proof = ParkedProof()
        val labels = listOf("van", "truck", "car")
        repeat(5) { index ->
            proof.update(listOf(det(box(shaky(index)), labels[index % 3])), index * step)
        }
        val same = box(shaky(5))
        val frame = labels.map { det(same, it) }
        proof.update(frame, 5 * step)
        frame.forEach { assertTrue(it.label, proof.isParked(it)) }
    }

    /** Garée de référence, prouvée, et une boîte en plus sur la frame suivante. */
    private fun parkedPlus(extra: Detection): Pair<ParkedProof, Detection> {
        val proof = ParkedProof()
        proof.feed(5) { box(320f, width = 240f, height = 160f) }
        val parked = det(box(320f, width = 240f, height = 160f))
        proof.update(listOf(parked, extra), 5 * step)
        return proof to parked
    }

    @Test
    fun `morceau d une garee - exempte avec elle`() {
        val fragment = det(listOf(330f, 180f, 400f, 300f), "van", 0.45f)
        val (proof, parked) = parkedPlus(fragment)
        assertTrue(proof.isParked(parked))
        assertTrue("morceau inscrit dans la garée", proof.isParked(fragment))
    }

    @Test
    fun `moto devant un van gare - jamais un morceau`() {
        val moto = det(listOf(330f, 180f, 400f, 300f), "motorcycle")
        val (proof, _) = parkedPlus(moto)
        assertFalse(proof.isParked(moto))
    }

    @Test
    fun `velo devant un van gare - jamais un morceau`() {
        val bike = det(listOf(330f, 180f, 400f, 300f), "bicycle")
        val (proof, _) = parkedPlus(bike)
        assertFalse(proof.isParked(bike))
    }

    @Test
    fun `voiture de meme taille devant une garee - pas un morceau`() {
        val same = det(listOf(300f, 165f, 520f, 315f))
        val (proof, _) = parkedPlus(same)
        assertFalse(proof.isParked(same))
    }

    @Test
    fun `boite a cote d une garee - pas un morceau`() {
        val aside = det(listOf(445f, 180f, 515f, 300f))
        val (proof, _) = parkedPlus(aside)
        assertFalse(proof.isParked(aside))
    }

    @Test
    fun `petite boite lancee sur une garee - pas un morceau`() {
        val proof = ParkedProof()
        val parkedBox = box(320f, width = 240f, height = 160f)
        proof.feed(5) { parkedBox }
        proof.update(listOf(det(parkedBox), det(listOf(260f, 200f, 340f, 280f))), 5 * step)
        val moved = det(listOf(275f, 200f, 355f, 280f)) // 0,12 image/s : encore un morceau…
        proof.update(listOf(det(parkedBox), moved), 6 * step)
        assertTrue(proof.isParked(moved))
        val launched = det(listOf(305f, 200f, 385f, 280f)) // …0,23 puis au-delà de la borne.
        proof.update(listOf(det(parkedBox), launched), 7 * step)
        val fast = det(listOf(345f, 205f, 425f, 285f))
        proof.update(listOf(det(parkedBox), fast), 8 * step)
        assertFalse("0,31 image/s : lancée", proof.isParked(fast))
    }

    @Test
    fun `morceau qui dure et bouge - se juge seul`() {
        // La jeunesse passée, l'exemption s'éteint : la piste répond d'elle-même.
        val proof = ParkedProof()
        val parkedBox = box(320f, width = 240f, height = 160f)
        proof.feed(5) { parkedBox }
        var inside = det(listOf(240f, 200f, 300f, 280f))
        repeat(8) { index ->
            inside = det(listOf(240f + index * 12f, 200f, 300f + index * 12f, 280f))
            proof.update(listOf(det(parkedBox), inside), (5 + index) * step)
        }
        assertFalse("0,09 image/s tenus : pas une garée", proof.isParked(inside))
    }

    @Test
    fun `une boite decalee sur une frame ne fait pas repartir la piste de zero`() {
        // Réel (20261104_101016, 2,3 s) : SAM rend deux boîtes sur la même voiture,
        // puis seulement la décalée. Le morceau ne vole pas la piste prouvée.
        val proof = ParkedProof()
        proof.feed(6) { listOf(100f, 200f, 200f, 300f) }
        val shifted = listOf(135f, 200f, 200f, 300f)
        proof.update(listOf(det(listOf(100f, 200f, 200f, 300f)), det(shifted, "van", 0.5f)), 6 * step)
        val after = det(shifted)
        proof.update(listOf(after), 7 * step)
        assertTrue(proof.isParked(after))
    }

    @Test
    fun `pan rapide - tout repart de zero`() {
        val proof = ParkedProof()
        val last = proof.feed(6) { box(320f) }
        assertTrue(proof.isParked(last))
        proof.update(listOf(last), 6 * step, panning = true)
        assertFalse("pan rapide : plus rien de prouvé", proof.isParked(last))
        proof.update(listOf(last), 7 * step)
        assertFalse("la fenêtre repart à vide", proof.isParked(last))
    }

    @Test
    fun `sans date - rien n est prouve`() {
        val proof = ParkedProof()
        val last = det(box(320f))
        repeat(8) { proof.update(listOf(last), -1L) }
        assertFalse(proof.isParked(last))
    }

    @Test
    fun `garee coupee au cadre - le bord libre juge`() {
        // Le bord collé au cadre ne dit rien : c'est le bord libre qui tient.
        val proof = ParkedProof()
        val last = proof.feed(6) { listOf(0f, 180f, 90f + (it % 2) * 2f, 300f) }
        assertTrue(proof.isParked(last))
    }

    @Test
    fun `voiture qui entre dans le cadre - jamais prouvee`() {
        val proof = ParkedProof()
        repeat(8) { index ->
            val entering = det(listOf(0f, 180f, 40f + index * 50f, 300f))
            proof.update(listOf(entering), index * step)
            assertFalse("frame $index", proof.isParked(entering))
        }
    }

    @Test
    fun `boite coupee des deux cotes - jamais prouvee`() {
        // Voiture qui remplit le cadre : aucun bord libre, on ne prouve rien.
        val proof = ParkedProof()
        val last = proof.feed(8) { listOf(0f, 120f, 640f, 360f) }
        assertFalse(proof.isParked(last))
    }

    @Test
    fun `trou de detection court - la preuve survit`() {
        val proof = ParkedProof()
        val last = proof.feed(6) { box(320f) }
        proof.update(emptyList(), 6 * step)
        proof.update(emptyList(), 7 * step)
        proof.update(listOf(last), 8 * step)
        assertTrue(proof.isParked(last))
    }

    @Test
    fun `absence longue - la piste est oubliee`() {
        val proof = ParkedProof()
        val last = proof.feed(6) { box(320f) }
        proof.update(emptyList(), 6 * step + ParkedProof.STALE_MS + 1L)
        proof.update(listOf(last), 6 * step + ParkedProof.STALE_MS + 2L)
        assertFalse("réapparition : à reprouver", proof.isParked(last))
    }

    @Test
    fun `score faible, non vehicule, boite degeneree - ignores`() {
        val proof = ParkedProof()
        val weak = det(box(320f), score = TraverseVeto.SCORE_MIN)
        val person = det(box(320f), "person")
        val flat = det(listOf(10f, 10f, 10f, 10f))
        repeat(6) { proof.update(listOf(weak, person, flat), it * step) }
        listOf(weak, person, flat).forEach { assertFalse(proof.isParked(it)) }
    }

    @Test
    fun `reset - oublie tout`() {
        val proof = ParkedProof()
        val last = proof.feed(6) { box(320f) }
        proof.reset()
        assertFalse(proof.isParked(last))
    }
}
