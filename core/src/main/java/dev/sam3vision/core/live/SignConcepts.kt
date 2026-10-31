package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concept panneau : ce que SAM suit en preset extérieur (`LivePrompts.outdoor`
 * suit « sign ») et que le guidage obstacles doit savoir ignorer quand c'est
 * hors de portée de tête.
 *
 * Une rue commerçante, c'est dix enseignes par vitrine : sans filtre, chacune
 * au centre bloque le couloir et la voix bavarde à chaque pas. Or un panneau
 * haut ne se percute pas — on passe dessous. Règle ([isHighSign]) : un
 * panneau dont le centre est au-dessus de [HEAD_TOP] et mesuré dans
 * [HEAD_RANGE_M] sort du couloir ([Guidance.decide] l'ignore comme le sol).
 * Un panneau à hauteur d'homme (centre sous [HEAD_TOP]) bloque toujours :
 * c'est un obstacle à contourner, annoncé comme les autres.
 *
 * Distances : « panneau »/« sign » sont au sténopé ([KnownSizes], 2,20 m) —
 * les enseignes sans hauteur typique restent non mesurées donc gardées, par
 * prudence (même doctrine que le couloir : l'inconnu bloque). Seuils
 * provisoires, à calibrer au téléphone (corpus § 14 de `docs/VOIX-DANGER.md`).
 */
object SignConcepts {

    /**
     * Haut de la zone tête : un panneau centré au-dessus (coordonée
     * normalisée, 0 = haut de l'image) est hors de portée de tête —
     * téléphone tenu à hauteur de poitrine, tangage neutre. Valeur provisoire,
     * non mesurée : à calibrer au téléphone (corpus § 14 de `docs/VOIX-DANGER.md`).
     */
    const val HEAD_TOP = 0.22f

    /**
     * Portée du filtre tête : au-delà, un haut de l'image ne prouve plus
     * rien (à 10 m, tout est près de l'horizon) — le panneau est gardé.
     */
    const val HEAD_RANGE_M = 4.0f

    private val KEYS = setOf(
        "sign", "signboard", "billboard", "panel", "poster", "placard",
        "panneau", "enseigne", "affiche", "pancarte", "ecriteau",
    )

    /**
     * Vrai si [label] désigne un panneau ou une enseigne (casse, accents,
     * pluriel simple neutralisés). Les composés du backend (« traffic
     * sign », « stop sign », « no entry sign » —
     * `server/backend/concepts.py`) sont reconnus au mot : un seul mot
     * connu suffit. « traffic light » et « pedestrian traffic light »
     * restent des non-panneaux (le feu ne se filtre jamais).
     */
    fun isSign(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in KEYS) return true
        if (key.endsWith("s") && key.length > 3 && key.dropLast(1) in KEYS) return true
        return key.split(' ').any { it in KEYS }
    }

    /**
     * Panneau haut à ignorer : concept panneau ([isSign]), centre au-dessus
     * de [HEAD_TOP] ([HEAD_TOP] exclu : à la frontière, on garde), mesuré
     * dans [HEAD_RANGE_M]. Distance inconnue ou non finie ⇒ gardé (`false`) :
     * sans mesure, la prudence bloque — le filtre ne statue que sur du
     * mesuré. Un non-panneau n'est jamais filtré, même haut et proche.
     */
    fun isHighSign(label: String, centerY: Float, meters: Float?): Boolean {
        if (!isSign(label)) return false
        if (!(centerY < HEAD_TOP)) return false
        val depth = meters?.takeIf { it.isFinite() } ?: return false
        return depth <= HEAD_RANGE_M
    }
}
