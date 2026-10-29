package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.model.SegObject

/**
 * Proposition de passer en **mode Traversée** quand un passage piéton est dans le
 * champ.
 *
 * Le concept `crosswalk` est déjà l'un des douze prompts par défaut du direct
 * (`LivePrompts.default`) : SAM le détecte donc déjà, et jusqu'ici l'information
 * n'était affichée nulle part. Rien à ajouter côté serveur, rien à payer en plus.
 *
 * La règle est volontairement lente et **non automatique** : voir un passage
 * piéton ne suffit pas à savoir que l'on veut traverser (on longe souvent un
 * carrefour sur des dizaines de mètres). On *propose*, l'utilisateur décide —
 * entrer en mode Traversée change les concepts suivis et coupe le guidage
 * obstacles, ce n'est pas une décision que l'app doit prendre seule.
 */
object CrossingOffer {

    /** Score minimal d'un objet pour compter comme passage piéton. */
    const val MIN_SCORE = 0.40f

    /** Étiquettes acceptées, après canonicalisation (casse, accents, ponctuation). */
    private val LABELS = setOf(
        "crosswalk", "cross walk", "zebra crossing", "zebra crossing", "pedestrian crossing",
        "passage pieton", "passage cloute", "cloutage",
    )

    /** Vrai si [label] (brut, tel que renvoyé par SAM) désigne un passage piéton. */
    fun isCrosswalkLabel(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (LABELS.contains(key)) return true
        return key.split(' ').any { it == "crosswalk" || it == "cloutage" }
    }

    /** Vrai si la frame contient un passage piéton assez sûr. */
    fun seesCrosswalk(objects: List<SegObject>, minScore: Float = MIN_SCORE): Boolean =
        objects.any { it.score >= minScore && isCrosswalkLabel(it.label) }
}

/**
 * Mémoire courte de la proposition : il faut voir le passage sur plusieurs frames
 * avant de proposer (une détection isolée est du bruit), et un refus se tait
 * pendant [quietMs].
 *
 * Pur et testé en JVM : l'instant est toujours passé en argument.
 */
class CrossingOfferGate(
    private val window: Int = WINDOW,
    private val minFrames: Int = MIN_FRAMES,
    private val quietMs: Long = QUIET_MS,
) {

    private val recent = ArrayDeque<Boolean>()
    private var quietUntilMs = 0L

    /**
     * Frame publiée : vrai si l'app doit proposer le mode Traversée. [traversing]
     * vrai = le mode est déjà actif, il n'y a plus rien à proposer.
     */
    fun onFrame(objects: List<SegObject>, nowMs: Long, traversing: Boolean = false): Boolean {
        recent.addLast(CrossingOffer.seesCrosswalk(objects))
        while (recent.size > window) recent.removeFirst()
        if (traversing) return false
        if (nowMs < quietUntilMs) return false
        return recent.count { it } >= minFrames
    }

    /** L'utilisateur a coupé le mode : on se tait un moment au lieu de relancer. */
    fun declined(nowMs: Long) {
        quietUntilMs = nowMs + quietMs
        recent.clear()
    }

    /** Nouvelle séance : mémoire et silence effacés. */
    fun reset() {
        recent.clear()
        quietUntilMs = 0L
    }

    companion object {
        /** Fenêtre d'observation, en frames publiées. */
        const val WINDOW = 5

        /** Frames de la fenêtre qui doivent voir le passage. */
        const val MIN_FRAMES = 3

        /** Silence après un refus. */
        const val QUIET_MS = 60_000L
    }
}
