package dev.sam3vision.core.curb

import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Position du trottoir vu caméra : devant (dans le couloir marché — l'utilisateur
 * est dessus ou va dessus), à gauche / à droite (l'utilisateur n'y est plus : le
 * guidage suspend le cap et ordonne le retour), ou absent (scène perçue sans
 * trottoir : doute — consigne prudente, jamais d'ordre vers la chaussée).
 */
enum class SidewalkPosition { Ahead, Left, Right, Absent }

/**
 * Maintien actif sur le trottoir : la boucle fermée du guidage piéton.
 *
 * Le trottoir est déjà suivi par SAM (`sidewalk` dans [dev.sam3vision.core.live.LivePrompts]) ;
 * ici on s'en sert pour ramener l'utilisateur dessus quand il n'y est plus (dérive,
 * contournement d'obstacle qui fait descendre du trottoir). Règle de priorité, lue
 * avec [dev.sam3vision.core.nav.RouteSteeringFusion] : sécurité obstacle (STOP/veto,
 * jamais affaiblis) > retour trottoir > cap destination > arrivée.
 *
 * Deux prudences assumées : une scène vide n'est pas une preuve d'absence (SAM
 * muet, trou de frame) — pas de correction, le cap suit son cours ; un trottoir
 * des deux côtés sans distance mesurée, c'est le doute, pas un choix ([Absent]).
 *
 * Pur et testé en JVM : aucune image, aucun Android, ne lève jamais.
 */
object SidewalkKeep {

    /**
     * Position du trottoir dans [labels] (labels placés SAM, sol marchable
     * inclus) : couloir central ([PositionText.SIDE_EDGE], comme
     * [dev.sam3vision.core.live.Guidance]) → [SidewalkPosition.Ahead] ; un seul
     * côté → ce côté ; les deux → le plus proche ([SidewalkPosition.Absent] si
     * aucun n'est mesuré) ; scène perçue sans trottoir → [SidewalkPosition.Absent].
     * Les labels blancs sont ignorés, comme dans le guidage.
     */
    fun positionOf(labels: List<PlacedLabel>): SidewalkPosition {
        val cleaned = labels.filter { it.label.isNotBlank() }
        if (cleaned.isEmpty()) return SidewalkPosition.Ahead
        val walks = cleaned.filter { CurbConcepts.isCurb(it.label) }
        if (walks.isEmpty()) return SidewalkPosition.Absent
        val edge = PositionText.SIDE_EDGE
        if (walks.any { it.centerX >= edge && it.centerX <= 1f - edge }) return SidewalkPosition.Ahead
        val left = walks.any { it.centerX < edge }
        val right = walks.any { it.centerX > 1f - edge }
        if (left && !right) return SidewalkPosition.Left
        if (right && !left) return SidewalkPosition.Right
        return nearestSide(walks) ?: SidewalkPosition.Absent
    }

    /**
     * Cap de retour vers le trottoir : gauche/droite quand il est sur un côté,
     * `null` sinon — devant, rien à corriger ; absent, on ne dévie jamais à
     * l'aveugle (la voix reste prudente mais la flèche garde le cap).
     */
    fun returnCap(position: SidewalkPosition): SteeringCap? = when (position) {
        SidewalkPosition.Left -> SteeringCap.Left
        SidewalkPosition.Right -> SteeringCap.Right
        SidewalkPosition.Ahead, SidewalkPosition.Absent -> null
    }

    /** Signature anti-répétition, toujours française (comme `Guidance.key`). */
    fun key(position: SidewalkPosition): String = when (position) {
        SidewalkPosition.Ahead -> "walk|ahead"
        SidewalkPosition.Left -> "walk|left"
        SidewalkPosition.Right -> "walk|right"
        SidewalkPosition.Absent -> "walk|absent"
    }

    /**
     * Côté du trottoir le plus proche (distance connue), `null` si aucun côté
     * n'est mesuré : le certain prime sur le doute, et deux doutes n'en font
     * pas un choix. À égalité exacte, la gauche gagne (déterministe).
     */
    private fun nearestSide(walks: List<PlacedLabel>): SidewalkPosition? {
        val edge = PositionText.SIDE_EDGE
        val left = walks.filter { it.centerX < edge }.mapNotNull { known(it.meters) }.minOrNull()
        val right = walks.filter { it.centerX > 1f - edge }.mapNotNull { known(it.meters) }.minOrNull()
        if (left == null && right == null) return null
        if (right == null || (left != null && left <= right)) return SidewalkPosition.Left
        return SidewalkPosition.Right
    }

    /** Distance mesurable, `null` sinon (inconnue, infinie ou négative). */
    private fun known(meters: Float?): Float? = meters?.takeIf { it.isFinite() && it > 0f }
}

/**
 * Consignes de retour au trottoir, FR/EN comme les positions ([PositionText]).
 * Devant : `null` (rien à corriger, le cap suit son cours). Sur un côté : ordre
 * de retour (« Remonte sur le trottoir, à gauche. »). Absent : consigne prudente
 * (« Trottoir introuvable, avancez lentement. ») — on ne guide jamais à l'aveugle.
 */
object SidewalkText {

    /** Consigne pour [position], `null` quand il n'y a rien à corriger. */
    fun phrase(position: SidewalkPosition, language: SpeechLanguage = SpeechLanguage.Fr): String? =
        when (position) {
            SidewalkPosition.Ahead -> null
            SidewalkPosition.Left -> when (language) {
                SpeechLanguage.Fr -> "Remonte sur le trottoir, à gauche."
                SpeechLanguage.En -> "Step back onto the sidewalk, to the left."
            }
            SidewalkPosition.Right -> when (language) {
                SpeechLanguage.Fr -> "Remonte sur le trottoir, à droite."
                SpeechLanguage.En -> "Step back onto the sidewalk, to the right."
            }
            SidewalkPosition.Absent -> when (language) {
                SpeechLanguage.Fr -> "Trottoir introuvable, avancez lentement."
                SpeechLanguage.En -> "Sidewalk not visible, walk slowly."
            }
        }
}
