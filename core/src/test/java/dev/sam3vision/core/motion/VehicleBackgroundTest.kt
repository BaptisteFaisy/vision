package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contrat V1 du discriminant véhicule/fond : qui bouge, du véhicule suivi ou de la caméra ?
 *
 * - fond fixe + boîte mobile → [VehicleBackground.Cause.VEHICLE] (la voiture roule, caméra fixe) ;
 * - fond mobile + boîte fixe → [VehicleBackground.Cause.CAMERA] (filmer-suivi : la caméra bouge) ;
 * - fond et boîte mobiles → [VehicleBackground.Cause.BOTH] ;
 * - fond plat (illisible) → [VehicleBackground.Cause.UNKNOWN], comme une scène plate ne donne
 *   pas d'avis à `BlockMotionSource` (`MotionSourceTest.scene plate ne donne pas d avis`).
 *
 * Les scènes sont synthétiques et déterministes (`MotionScenes`, fond texturé + rectangle
 * d'avant-plan) : le mouvement vrai est connu, donc ces tests jugent le discriminant, pas
 * une séquence vidéo réelle — aucune mesure de terrain ici.
 */
class VehicleBackgroundTest {

    @Test
    fun `fond fixe et boite mobile, le vehicule bouge`() {
        val scene = MotionScenes.vehicleMoving()
        assertEquals(
            VehicleBackground.Cause.VEHICLE,
            VehicleBackground.classify(scene.prev, scene.cur, scene.prevBox, scene.curBox),
        )
    }

    @Test
    fun `fond mobile et boite fixe, la camera bouge`() {
        val scene = MotionScenes.cameraMoving()
        assertEquals(
            VehicleBackground.Cause.CAMERA,
            VehicleBackground.classify(scene.prev, scene.cur, scene.prevBox, scene.curBox),
        )
    }

    @Test
    fun `fond et boite mobiles, les deux bougent`() {
        val scene = MotionScenes.bothMoving()
        assertEquals(
            VehicleBackground.Cause.BOTH,
            VehicleBackground.classify(scene.prev, scene.cur, scene.prevBox, scene.curBox),
        )
    }

    @Test
    fun `fond plat ne donne pas d avis`() {
        val box = MotionScenes.VEHICLE_BOX
        val moved = box.translated(BOX_DX.toFloat(), 0f)
        val prev = MotionScenes.withForeground(flat(), box)
        val cur = MotionScenes.withForeground(flat(), moved)
        assertEquals(
            VehicleBackground.Cause.UNKNOWN,
            VehicleBackground.classify(prev, cur, box, moved),
        )
    }

    /** Même fond uni que `MotionSourceTest` : niveau 90, aucun bord où accrocher une estimation. */
    private fun flat(): GrayMask {
        val size = MotionScenes.WIDTH * MotionScenes.HEIGHT
        return GrayMask(MotionScenes.WIDTH, MotionScenes.HEIGHT, ByteArray(size) { 90 })
    }

    private companion object {
        /** Déplacement de la boîte sur fond plat : 12 px, le même que `vehicleMoving`. */
        const val BOX_DX = 12
    }
}
