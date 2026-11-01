package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DeviceType
import dev.sam3vision.app.live.LensOrigin
import dev.sam3vision.app.live.chooseLens
import dev.sam3vision.app.live.chosenLens
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sélection du calibrage : les deux chemins (téléphone, lunettes) coexistent
 * sans se fuir l'un dans l'autre, l'étage le plus mesuré gagne, et le mauvais
 * lens se voit au chiffre.
 *
 * Dépend de D1 (voie lunettes de `chooseLens`, `GlassesLens.kt`) : ne compile
 * pas sans. Complète `GlassesLensTest` (table par modèle) sans le répéter :
 * ici, la sélection et ses conséquences chiffrées.
 */
class GlassesSelectTest {

    private val phoneSensor = LensSpec(73.32f, 58.34f)

    @Test
    fun `les deux reglages forces sont independants`() {
        assertEquals(63f, chosenLens(63f, phoneSensor).horizontalFovDeg, 0f)
        assertEquals(71.5f, chooseLens(71.5f, phoneSensor, null, fromGlasses = true).spec.horizontalFovDeg, 0f)
        assertEquals(73.32f, chosenLens(0f, phoneSensor).horizontalFovDeg, 0f)
    }

    @Test
    fun `mire, profil et repli : l'etage le plus mesure gagne`() {
        val mire = LensSpec(60f, 90f, sensorLandscape = false)
        val withMire = chooseLens(0f, null, mire, fromGlasses = true)
        assertEquals(LensOrigin.SENSOR, withMire.origin)
        assertEquals(mire, withMire.spec)
        // JVM sans SDK : pas de profil — mire inutilisable → repli estimé.
        val fallback = chooseLens(0f, null, LensSpec(0f, 0f), fromGlasses = true)
        assertEquals(LensOrigin.FALLBACK, fallback.origin)
        assertEquals(LensSpec.Glasses, fallback.spec)
        // Le forcé prime sur la mire, sans lire le SDK.
        val forced = chooseLens(69f, null, mire, fromGlasses = true)
        assertEquals(LensOrigin.FORCED, forced.origin)
        assertEquals(69f, forced.spec.horizontalFovDeg, 0f)
    }

    @Test
    fun `le profil flux suit la taille du capteur, vanguard reste borne`() {
        val rayban = glassesStreamLensFor(DeviceType.RAYBAN_META).horizontalFovDeg
        val hstn = glassesStreamLensFor(DeviceType.OAKLEY_META_HSTN).horizontalFovDeg
        val vanguard = glassesStreamLensFor(DeviceType.OAKLEY_META_VANGUARD)
        assertTrue(rayban < hstn && hstn < vanguard.horizontalFovDeg)
        assertEquals(55.41f, hstn, 0.05f)
        assertEquals(78.61f, vanguard.horizontalFovDeg, 0.05f)
        assertTrue(vanguard.horizontalFovDeg in LensSpec.FOV_MIN..LensSpec.FOV_MAX)
        assertTrue(vanguard.verticalFovDeg in LensSpec.FOV_MIN..LensSpec.FOV_MAX)
        assertTrue(vanguard.usable)
    }

    @Test
    fun `medium et high partagent le fov angulaire, la focale suit l'echelle`() {
        val lens = glassesStreamLensFor(DeviceType.RAYBAN_META)
        assertEquals(lens.widthFovDeg(720, 1280), lens.widthFovDeg(504, 896), 0.001f)
        assertEquals(738.7f, lens.focalPx(720, 1280), 1f)
        assertEquals(517.1f, lens.focalPx(504, 896), 1f)
        assertEquals(504f / 720f, lens.focalPx(504, 896) / lens.focalPx(720, 1280), 0.001f)
    }

    @Test
    fun `meme snapshot, deux lens donnent deux distances au ratio des focales`() {
        val phone = chosenLens(0f, phoneSensor)
        val glasses = glassesStreamLensFor(DeviceType.OAKLEY_META_HSTN)
        val box = listOf(40f, 60f, 140f, 260f)
        val phoneF = phone.focalPx(180, 320)
        val glassesF = glasses.focalPx(180, 320)
        assertTrue(phoneF != glassesF)
        val phoneD = Pinhole.distanceMeters(box, 1.70f, phoneF)!!
        val glassesD = Pinhole.distanceMeters(box, 1.70f, glassesF)!!
        assertEquals(phoneF / glassesF, phoneD / glassesD, 0.01f)
    }

    @Test
    fun `le champ force passe par les reglages meme sans sdk`() {
        // JVM sans SDK : modèle null, mais le forcé ne lit pas le SDK.
        val settings = Settings.Default.copy(glassesFovDeg = 69f)
        val chosen = chooseLens(settings.glassesFovDeg, null, null, fromGlasses = true)
        assertEquals(LensOrigin.FORCED, chosen.origin)
        assertEquals(69f, chosen.spec.horizontalFovDeg, 0f)
        assertTrue(chosen.spec.usable)
    }
}
