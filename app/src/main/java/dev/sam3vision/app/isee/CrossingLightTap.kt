package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.LampLens
import dev.sam3vision.core.crossing.PedLampRank
import dev.sam3vision.core.crossing.PedLightGate
import dev.sam3vision.core.exposure.ClipStats
import dev.sam3vision.core.model.LiveFrame

/**
 * Le feu d'une frame : **où** il est ([box], normalisée 0..1) et **ce qu'il dit**
 * ([state]). Les deux viennent de la même lecture, donc l'étiquette posée sur la
 * vidéo ne peut pas désigner un autre feu que celui qui a été classifié.
 */
data class CrossingLight(
    val box: List<Float>?,
    val state: CrossingState,
    /** Rang de la boîte retenue, `null` sans mesure (repli poteau) : aucune tenue possible. */
    val rank: PedLampRank.Ranked? = null,
    /** Rouge franc lu sur [box]. Vrai sans mesure : un rouge non mesuré reste un rouge (sûr). */
    val redProven: Boolean = true,
)

/**
 * Prise crop : appelée avec la boîte lue, le crop classifié et le verdict.
 * Sert la collecte ([LightCropLogger]) ; `null` = pas de collecte.
 */
internal typealias CrossingCropTap = (box: List<Float>, crop: CrossingFrame, verdict: CrossingState) -> Unit

/** Lecture d'une frame : la boîte retenue, le verdict et l'écrêtage du crop relu. */
private data class LightRead(
    val box: List<Float>?,
    val state: CrossingState,
    val clip: ClipStats,
    val rank: PedLampRank.Ranked? = null,
    val redProven: Boolean = true,
)

/**
 * Lecture du feu sur une frame : boîtes SAM candidates (tête puis caissons),
 * crop JPEG classifié en local puis filtré par la porte anti-confusion
 * ([PedLightGate], 100 % embarqué, aucun réseau). Tête sans lampe vue : le
 * caisson est relu (mauvaise lentille) ; pas de feu en vue ou image
 * illisible ⇒ [CrossingState.Red] — binaire, tout non-vert attend (ordre user du 31/10),
 * l'équivalent sûr de l'ancien « Doute » : jamais de vert sans boîte lue puis
 * crop classifié vert. Seuils inchangés (arbre + porte intacts).
 *
 * Appelée **une fois par frame** par la sonde : le décodage du crop est le poste
 * coûteux, le refaire pour l'affichage doublerait le travail.
 *
 * Publie au passage l'écrêtage du crop ([LightExposureSignal]) : c'est la seule mesure
 * d'exposition **de la lampe** de l'app (une lampe fait 1 à 10 % des pixels, la moyenne
 * globale du gouverneur EV ne la voit pas). Publication **après** la lecture, quel que
 * soit le chemin, pour que le capteur ne pilote jamais sur une mesure périmée.
 *
 * Sans boîte de feu : repli haut-de-poteau ([poleFallbackOf]) — le feu
 * piéton est sur poteau, et SAM voit parfois le fût sans le boîtier.
 */
internal fun crossingLightOf(
    frame: LiveFrame,
    jpegB64: String,
    onCrop: CrossingCropTap? = null,
): CrossingLight {
    val read = readLightOf(frame, jpegB64, onCrop)
    LightExposureSignal.offer(read.box, read.clip)
    return CrossingLight(read.box, read.state, read.rank, read.redProven)
}

/** Corps de [crossingLightOf], sans la publication (une seule sortie par frame au-dessus). */
private fun readLightOf(frame: LiveFrame, jpegB64: String, onCrop: CrossingCropTap?): LightRead {
    val boxes = CrossingPrompts.selectLightBoxes(frame.objects, frame.width, frame.height)
    if (boxes.isEmpty()) return poleFallbackOf(frame, jpegB64, onCrop)
    val signLike = CrossingPrompts.signLikeBoxes(frame.objects, frame.width, frame.height)
    var best: Candidate? = null
    var last: Candidate? = null
    for (box in boxes) {
        val crop = CrossingPixels.frame(jpegB64, box)
            ?: return LightRead(box, CrossingState.Red, ClipStats.NONE)
        val flux = LampLens.flux(crop.argb, crop.width, crop.height)
        val candidate = Candidate(
            box = box,
            crop = crop,
            verdict = PedLightGate.verdict(crop.argb, crop.width, crop.height),
            rank = PedLampRank.rank(box, crop.argb, crop.width, crop.height, lampFlux(crop)),
        )
        // Boîte faible dans un panneau mieux noté : sans lampe, c'est le panneau
        // (`SignLightVeto`) — elle ne compte plus comme un feu lu, une lampe
        // allumée passe au-dessus juste après.
        if (box !in signLike) last = candidate
        if (flux >= LampLens.MIN_FLUX && (best == null || PedLampRank.better(candidate.rank, best.rank))) {
            best = candidate
        }
    }
    // La bonne boîte est le FEU PIETON, pas la mieux notée par SAM (le score
    // allait à la lanterne éteinte, 0,84 contre 0,77 sur le vert allumé — A4
    // §9.1) ni la plus brillante quand un bout de tricolore est en vue : vue
    // entière avant bout coupé, bonhomme avant disque, flux en cas d'égalité
    // ([PedLampRank]). Trois décodages au plus, cas rare (deux caissons).
    val pick = best ?: last ?: return poleFallbackOf(frame, jpegB64, onCrop)
    // Collecte (voir LightCropLogger) : seuls les crops classifiés partent,
    // jamais les frames sans boîte ni les échecs de décodage.
    if (onCrop != null) onCrop(pick.box, pick.crop, pick.verdict)
    val red = PedLightGate.hasRedLamp(pick.crop.argb, pick.crop.width, pick.crop.height)
    return LightRead(pick.box, pick.verdict, pick.crop.clip, pick.rank, red)
}

/** Flux de la seule lampe de [crop] : le rang compare des feux, pas leurs caissons. */
private fun lampFlux(crop: CrossingFrame): Float =
    LampLens.flux(PedLampRank.lampOnly(crop.argb), crop.width, crop.height)

/** Boîte candidate déjà croppée : verdict de la porte et rang du feu piéton. */
private class Candidate(
    val box: List<Float>,
    val crop: CrossingFrame,
    val verdict: CrossingState,
    val rank: PedLampRank.Ranked,
)

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
): LightRead {
    val poleBox = CrossingPrompts.selectPoleTopBox(frame.objects, frame.width, frame.height)
        ?: return LightRead(null, CrossingState.Red, ClipStats.NONE)
    val crop = CrossingPixels.frame(jpegB64, poleBox)
        ?: return LightRead(null, CrossingState.Red, ClipStats.NONE)
    if (!PedLightGate.hasLampSignal(crop.argb, crop.width, crop.height)) {
        return LightRead(null, CrossingState.Red, ClipStats.NONE)
    }
    val verdict = PedLightGate.verdict(crop.argb, crop.width, crop.height)
    if (onCrop != null) onCrop(poleBox, crop, verdict)
    return LightRead(poleBox, verdict, crop.clip, redProven = PedLightGate.hasRedLamp(crop.argb, crop.width, crop.height))
}
