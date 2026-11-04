package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le juge de l'écart : mètres sur les bords de boîte, une seule réponse pour le
 * couloir, le décalage et le guetteur. Champ portrait du téléphone : à 2 m,
 * toute l'image vaut 2 m de large, le centre d'une boîte n'y dit plus rien.
 */
class ClearPathTest {

    private val hfov = 52.8f

    private fun box(label: String, x0: Float, x1: Float, meters: Float?, cutBottom: Boolean = false) =
        PlacedLabel(label, (x0 + x1) / 2f, 0.5f, meters, x0, x1, cutBottom = cutBottom)

    private fun orderOf(vararg labels: PlacedLabel): AvoidOrder? = ClearPath.view(labels.toList(), hfov).order

    private fun closing(label: PlacedLabel, mps: Float, spanMs: Long = 1_000L) =
        label.copy(closingMps = mps, closingSpanMs = spanMs)

    @Test
    fun `personne a moins d un metre de l axe entre dans le couloir`() {
        val order = orderOf(box("person", 0.60f, 0.75f, 3f))
        assertEquals(GuidanceSide.Left, order?.side)
        assertEquals("person", order?.threat?.label)
    }

    @Test
    fun `personne a plus d un metre de l axe passe sans mot`() {
        assertNull(orderOf(box("person", 0.86f, 0.98f, 3f)))
    }

    @Test
    fun `obstacle au dela de la portee proche ne donne pas d ordre`() {
        assertNull(orderOf(box("pole", 0.49f, 0.51f, 10.5f)))
    }

    @Test
    fun `barriere large dont le centre est a droite sort par le bout court, a gauche`() {
        // Centre à 0,66, hors de la bande : c'est le bord qui coupe l'axe.
        val order = orderOf(box("barrier", 0.42f, 0.90f, 3f))
        assertEquals(GuidanceSide.Left, order?.side)
    }

    @Test
    fun `miroir barriere large sort a droite`() {
        assertEquals(GuidanceSide.Right, orderOf(box("barrier", 0.10f, 0.58f, 3f))?.side)
    }

    @Test
    fun `poteau plein axe passe a droite, le bruit du cadre ne change pas le cote`() {
        for (x in listOf(0.46f, 0.48f, 0.50f, 0.52f, 0.54f)) {
            val swaying = box("pole", x - 0.01f, x + 0.01f, 3f).copy(steadyX = 0.5f)
            assertEquals("x=$x", GuidanceSide.Right, orderOf(swaying)?.side)
        }
    }

    @Test
    fun `poteau nettement a droite passe a gauche`() {
        assertEquals(GuidanceSide.Left, orderOf(box("pole", 0.59f, 0.61f, 3f))?.side)
    }

    @Test
    fun `poteau dans l axe et personne a droite font passer a gauche`() {
        val order = orderOf(box("pole", 0.49f, 0.51f, 3f), box("person", 0.80f, 0.92f, 3f))
        assertEquals(GuidanceSide.Left, order?.side)
    }

    @Test
    fun `mur longe a 80 cm ne fait pas parler`() {
        // Portrait : au bord du cadre, 0,8 m d'écart latéral se voit à 1,6 m.
        assertNull(orderOf(box("wall", 0f, 0.46f, 1.6f)))
    }

    @Test
    fun `mur longe sans mesure ne fait pas parler`() {
        assertNull(orderOf(box("wall", 0f, 0.46f, null, cutBottom = true)))
    }

    @Test
    fun `mur longe a 30 cm ecarte vers l autre cote`() {
        assertEquals(GuidanceSide.Right, orderOf(box("wall", 0f, 0.46f, 0.6f))?.side)
    }

    @Test
    fun `mur longe ne bouche pas le couloir`() {
        val labels = listOf(box("wall", 0f, 0.46f, 1.6f))
        assertFalse(Guidance.decide(labels, hfovDeg = hfov).blocked)
    }

    @Test
    fun `mur de face qui passe l axe entre dans le couloir, sortie du cote libre`() {
        assertEquals(GuidanceSide.Right, orderOf(box("wall", 0f, 0.62f, 3f))?.side)
    }

    @Test
    fun `barriere dont on frole le bout n est pas longee, elle se rapproche`() {
        val frontal = box("barrier", 0.54f, 1f, 3f)
        assertNull("sans approche connue, la boîte seule dit longée", orderOf(frontal))
        assertEquals(GuidanceSide.Left, orderOf(closing(frontal, 1.2f))?.side)
    }

