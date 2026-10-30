package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.model.SegObject

/**
 * Détection d'un passage piéton dans le champ : le mode Traversée s'arme
 * **automatiquement** ([CrossingOfferGate], 3 frames sur 5) et retombe seul
 * quand le passage disparaît ([CrossingLossGate], 4 frames vides de suite).
 *
 * Le concept `crosswalk` est déjà l'un des douze prompts par défaut du direct
 * (`LivePrompts.default`) : SAM le détecte donc déjà, et jusqu'ici l'information
 * n'était affichée nulle part. Rien à ajouter côté serveur, rien à payer en plus.
 *
 * La règle reste volontairement lente : une détection isolée est du bruit, et
 * un refus manuel (bouton, ordre vocal) se tait une minute. Mais l'utilisateur
 * ne décide plus à chaque passage — il reprend la main en coupant le mode.
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

/**
 * Mémoire de perte du passage en mode auto : le mode Traversée armé par la
 * vision ne retombe pas à la première frame vide (une détection qui clignote
 * n'est pas un départ), il faut [MISS_FRAMES] frames publiées de suite sans
 * passage. Une seule réapparition remet le compteur à zéro.
 *
 * Pur et testé en JVM : aucune horloge, l'appelant compte des frames.
 */
class CrossingLossGate(
    private val missFrames: Int = MISS_FRAMES,
) {

    private var misses = 0

    /**
     * Frame publiée : faux quand le mode auto doit retomber ([visible] faux
     * [missFrames] fois de suite), vrai sinon. [visible] vrai remet le
     * compteur à zéro.
     */
    fun onFrame(visible: Boolean): Boolean {
        misses = if (visible) 0 else misses + 1
        return misses < missFrames
    }

    /** Nouvelle séance, ou mode coupé : le compteur repart de zéro. */
    fun reset() {
        misses = 0
    }

    companion object {
        /** Frames sans passage de suite avant le retour au mode normal. */
        const val MISS_FRAMES = 4
    }
}
