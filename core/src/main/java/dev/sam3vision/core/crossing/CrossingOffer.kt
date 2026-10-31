package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.model.SegObject

/**
 * Détection d'un passage piéton proche pour le guidage latéral
 * ([CrossingOffer.seesNearCrosswalk], ≤ 2 m mesurés ou boîte prouvée sous
 * les pieds) : le mot doux (« Gauche. », « Droite. », « Tout droit. ») et
 * l'arrivée (« Sur le passage. »). Le mode Traversée ne s'arme plus seul
 * (ordre user du 31/10) : la proposition et sa mémoire d'armement sont
 * supprimées ; seule la proximité prouvée reste, plus la mémoire de perte
 * ([CrossingLossGate], décision de sortie).
 *
 * Le concept `crosswalk` est déjà l'un des douze prompts par défaut du direct
 * (`LivePrompts.default`) : SAM le détecte donc déjà. Rien à ajouter côté
 * serveur, rien à payer en plus.
 */
object CrossingOffer {

    /** Score minimal d'un objet pour compter comme passage piéton. */
    const val MIN_SCORE = 0.40f

    /** Au-delà (m), un passage vu ne compte plus comme proche (ordre user 30/10). */
    const val NEAR_MAX_METERS = 2.0f

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

    /**
     * Vrai si la frame contient un passage piéton assez sûr **et** assez près :
     * mesuré à [maxMeters] au plus ([metersOf], profondeur calibrée ou sol —
     * jamais de sténopé, `crosswalk` est DEPTH_ONLY), ou, sans mesure valide,
     * prouvé sous les pieds par sa boîte ([CrossingGuidance.touchesBottom],
     * [CrossingGuidance.footClose]). Sans preuve de proximité : silence
     * (fail-closed) — le guidage ne parle jamais sur un passage lointain ou
     * non mesuré.
     */
    fun seesNearCrosswalk(
        objects: List<SegObject>,
        metersOf: (SegObject) -> Float?,
        frameH: Int,
        maxMeters: Float = NEAR_MAX_METERS,
        minScore: Float = MIN_SCORE,
    ): Boolean = objects.any { obj ->
        if (obj.score < minScore || !isCrosswalkLabel(obj.label)) return@any false
        val meters = metersOf(obj)
        if (meters != null && meters.isFinite() && meters > 0f) return@any meters <= maxMeters
        CrossingGuidance.touchesBottom(obj.box, frameH) ||
            (obj.box.size >= 4 && CrossingGuidance.footClose(obj.box[3], frameH))
    }
}

/**
 * Mémoire de perte du passage en mode auto : le mode Traversée armé par la
 * vision ne retombe pas à la première frame vide (une détection qui clignote
 * n'est pas un départ), il faut [MISS_FRAMES] frames publiées de suite sans
 * passage. Une seule réapparition remet le compteur à zéro.
 *
 * Pur et testé en JVM : l'instant passe en argument, aucune horloge implicite.
 * [onFrame] compte les frames vides, [noteFrame]+[expired] bornent l'absence
 * par l'horloge (le réseau figé ne coince plus le mode).
 */
class CrossingLossGate(
    private val missFrames: Int = MISS_FRAMES,
    private val exitTimeoutMs: Long = EXIT_TIMEOUT_MS,
) {

    private var misses = 0
    private var lastVisibleAtMs = NO_SEEN

    /**
     * Frame publiée : faux quand le mode auto doit retomber ([visible] faux
     * [missFrames] fois de suite), vrai sinon. [visible] vrai remet le
     * compteur à zéro.
     */
    fun onFrame(visible: Boolean): Boolean {
        misses = if (visible) 0 else misses + 1
        return misses < missFrames
    }

    /**
     * Frame publiée à [nowMs] : mémorise la dernière scène vue (la première
     * frame cale l'horloge même vide — un mode armé sur du vide expire).
     */
    fun noteFrame(visible: Boolean, nowMs: Long) {
        if (visible || lastVisibleAtMs == NO_SEEN) lastVisibleAtMs = nowMs
    }

    /** Vrai quand la scène manque depuis [exitTimeoutMs] ou plus. */
    fun expired(nowMs: Long): Boolean {
        val last = lastVisibleAtMs
        if (last == NO_SEEN) return false
        return nowMs - last >= exitTimeoutMs
    }

    /** Nouvelle séance, ou mode coupé : le compteur repart de zéro. */
    fun reset() {
        misses = 0
        lastVisibleAtMs = NO_SEEN
    }

    companion object {
        /** Frames sans passage de suite avant le retour au mode normal. */
        const val MISS_FRAMES = 4

        /** Absence de scène au-delà de laquelle le mode retombe (ms). */
        const val EXIT_TIMEOUT_MS = 4_000L

        /** Réveil du guetteur de sortie entre deux frames publiées (ms). */
        const val TICK_MS = 500L

        /** Aucune frame mémorisée : l'horloge seule ne sort pas. */
        private const val NO_SEEN = Long.MIN_VALUE
    }
}
