package dev.sam3vision.core.distance

import dev.sam3vision.core.live.Guidance
import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mesure du direct : ce que le sténopé et le plan de sol peuvent dire, et ce
 * qu'ils refusent de dire. Verrouille les deux défauts « la distance est fausse
 * là où ça compte » du P0 point 7 :
 *
 * - l'adulte (1,70 m) imposé à tout être humain — un enfant de 1,20 m à 3 m
 *   sortait à 4,26 m, hors de la portée de contact de 4 m ;
 * - la constante du bord bas pour une boîte coupée — les pieds sous le cadre,
 *   la branche rendait la distance du bord bas (2,27 m à tangage 0 sur le
 *   téléphone de l'audit), donc un chiffre faux pour un objet à 1 m.
 */
class DistanceEstimatorTest {

    private val width = 512
    private val height = 384
    private val focal = LensSpec.Default.focalPx(width, height)
    private val principalY = LensSpec.Default.principalPx(width, height).second

    /** Haut de toutes les boîtes de test : jamais collé au bord haut. */
    private val top = 100f

    private fun detection(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width, height)

    /** Boîte de largeur fixe dont la hauteur en pixels vaut [realMeters] à [meters]. */
    private fun boxFor(realMeters: Float, meters: Float): List<Float> {
        val heightPx = realMeters * focal / meters
        return listOf(100f, top, 200f, top + heightPx)
    }

    /** Boîte dont le bas touche le bord bas de l'image : pieds hors cadre. */
    private fun cutBottomBox(): List<Float> = listOf(100f, 200f, 200f, height.toFloat())

    @Test
    fun `enfant de 1 20 m a 3 m reste dans la portee de 4 m`() {
        val box = boxFor(1.20f, 3f)
        // calcul sténopé explicite : d = h × f / hauteur_px
        val childHeight = KnownSizes.heightMeters("child")!!
        assertEquals(3f, childHeight * focal / (box[3] - box[1]), 0.01f)
        assertEquals(3f, Pinhole.distanceMeters(box, childHeight, focal)!!, 0.01f)

        val reading = DistanceMeter().measure(width, height, listOf(detection("child", box))).single()
        assertEquals(3f, reading.meters, 0.01f)
        // dans la portée : l'enfant bloque et se contourne (Guidance, 4 m)
        assertTrue("enfant à 3 m hors portée : ${reading.meters}", reading.meters < Guidance.RANGE_METERS)
    }

    @Test
    fun `enfant lu comme un adulte sortirait de la portee`() {
        val box = boxFor(1.20f, 3f)
        // l'ancien comportement (1,70 m pour tout humain) : 42 % trop loin
        val asAdult = Pinhole.distanceMeters(box, 1.70f, focal)!!
        assertEquals(4.25f, asAdult, 0.01f)
        assertTrue("un enfant lu comme un adulte ne bloquerait plus", asAdult > Guidance.RANGE_METERS)
        // et l'adulte lui-même, sur sa propre boîte, reste à 3 m
        val adultBox = boxFor(1.70f, 3f)
        val adult = DistanceMeter().measure(width, height, listOf(detection("personne", adultBox))).single()
        assertEquals(3f, adult.meters, 0.01f)
    }

    @Test
    fun `bas de boite coupe sans plan de sol ne rend aucune distance`() {
        val box = cutBottomBox()
        assertTrue(Pinhole.isCutBottom(box, height))
        // aucune pose : la branche sol est muette et le sténopé impraticable
        assertTrue(DistanceMeter().measure(width, height, listOf(detection("personne", box))).isEmpty())
        // un concept sans hauteur sténopé est refusé de la même façon
        assertTrue(DistanceMeter().measure(width, height, listOf(detection("curb", box))).isEmpty())
    }

    @Test
    fun `bas de boite coupe avec pose ne rend plus la constante du bord bas`() {
        // La pose seule ne fait pas un plan de sol : la ligne des pieds est hors
        // cadre, et la rabattre sur le bord bas rend une constante de pose.
        val pose = CameraPose(1.5f)
        val constant = GroundPlane.forwardMeters(height.toFloat(), pose, focal, principalY, height)!!
        // la valeur refusée : 3,01 m avec la focale par défaut (2,27 m sur l'objectif
        // plus large de l'audit), identique pour tous les objets coupés
        assertEquals(3.01f, constant, 0.01f)
        val meter = DistanceMeter().apply { this.pose = pose }
        assertTrue(meter.measure(width, height, listOf(detection("personne", cutBottomBox()))).isEmpty())
    }

    @Test
    fun `bas de boite coupe suivi par piste ne rend rien non plus`() {
        // Une piste existante ne ressuscite pas l'objet : la mesure reste inconnue
        // (les prédictions Kalman, elles, couvrent l'oubli côté overlay).
        val meter = DistanceMeter()
        val whole = meter.measure(width, height, listOf(detection("personne", boxFor(1.70f, 2.5f))), listOf(7))
        assertEquals(1, whole.size)
        assertTrue(meter.measure(width, height, listOf(detection("personne", cutBottomBox())), listOf(7)).isEmpty())
    }

    @Test
    fun `pieton a 1 m dont les pieds sortent du cadre n'est plus annonce a 2 27 m`() {
        // Un piéton à 1 m : 658 px de haut pour 384 px d'image, le bas est coupé.
        val box = listOf(100f, 50f, 200f, height.toFloat())
        assertTrue(Pinhole.isCutBottom(box, height))
        assertFalse("la tête reste dans le cadre : seule la coupe basse est en cause", Pinhole.isCutTop(box))
        val meter = DistanceMeter().apply { pose = CameraPose(1.5f, -0.349f) }
        val readings = meter.measure(width, height, listOf(detection("personne", box)))
        // ni 2,27 m (tangage 0) ni 6,29 m (tangage −20° sur l'objectif de l'audit)
        assertTrue("distance annoncée pour un piéton à 1 m : $readings", readings.isEmpty())
    }

    @Test
    fun `boite entiere garde sa mesure et n'est pas un repli`() {
        val box = listOf(100f, top, 200f, top + 200f)
        assertFalse(Pinhole.isVerticallyCut(box, height))
        val reading = DistanceMeter().measure(width, height, listOf(detection("personne", box))).single()
        assertEquals(3.29f, reading.meters, 0.01f)
        assertEquals(reading.meters, reading.rawMeters, 0.001f)
        assertFalse(reading.cutFallback)
    }

    @Test
    fun `boite coupee en haut est ecartee`() {
        val box = listOf(100f, 0f, 200f, 200f)
        assertTrue(Pinhole.isCutTop(box))
        assertTrue(DistanceMeter().measure(width, height, listOf(detection("personne", box))).isEmpty())
        // même avec une pose : la tête hors cadre ne se mesure pas
        val posed = DistanceMeter().apply { pose = CameraPose(1.5f) }
        assertTrue(posed.measure(width, height, listOf(detection("personne", box))).isEmpty())
    }

    @Test
    fun `pieds visibles dans le cadre gardent la fusion stenope sol`() {
        val box = listOf(100f, top, 200f, 378f)
        assertFalse(Pinhole.isCutBottom(box, height))
        val pose = CameraPose(1.5f)
        val meter = DistanceMeter().apply { this.pose = pose }
        val reading = meter.measure(width, height, listOf(detection("personne", box))).single()
        val standing = Pinhole.distanceMeters(box, 1.70f, focal)!!
        val ground = GroundPlane.forwardMeters(378f, pose, focal, principalY, height)!!
        val expected = DistanceFusion.combine(
            standing,
            ground,
            KnownSizes.fusionWeight("personne"),
            GroundWeight.of(GroundPlane.depressionRad(378f, pose, focal, principalY)),
        )!!
        assertEquals(expected, reading.rawMeters, 0.001f)
        assertTrue(reading.rawMeters in standing..ground)
        assertFalse(reading.cutFallback)
    }
}