    @Test
    fun `deux mesures proches ne font pas d un mur longe un mur de face`() {
        assertNull(orderOf(closing(box("wall", 0f, 0.46f, 1.6f), 1.5f, spanMs = 200L)))
    }

    @Test
    fun `mur qui ne se rapproche pas tolere le lacet de la marche`() {
        assertNull(orderOf(closing(box("wall", 0f, 0.56f, 1.6f), 0.1f)))
    }

    @Test
    fun `sans mesure le lacet passe, mesure sans approche il reste de face`() {
        assertNull(orderOf(box("wall", 0f, 0.56f, null, cutBottom = true)))
        assertEquals(GuidanceSide.Right, orderOf(box("wall", 0f, 0.56f, 3f))?.side)
    }

    @Test
    fun `corps coupe par le bas sans distance est proche`() {
        val order = orderOf(box("person", 0.70f, 0.95f, null, cutBottom = true))
        assertNotNull(order)
        assertEquals(GuidanceSide.Left, order?.side)
    }

    @Test
    fun `objet sans distance ni coupe ne donne pas d ordre`() {
        assertNull(orderOf(box("pole", 0.49f, 0.51f, null)))
    }

    @Test
    fun `objet sol et panneau haut ne comptent jamais`() {
        assertNull(orderOf(box("sidewalk", 0f, 1f, 2f)))
    }

    @Test
    fun `le plus proche des intrus fait la menace`() {
        val order = orderOf(box("pole", 0.49f, 0.51f, 3.5f), box("person", 0.60f, 0.70f, 2f))
        assertEquals("person", order?.threat?.label)
    }

    @Test
    fun `chemin lointain donne le cote sans ordre`() {
        val view = ClearPath.view(listOf(box("person", 0.57f, 0.62f, 12f)), hfov)
        assertNull(view.order)
        assertEquals(GuidanceSide.Left, view.side)
    }

    @Test
    fun `rien devant, ni ordre ni cote`() {
        val view = ClearPath.view(emptyList(), hfov)
        assertNull(view.order)
        assertNull(view.side)
    }

    @Test
    fun `plan garde 1 m a un poteau quand ca passe`() {
        val path = PeoplePath.plan(listOf(box("pole", 0.49f, 0.51f, 3f)), hfovDeg = hfov)!!
        assertTrue(path.fits)
        assertEquals(1f, path.clearanceM, 0.02f)
    }

    @Test
    fun `plan serre a 60 cm quand 1 m ne passe plus`() {
        val path = PeoplePath.plan(listOf(box("pole", 0.49f, 0.51f, 1.3f)), hfovDeg = hfov)!!
        assertTrue(path.fits)
        assertEquals(0.6f, path.clearanceM, 0.02f)
    }

    @Test
    fun `plan survit a un mur sans distance`() {
        val labels = listOf(box("pole", 0.49f, 0.51f, 3f), box("wall", 0f, 0.3f, null))
        assertTrue(PeoplePath.plan(labels, hfovDeg = hfov)!!.fits)
    }

    @Test
    fun `plan ne devine rien sans distance ni bords`() {
        assertNull(PeoplePath.plan(listOf(PlacedLabel("pole", 0.5f, 0.5f, null)), hfovDeg = hfov))
    }

    @Test
    fun `mur longe ferme son cote au plan`() {
        val labels = listOf(box("wall", 0f, 0.46f, 1.6f), box("pole", 0.53f, 0.55f, 3f))
        assertEquals(GuidanceSide.Right, PeoplePath.plan(labels, hfovDeg = hfov)!!.side)
    }

    @Test
    fun `sortie jamais vers une personne pour tenir le trottoir`() {
        val labels = listOf(box("person", 0.60f, 0.75f, 2.5f))
        val serre = FreeSpace(leftM = 0.1f, rightM = 2f)
        assertEquals(GuidanceSide.Left, ClearPath.view(labels, hfov, serre).order?.side)
    }

    @Test
    fun `l annonceur et la lecture disent le meme cote`() {
        val announcer = GuidanceAnnouncer()
        val labels = listOf(box("barrier", 0.42f, 0.90f, 3f))
        val phrase = announcer.onFrame(0L, labels, hfovDeg = hfov)
        assertEquals(GuidanceSide.Left, announcer.lastView?.side)
        assertEquals(GuidanceWord.Left, Guidance.word(announcer.lastDecision!!))
        assertEquals("left.", phrase)
    }

    @Test
    fun `champ par defaut sert sans focale`() {
        assertNotNull(ClearPath.view(listOf(box("pole", 0.49f, 0.51f, 3f)), LensSpec.DEFAULT_H_FOV_DEG).order)
    }
}
