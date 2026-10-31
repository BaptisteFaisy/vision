package dev.sam3vision.app.glasses

import com.meta.wearable.dat.inputs.types.CapturePressType

/**
 * Recette sans matériel, sortie du contrôleur (garde 500 lignes) : ces
 * délégués ne font que relayer [GlassesMockControls], comportement inchangé.
 */

/** Lunettes simulées : démo sans matériel, et plan B si l'appairage coince. */
fun GlassesController.startMockDevice() = mocks.start()

fun GlassesController.mockPress(type: CapturePressType = CapturePressType.SHORT_PRESS) = mocks.press(type)

/** Recette mains-libres : simule l'appui long (parler) sur le mock. */
fun GlassesController.mockHold() = mocks.press(CapturePressType.HOLD)

/** Recette mains-libres : simule « Hey Meta, ouvre iSee » sur le mock. */
fun GlassesController.mockVoiceLaunch() {
    // Injection côté appareil quand le mock la supporte, sinon simulation
    // côté app (même aval : session puis écoute).
    if (!mocks.tryVoiceLaunch()) onVoiceLaunch()
}

fun GlassesController.stopMockDevice() = mocks.stop()

/**
 * Raccourci de recette : lunettes simulées, puis connexion dès que l'enregistrement le
 * permet (extra adb [EXTRA_GLASSES_MOCK_CONNECT]). Inerte en release — le mock y est
 * indisponible (`mockUnavailable`) et rien ne se connecte tout seul.
 */
fun GlassesController.debugMockConnect() = mocks.debugConnect()
