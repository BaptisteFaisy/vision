package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.traverse.MovingVerdict
import dev.sam3vision.core.traverse.VehicleBackground
import dev.sam3vision.core.traverse.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contrat V1 du discriminant véhicule/fond ([VehicleBackground.decide]) : qui bouge,
 * du véhicule suivi ou de la caméra ? Le résidu = boîte − fond (fractions d'image/s)
 * est le mouvement propre du véhicule : franc ⇒ [Verdict.VEHICLE_MOVED], nul sur fond
 * franc ⇒ [Verdict.CAMERA_MOVED] (la boîte suit le porteur), les deux faibles ⇒
 * [Verdict.STATIC], doute ⇒ [Verdict.UNKNOWN].
 *
 * Deux niveaux : la règle seule (échantillon global piloté, déterministe) puis la
 * chaîne complète (fond estimé à l'estimateur réel sur scènes synthétiques
 * déterministes — le mouvement vrai est connu, donc ces tests jugent le
 * discriminant, pas une séquence vidéo réelle, aucune mesure de terrain ici).
 * Le verdict porte les vitesses comparées : en cas d'échec, son détail dit si
 * c'est l'estimateur ou la règle qui a failli.
 *
 * Réconciliations avec la maquette initiale : il n'y a pas de cause BOTH en V1
 * (fond et boîte mobiles ⇒ résidu franc ⇒ le véhicule bouge vraiment) ; une boîte
 * fixe sur fond qui défile, c'est une poursuite caméra d'un véhicule mobile (un
 * véhicule fixe suivrait le défilement) ⇒ [Verdict.VEHICLE_MOVED] aussi.
 */
class VehicleBackgroundTest {

    private val estimator = BlockMotionSource()

    // Règle seule (global piloté).

    @Test
    fun `regle - fond fixe confiant et boite mobile, le vehicule bouge`() {
        val scene = MotionScenes.vehicleMoving()
        val (boxDx, boxDy) = boxMotion(scene)
        val verdict = decide(boxDx, boxDy, MotionSample(0f, 0f, 1f, CONFIDENT), DT_MS)
        assertEquals("$verdict", Verdict.VEHICLE_MOVED, verdict.verdict)
    }

    @Test
    fun `regle - boite qui suit le fond, la camera bouge`() {
        val scene = trackedScene()
        val (boxDx, boxDy) = boxMotion(scene)
        val global = MotionSample(TRACK_DX.toFloat(), TRACK_DY.toFloat(), 1f, CONFIDENT)
        val verdict = decide(boxDx, boxDy, global, TRACK_DT_MS)
        assertEquals("$verdict", Verdict.CAMERA_MOVED, verdict.verdict)
    }

    @Test
    fun `regle - ni boite ni fond, a l arret`() {
        val verdict = decide(0f, 0f, MotionSample(0f, 0f, 1f, CONFIDENT), DT_MS)
        assertEquals("$verdict", Verdict.STATIC, verdict.verdict)
    }

    @Test
    fun `regle - estimateur muet, pas d avis`() {
        val verdict = decide(0f, 0f, null, DT_MS)
        assertEquals("$verdict", Verdict.UNKNOWN, verdict.verdict)
    }

    // Chaîne complète (fond estimé sur scènes).

    @Test
    fun `fond mobile et boite fixe, poursuite camera d un vehicule mobile`() {
        val verdict = decideOf(MotionScenes.cameraMoving(), DT_MS)
        assertEquals("$verdict", Verdict.VEHICLE_MOVED, verdict.verdict)
    }

    @Test
    fun `fond et boite mobiles, le vehicule bouge`() {
        val verdict = decideOf(MotionScenes.bothMoving(), DT_MS)
        assertEquals("$verdict", Verdict.VEHICLE_MOVED, verdict.verdict)
    }

    @Test
    fun `boite qui suit le fond estime, la camera bouge`() {
        val verdict = decideOf(trackedScene(), TRACK_DT_MS)
        assertEquals("$verdict", Verdict.CAMERA_MOVED, verdict.verdict)
    }

    @Test
    fun `fond fixe et boite mobile, l estimateur V1 se tait`() {
        // L'estimateur V1 n'a pas d'avis global confiant quand seul l'objet bouge
        // sur fond fixe : la chaîne rend UNKNOWN (silence sûr, pas d'arrêt inventé).
        // La règle produit du cas (fond fixe confiant ⇒ véhicule) est couverte par
        // `regle - fond fixe confiant et boite mobile, le vehicule bouge`.
        val verdict = decideOf(MotionScenes.vehicleMoving(), DT_MS)
        assertEquals("$verdict", Verdict.UNKNOWN, verdict.verdict)
    }

    @Test
    fun `fond plat ne donne pas d avis`() {
        val box = MotionScenes.VEHICLE_BOX
        val flat = flat()
        val verdict = decideOf(MotionScenes.VehicleScene(flat, flat, box, box), DT_MS)
        assertEquals("$verdict", Verdict.UNKNOWN, verdict.verdict)
    }

    /** Verdict V1 d'une scène : fond estimé à l'estimateur réel, boîte en fractions d'image. */
    private fun decideOf(scene: MotionScenes.VehicleScene, dtMs: Long): MovingVerdict {
        val (boxDx, boxDy) = boxMotion(scene)
        return decide(boxDx, boxDy, estimator.estimateSince(scene.prev, scene.cur), dtMs)
    }

    /** Verdict V1 direct : [boxDx]/[boxDy] en fractions d'image, [global] piloté ou estimé. */
    private fun decide(boxDx: Float, boxDy: Float, global: MotionSample?, dtMs: Long): MovingVerdict =
        VehicleBackground.decide(
            trackId = 0,
            boxDx = boxDx,
            boxDy = boxDy,
            global = global,
            lumaWidth = MotionScenes.WIDTH,
            lumaHeight = MotionScenes.HEIGHT,
            dtMs = dtMs,
            bgChangeScore = 0f,
        )

    /** Déplacement du centre de la boîte entre les deux frames, en fractions d'image. */
    private fun boxMotion(scene: MotionScenes.VehicleScene): Pair<Float, Float> {
        val dx = (scene.curBox.centerX - scene.prevBox.centerX) / MotionScenes.WIDTH
        val dy = (scene.curBox.centerY - scene.prevBox.centerY) / MotionScenes.HEIGHT
        return dx to dy
    }

    /** Poursuite : la boîte suit le fond de ([TRACK_DX], [TRACK_DY]) px, fond texturé. */
    private fun trackedScene(): MotionScenes.VehicleScene {
        val box = MotionScenes.VEHICLE_BOX
        val moved = box.translated(TRACK_DX.toFloat(), TRACK_DY.toFloat())
        val prev = MotionScenes.withForeground(MotionScenes.scene(), box)
        val background = MotionScenes.shifted(MotionScenes.scene(), TRACK_DX, TRACK_DY)
        return MotionScenes.VehicleScene(prev, MotionScenes.withForeground(background, moved), box, moved)
    }

    /** Même fond uni que `MotionSourceTest` : niveau 90, aucun bord où accrocher une estimation. */
    private fun flat(): GrayMask {
        val size = MotionScenes.WIDTH * MotionScenes.HEIGHT
        return GrayMask(MotionScenes.WIDTH, MotionScenes.HEIGHT, ByteArray(size) { 90 })
    }

    private companion object {
        /** Délai inter-frames des scènes : 500 ms, marges confortables aux seuils 0,02/0,03. */
        const val DT_MS = 500L

        /** Poursuite boîte/fond : 1 s, le résidu tolère l'erreur d'estimation (~5 px). */
        const val TRACK_DT_MS = 1000L

        /** Poursuite : la boîte suit le fond de (12, 4) px, dans la plage de l'estimateur. */
        const val TRACK_DX = 12
        const val TRACK_DY = 4

        /** Confiance d'un fond sûr : franchement au-dessus de `TRUST_MIN` (0,12). */
        const val CONFIDENT = 0.5f
    }
}
