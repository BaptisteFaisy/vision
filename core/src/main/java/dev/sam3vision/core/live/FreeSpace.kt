package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.LensSpec

/**
 * Place latérale libre de part et d'autre de l'axe marché, en mètres.
 *
 * Le guidage dit « décale-toi de 50 cm » : encore faut-il savoir de combien on
 * **peut** se décaler. Un trottoir de 1,80 m laisse 0,90 m de chaque côté ; un
 * mur à 0,50 m à droite ramène ce côté à 0,20 m (marge de bord déduite) et
 * rend le décalage à droite impossible. Le chiffre parlé se borne alors à ce
 * que le terrain permet, sans jamais promettre un pas dans le mur.
 *
 * Mesuré sur les **bords vus** (bordures, murs, clôtures : les labels que
 * le modèle segmente comme limite de l'espace marchable), pur et
 * testé en JVM. L'inconnu n'est pas le vide : sans bord mesuré d'un côté, on
 * suppose le trottoir par défaut ([DEFAULT_HALF_M]) — la prudence du guidage
 * vient des obstacles, pas d'un bord qu'on n'a pas vu.
 */
data class FreeSpace(val leftM: Float, val rightM: Float) {

    /** Place libre totale (m) : les deux côtés additionnés, l'axe au milieu. */
    val totalM: Float get() = leftM + rightM

    /**
     * Côté qui a le plus de place : celui vers lequel se décaler. Égalité
     * (trottoir mesuré symétrique, ou rien de mesuré) ⇒ [GuidanceSide.Right] :
     * une règle fixe, jamais un tirage — le guidage doit être le même d'une
     * frame à l'autre.
     */
    val side: GuidanceSide get() = if (rightM >= leftM) GuidanceSide.Right else GuidanceSide.Left

    /** Place disponible de ce côté (m). */
    fun room(side: GuidanceSide): Float = when (side) {
        GuidanceSide.Left -> leftM
        GuidanceSide.Right -> rightM
    }

    /** Vrai si [meters] de décalage tiennent entièrement de ce côté. */
    fun canShift(side: GuidanceSide, meters: Float): Boolean = room(side) >= meters

    companion object {
        /** Demi-trottoir par défaut (m) : trottoir de 1,80 m, sans bord mesuré. */
        const val DEFAULT_HALF_M = 0.9f

        /** Marge de sécurité au bord (m) : on ne marche pas sur la bordure. */
        const val EDGE_MARGIN_M = 0.3f

        /** Au-delà, on ne croit plus la mesure (m) : un bord « très loin » n'informe pas. */
        const val MAX_HALF_M = 3f

        /**
         * Labels de **bord** (canoniques, comme [KnownSizes.canonical]) : ce qui
         * clôt l'espace marchable. `sidewalk`, `pavement`, `footpath` et
         * `crosswalk` n'y sont pas : ce sont des sols marchables, pas des bords
         * — leur segmentation désigne la surface où l'on marche, la borner
         * reviendrait à s'interdire le trottoir lui-même (même partage que
         * `CurbConcepts.isGround`).
         */
        private val EDGES = setOf("curb", "wall", "fence", "hedge", "barrier")

        /** Trottoir supposé : rien de mesuré, des deux côtés la place par défaut. */
        fun unknown(): FreeSpace = FreeSpace(DEFAULT_HALF_M, DEFAULT_HALF_M)

        /**
         * Estime la place libre depuis les bords vus : bordure, mur, clôture.
         *
         * Chaque bord mesuré donne sa distance **latérale** à l'axe (même
         * projection que `LateralNudge.lateralMeters`, [hfovDeg] calibre la
         * largeur de l'image en mètres) ; l'écart signé place le bord à gauche
         * ou à droite. Le bord le plus proche d'un côté fait foi (c'est lui qui
         * limite le pas), [EDGE_MARGIN_M] est déduit, le résultat est borné à
         * `[0, MAX_HALF_M]`. Un bord non mesuré (distance `null`, non finie ou
         * ≤ 0) n'informe pas : un bord qu'on n'a pas mesuré n'est pas un bord
         * collé. Aucun bord mesuré d'un côté ⇒ [DEFAULT_HALF_M] de ce côté.
         *
         * Un bord pile sur l'axe (écart nul) compte **à droite**, par la même
         * convention d'égalité que [side] : il n'y a alors aucune place de ce
         * côté, et le guidage propose la gauche.
         *
         * Pur, un seul parcours de la liste ([labels]), sans allocation.
         */
        fun of(labels: List<PlacedLabel>, hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG): FreeSpace {
            var nearestLeft = Float.POSITIVE_INFINITY
            var nearestRight = Float.POSITIVE_INFINITY
            for (label in labels) {
                if (!isEdge(label.label)) continue
                val lateral = lateralMeters(label, hfovDeg) ?: continue
                if (lateral < 0f) nearestLeft = minOf(nearestLeft, -lateral)
                else nearestRight = minOf(nearestRight, lateral)
            }
            return FreeSpace(roomOf(nearestLeft), roomOf(nearestRight))
        }

        /** Place utile depuis la distance du bord le plus proche : marge déduite, bornée. */
        private fun roomOf(nearestM: Float): Float =
            if (!nearestM.isFinite()) DEFAULT_HALF_M
            else (nearestM - EDGE_MARGIN_M).coerceIn(0f, MAX_HALF_M)

        /** Vrai si [label] désigne un bord (casse, accents, pluriel simple neutralisés). */
        private fun isEdge(label: String): Boolean {
            val key = KnownSizes.canonical(label)
            if (key.isEmpty()) return false
            if (key in EDGES) return true
            return key.endsWith("s") && key.length > 3 && key.dropLast(1) in EDGES
        }

        /**
         * Écart latéral signé du bord (m, négatif à gauche) à sa profondeur :
         * `(centerX − 0,5) × 2 × profondeur × tan(hfov/2)`, la projection du
         * trait du couloir. `null` sans distance exploitable : un bord non
         * mesuré n'informe pas.
         */
        private fun lateralMeters(label: PlacedLabel, hfovDeg: Float): Float? {
            val depth = label.meters?.takeIf { it.isFinite() && it > 0f } ?: return null
            val lateral = (label.centerX - AXIS_MID) * 2f * depth * LensSpec.halfTangent(hfovDeg)
            return lateral.takeIf { it.isFinite() }
        }

        /**
         * Milieu de l'image = axe de marche : la même valeur que
         * `LateralNudgeTracker.SIDE_MID`, une seule définition dans le paquet.
         */
        private val AXIS_MID = LateralNudgeTracker.SIDE_MID
    }
}
