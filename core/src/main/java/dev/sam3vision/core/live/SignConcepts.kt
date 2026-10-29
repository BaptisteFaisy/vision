package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes

/**
 * Panneaux à hauteur d'humain : ce que SAM doit garder, et quoi taire.
 *
 * `sign` attrape tout — panneau routier à 4 m, pub en hauteur, affiche de
 * magasin. Sans filtre, le guidage bavarde à chaque rue commerçante. Règle
 * (ordre user du 29/10) : on ne garde que le plausible « à hauteur d'humain »,
 * le reste est écarté **avant** le couloir — un panneau écarté ne bloque pas
 * la marche et ne compte pas dans le côté le moins encombré.
 *
 * Deux gardes, sans calibrage téléphone (pas de mesure terrain à ce stade) :
 * - **vertical** : `centerY < HEAD_TOP` = haut du cadre = panneau routier ou
 *   pub en hauteur, jamais un obstacle à hauteur d'homme filmé téléphone en
 *   main. Le tangage de la caméra fait varier l'horizon, le seuil est donc
 *   volontairement haut (on ne tait que l'évident) ;
 * - **profondeur** : au-delà de [HEAD_RANGE_M], un panneau n'est pas un
 *   obstacle du prochain pas — et le couloir l'ignore déjà au-delà de
 *   [Guidance.RANGE_METERS].
 *
 * Distance inconnue = prudence : on garde (mieux vaut une consigne de trop
 * qu'un panneau dans la figure). Pur et testé en JVM.
 */
object SignConcepts {

    /** Au-dessus de cette hauteur normalisée, ce n'est pas à hauteur d'humain. */
    const val HEAD_TOP = 0.22f

    /** Au-delà (m), un panneau n'est pas l'obstacle du prochain pas. */
    const val HEAD_RANGE_M = 4.0f

    /** Étiquettes (canoniques) qui désignent un panneau, voir [KnownSizes.canonical]. */
    private val SIGN_LABELS = setOf(
        "sign",
        "signs",
        "panneau",
        "panneaux",
        "traffic sign",
        "street sign",
        "billboard",
        "panneau publicitaire",
    )

    /** Vrai si [label] (brut, tel que renvoyé par SAM) désigne un panneau. */
    fun isSign(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in SIGN_LABELS) return true
        return key.split(' ').any { it in SIGN_LABELS }
    }

    /**
     * Faut-il garder ce panneau ([centerY] normalisé 0 = haut, [meters] sténopé
     * ou profondeur, `null` = inconnue) ? Non-panneau = toujours gardé (ce
     * filtre ne tranche que les panneaux).
     */
    fun keep(label: String, centerY: Float, meters: Float?): Boolean {
        if (!isSign(label)) return true
        if (centerY < HEAD_TOP) return false
        val depth = meters?.takeIf { it.isFinite() && it > 0f } ?: return true
        return depth <= HEAD_RANGE_M
    }

    /** Garde-fou de [Guidance] : écarte les panneaux hors hauteur d'humain. */
    fun keep(placed: PlacedLabel): Boolean =
        keep(placed.label, placed.centerY, placed.meters)
}
