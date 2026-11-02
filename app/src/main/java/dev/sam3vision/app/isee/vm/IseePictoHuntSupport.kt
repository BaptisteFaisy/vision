package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.StoredPicto
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.picto.FindIndoorTarget
import dev.sam3vision.core.picto.PictoHit
import dev.sam3vision.core.picto.PictoVoiceOrder

/**
 * Le label detecte vise-t-il la chasse, dans un sens ou dans l'autre
 * ("keys" <-> "cles", "remote" <-> "tv remote") ? Egalite canonique, meme
 * cible dedans (pont FR-EN), ou regle vocale (singulier/pluriel, abrege oral).
 */
internal fun pictoMatchesHunt(hunt: String, label: String): Boolean {
    if (KnownSizes.canonical(hunt) == KnownSizes.canonical(label)) return true
    // Pont FR-EN : "cles" chasse les detections "keys", et l'inverse.
    val hunted = FindIndoorTarget.fromSpoken(hunt)
    if (hunted != null && hunted == FindIndoorTarget.fromSpoken(label)) return true
    return PictoVoiceOrder.matches(label, hunt) || PictoVoiceOrder.matches(hunt, label)
}

/**
 * Ancrage d'un repere livre pendant sa chasse : (hit, cadran), `null` si
 * bouton (sans nom), chasse annulee ou boite invalide. Pur hors [PositionText].
 */
internal fun huntDeliveryAnchor(
    hunt: String?,
    label: String,
    anchor: List<Float>?,
    score: Float,
    armed: StoredPicto?,
): Pair<PictoHit, Int>? {
    if (label.isEmpty() || anchor == null || anchor.size < 4) return null
    if (hunt == null || !pictoMatchesHunt(hunt, label)) return null
    if (armed == null) return null
    val clock = PositionText.clockHour((anchor[0] + anchor[2]) / 2f)
    val hit = PictoHit(armed.id, armed.label, anchor[0], anchor[1], anchor[2] - anchor[0], anchor[3] - anchor[1], score)
    return hit to clock
}

/**
 * SAM autorise-t-il l'affichage du gabarit nommé [pictoLabel] ? Un picto
 * nommé comme un masque indoor (clés, lunettes, boîtier… — les 10
 * concepts SAM dedans) ne s'affiche que si SAM le voit dans [samLabels]
 * (pont FR-EN : « cles » vaut « keys ») — le gabarit seul ne montre
 * jamais un objet que SAM ne voit pas, même si le motif matche. Un
 * picto hors indoor (« Object » du bouton) reste gabarit seul. Pur.
 */
internal fun samShowsPicto(pictoLabel: String, samLabels: Set<String>): Boolean {
    if (FindIndoorTarget.fromSpoken(pictoLabel) == null) return true
    return samLabels.any { pictoMatchesHunt(pictoLabel, it) }
}
