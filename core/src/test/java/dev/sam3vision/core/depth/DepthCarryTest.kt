package dev.sam3vision.core.depth

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le serveur répète ses médianes **par rang** entre deux cartes, et le rang change
 * avec les scores : ces tests rejouent cette frame-là. Une proximité doit suivre son
 * objet, sinon toute la frame se calibre sur des paires fausses.
 */
class DepthCarryTest {

    private val width = 240
    private val height = 320

    private fun box(x: Float, top: Float = 40f) = listOf(x, top, x + 30f, top + 120f)

    private fun obj(label: String, box: List<Float>, depth: Float?) = SegObject(label, 0.9f, box, "", depth = depth)

    private fun moved(box: List<Float>, dx: Float) = listOf(box[0] + dx, box[1], box[2] + dx, box[3])

    /** Frame répétée : mêmes valeurs, au même rang, sur des objets rangés autrement. */
    private fun repeatOf(fresh: List<SegObject>, order: List<Int>, dx: Float = 3f): List<SegObject> =
        order.mapIndexed { rank, index -> fresh[index].copy(box = moved(fresh[index].box, dx), depth = fresh[rank].depth) }

    @Test
    fun `une vraie carte passe telle quelle`() {
        val fresh = listOf(obj("person", box(20f), 0.5f), obj("pole", box(120f), 0.25f))
        assertEquals(mapOf(fresh[0].box to 0.5f, fresh[1].box to 0.25f), DepthCarry().onFrame(fresh, width, height))
    }

    @Test
    fun `sur une frame repetee chaque objet reprend sa proximite, pas celle de son rang`() {
        val carry = DepthCarry()
        val fresh = listOf(obj("person", box(20f), 0.5f), obj("person", box(120f), 0.25f))
        carry.onFrame(fresh, width, height)
        val swapped = repeatOf(fresh, listOf(1, 0))
        val out = carry.onFrame(swapped, width, height)
        assertEquals(0.25f, out[swapped[0].box])
        assertEquals(0.5f, out[swapped[1].box])
    }

    @Test
    fun `un objet sans voisin dans la carte n a pas de proximite`() {
        val carry = DepthCarry()
        val fresh = listOf(obj("person", box(20f), 0.5f))
        carry.onFrame(fresh, width, height)
        val arrived = obj("person", box(200f), 0.5f)
        assertTrue(carry.onFrame(listOf(arrived), width, height).isEmpty())
    }

    @Test
    fun `une proximite ne passe pas d un nom a l autre`() {
        val carry = DepthCarry()
        carry.onFrame(listOf(obj("person", box(20f), 0.5f)), width, height)
        assertTrue(carry.onFrame(listOf(obj("pole", box(20f), 0.5f)), width, height).isEmpty())
    }

    @Test
    fun `une mesure ne sert qu a un objet`() {
        val carry = DepthCarry()
        carry.onFrame(listOf(obj("person", box(20f), 0.5f)), width, height)
        val out = carry.onFrame(listOf(obj("person", box(22f), 0.5f), obj("person", box(30f), null)), width, height)
        assertEquals(1, out.size)
    }

    @Test
    fun `une carte trop vieille ne vaut plus`() {
        val carry = DepthCarry(maxRepeats = 2)
        val fresh = listOf(obj("person", box(20f), 0.5f))
        carry.onFrame(fresh, width, height)
        repeat(2) { assertFalse(carry.onFrame(fresh, width, height).isEmpty()) }
        assertTrue(carry.onFrame(fresh, width, height).isEmpty())
    }

    @Test
    fun `une frame sans medianes ne dit rien et ne compte pas`() {
        val carry = DepthCarry()
        assertTrue(carry.onFrame(listOf(obj("person", box(20f), null)), width, height).isEmpty())
    }

    @Test
    fun `reset oublie la carte`() {
        val carry = DepthCarry()
        val fresh = listOf(obj("person", box(20f), 0.5f))
        carry.onFrame(fresh, width, height)
        carry.reset()
        assertEquals(mapOf(fresh[0].box to 0.5f), carry.onFrame(fresh, width, height))
    }

    @Test
    fun `poteau sans hauteur connue garde sa distance quand les rangs tournent`() {
        val fresh = listOf(
            obj("person", box(20f), 0.5f),
            obj("person", box(80f), 0.25f),
            obj("pole", box(160f), 0.1875f),
        )
        val carry = DepthCarry()
        carry.onFrame(fresh, width, height)
        val turned = repeatOf(fresh, listOf(2, 0, 1))
        val pinhole = listOf(
            DistanceReading(1, "person", 3f, 3f, turned[1].box),
            DistanceReading(2, "person", 6f, 6f, turned[2].box),
        )
        val detections = turned.map { Detection(it.label, it.score, it.box, width, height) }
        fun poleMeters(closeness: Map<List<Float>, Float>): Float =
            DepthReadings.refineWithCloseness(detections, pinhole, closeness).first { it.label == "pole" }.meters

        assertEquals("avec le rang : 8 m lus 1,5 m", 1.5f, poleMeters(DepthReadings.serverCloseness(turned)), 0.1f)
        assertEquals("avec sa boîte : 1,5 / 0,1875", 8f, poleMeters(carry.onFrame(turned, width, height)), 0.5f)
    }
}
