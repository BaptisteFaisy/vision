package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.PedLightGate
import dev.sam3vision.core.model.LiveFrame

/**
 * Prise crop : appelée avec la boîte lue, le crop classifié et le verdict.
 * Sert la collecte ([LightCropLogger]) ; `null` = pas de collecte.
 */
internal typealias CrossingCropTap = (box: List<Float>, crop: CrossingFrame, verdict: CrossingState) -> Unit

/**
 * Lecture du feu sur une frame : boîte SAM au meilleur score, crop JPEG classifié
 * en local puis filtré par la porte anti-confusion ([PedLightGate], 100 %
 * embarqué, aucun réseau). Pas de feu en vue ou image illisible ⇒
 * [CrossingState.Red] — binaire, tout non-vert attend (ordre user du 31/10),
 * l'équivalent sûr de l'ancien « Doute » : jamais de vert sans boîte lue puis
 * crop classifié vert. Seuils inchangés (arbre + porte intacts).
 *
 * Appelée **une fois par frame** par la sonde : le décodage du crop est le poste
 * coûteux, le refaire pour l'affichage doublerait le travail.
 *
 * Sans boîte de feu : repli haut-de-poteau ([poleFallbackOf]) — le feu
 * piéton est sur poteau, et SAM voit parfois le fût sans le boîtier.
 */
internal fun crossingLightOf(
    frame: LiveFrame,
    jpegB64: String,
    onCrop: CrossingCropTap? = null,
): CrossingLight {
    val box = CrossingPrompts.selectLightBox(frame.objects, frame.width, frame.height)
        ?: return poleFallbackOf(frame, jpegB64, onCrop)
    val crop = CrossingPixels.frame(jpegB64, box)
        ?: return CrossingLight(box, CrossingState.Red)
    val verdict = PedLightGate.verdict(crop.argb, crop.width, crop.height)
    // Collecte (voir LightCropLogger) : seuls les crops classifiés partent,
    // jamais les frames sans boîte ni les échecs de décodage.
    if (onCrop != null) onCrop(box, crop, verdict)
    return CrossingLight(box, verdict)
}

/**
 * Repli haut-de-poteau : le carré en haut du meilleur poteau part au
 * classifieur comme une boîte de feu (même collecte). Sans poteau, sans
 * décodage ou sans lampe allumée ([PedLightGate.hasLampSignal]) : boîte
 * nulle et rouge — un poteau sans feu ne doit pas tuer la branche
 * sans-feu (`NoLightGate` compte les boîtes nulles).
 */
private fun poleFallbackOf(
    frame: LiveFrame,
    jpegB64: String,
    onCrop: CrossingCropTap?,
): CrossingLight {
    val poleBox = CrossingPrompts.selectPoleTopBox(frame.objects, frame.width, frame.height)
        ?: return CrossingLight(null, CrossingState.Red)
    val crop = CrossingPixels.frame(jpegB64, poleBox)
        ?: return CrossingLight(null, CrossingState.Red)
    if (!PedLightGate.hasLampSignal(crop.argb, crop.width, crop.height)) {
        return CrossingLight(null, CrossingState.Red)
    }
    val verdict = PedLightGate.verdict(crop.argb, crop.width, crop.height)
    if (onCrop != null) onCrop(poleBox, crop, verdict)
    return CrossingLight(poleBox, verdict)
}
