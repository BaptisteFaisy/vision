package dev.sam3vision.core.crossing

/**
 * Sélection de la boîte d'un feu **piéton** parmi N candidats : choisit l'indice
 * d'un candidat ou **refuse** (`null` = « attendez », jamais un vert).
 *
 * Le défaut mesuré sur 58 séquences de rue (02/11/2026, `~/.scratch/feux-sel/
 * RAPPORT.md`) n'est pas la couleur : **5 faux verts sur 5 viennent de la
 * boîte**, le suivi ayant verrouillé un feu véhicule (`151727`, `152816`,
 * `160205`, `160851`), un marquage vert au sol (`161541`) ou une boîte trop
 * large (`155503`). Ici on ne note donc pas des boîtes, on **écarte** celles
 * dont la lentille n'a pas la géométrie d'un pictogramme piéton.
 *
 * Traits retenus par la mesure (12 503 frames, 1 282 frames de bon vert contre
 * 500 de faux vert, même scène et même couleur des deux côtés) :
 *
 * | trait | bon vert (médiane) | faux vert (médiane) |
 * |---|---|---|
 * | remplissage de la lentille (`aire / boîte`) | 0,41 | **0,74** |
 * | élongation (l/h) | 0,56 | **0,75** |
 * | côté de lentille à 480 px | 45 px | 24 px |
 * | hauteur relative dans la frame | 0,22 | **0,37** |
 * | trous internes de la tache | 6–11 | **0–1** |
 *
 * Une lentille de feu véhicule est un **disque** (π/4 = 0,785 de sa boîte) ; un
 * pictogramme de bonhomme remplit 0,30–0,55 et laisse des **trous** (entre les
 * jambes, sous le bras). Le marquage au sol, lui, est une tache de 6–10 px sans
 * caisson, et un tricolore se repère à sa hauteur sur la chaussée.
 *
 * Ordre de décision : **vetos** ([Veto], du plus sûr au plus fin), puis « le
 * plus proche du prior » dans la porte [GATE] — le suivi livre déjà la bonne
 * boîte sur 39 vidéos correctes sur 58, on ne remplace pas ce choix, on
 * l'interdit seulement quand la cible est manifestement autre chose. Sans
 * prior frais, on classe par vraisemblance de pictogramme ([score]) et on
 * refuse sous [MIN_PED_SCORE].
 *
 * Pur Kotlin (aucun import Android, aucune allocation dans [choose]) : la
 * mesure de lentille ([Lens.of]) travaille sur les pixels ARGB d'un crop, comme
 * [LampLens], dont elle est le complément (LampLens note l'intensité, celle-ci
 * note la forme). Testé en JVM (`PedLightSelectionTest`).
 */
object PedLightSelection {

    /** Porte d'association, en côté de boîte prior : même valeur que le suivi livré. */
    const val GATE = 1.6f

    /**
     * Côté de lentille minimal, en fraction de la largeur de frame (0,030 × 480
     * = 14 px) : le marquage au sol de `161541` verrouille des taches de 6 à
     * 10 px, les vrais feux suivis font 16 px au minimum (p10 = 26 px).
     */
    const val MIN_SIDE_REL = 0.030f

    /**
     * Remplissage à partir duquel une lentille ronde est un disque de feu
     * véhicule : pictogrammes 0,23–0,57, lentilles rondes 0,61–0,83.
     */
    const val DISC_FILL = 0.55f

    /** Bande d'élongation d'un disque (le bonhomme est plus étroit que 0,55). */
    const val DISC_ASPECT_MIN = 0.55f
    const val DISC_ASPECT_MAX = 1.05f

    /** Bandeau horizontal (marquage, rambarde) : le bonhomme ne dépasse pas 1,55. */
    const val MAX_ASPECT = 1.70f
    const val MIN_ASPECT = 0.28f

    /**
     * Hauteur relative maximale (0 = haut de la frame) : au-delà de 0,55 on est
     * au niveau de la chaussée — `151727` verrouille un feu véhicule à 0,58–0,67,
     * les bons verts mesurés montent à 0,385 au plus.
     */
    const val MAX_CY = 0.55f

    /** Lentille plus large que 30 % de la frame : plusieurs sources fusionnées. */
    const val MAX_SIDE_REL = 0.30f

    /** Saut de taille toléré sur une même piste (facteur), cf. `155503`. */
    const val SIZE_JUMP = 1.9f

    /** Plancher de vraisemblance quand il n'y a pas de prior (démarrage). */
    const val MIN_PED_SCORE = 0.30f

    /** Au-delà, le prior n'est plus « frais » : on reclasse par vraisemblance. */
    const val PRIOR_FRESH_FRAMES = 8

    /** Motif de refus d'un candidat (l'ordre de [rejection] fixe la priorité). */
    enum class Veto { TOO_SMALL, STRIP, MERGED, DISC, ROAD_LEVEL, SIZE_JUMP }

    /**
     * Géométrie de la lentille d'un crop : boîte de la tache allumée et ce
     * qu'elle dit de sa forme. `fill` et `holes` sont sans dimension, donc
     * insensibles à la distance et à la résolution.
     */
    data class Lens(val width: Float, val height: Float, val fill: Float, val holes: Int) {
        val side: Float get() = maxOf(width, height)
        val aspect: Float get() = width / maxOf(1f, height)
    }

    /**
     * Candidat : la boîte soumise (normalisée), la lentille mesurée dans son
     * crop et la taille de ce crop. Le crop est rééchantillonné (≤ 96 px), donc
     * les pixels de lentille ne sont pas ceux de la frame : [sideRelative]
     * défait l'échelle par le côté de boîte.
     */
    data class Candidate(
        val box: List<Float>,
        val lens: Lens,
        val cropWidth: Int,
        val cropHeight: Int,
    )

    /**
     * Contexte de la frame : le prior (dernière boîte retenue), sa lentille et
     * son âge, plus les dimensions de la frame pour les mesures relatives.
     */
    data class Context(
        val frameWidth: Int,
        val frameHeight: Int,
        val prior: List<Float>? = null,
        val priorLensSide: Float = 0f,
        val framesSincePrior: Int = Int.MAX_VALUE,
    )

    /**
     * Indice du feu piéton retenu dans [candidates], ou `null` = « attendez ».
     *
     * Aucun candidat, aucun survivant aux vetos, ou aucun survivant dans la
     * porte du prior : `null`. Un refus n'autorise jamais la traversée — l'aval
     * (`PedLightGate`, vote 3/5) rend `Red`/`Unknown` sur une frame sans boîte.
     */
    fun choose(candidates: List<Candidate>, context: Context): Int? {
        if (candidates.isEmpty()) return null
        val fresh = context.prior != null &&
            context.framesSincePrior <= PRIOR_FRESH_FRAMES
        var best = -1
        var bestDist = Float.MAX_VALUE
        var bestScore = -1f
        for (i in candidates.indices) {
            val candidate = candidates[i]
            if (rejection(candidate, context) != null) continue
            val likelihood = score(candidate)
            if (fresh) {
                val d = distance(candidate.box, context)
                if (d > GATE) continue
                if (d < bestDist || (d == bestDist && likelihood > bestScore)) {
                    best = i; bestDist = d; bestScore = likelihood
                }
            } else {
                if (likelihood < MIN_PED_SCORE) continue
                if (likelihood > bestScore || (likelihood == bestScore && best == -1)) {
                    best = i; bestScore = likelihood
                }
            }
        }
        return if (best < 0) null else best
    }

    /** Motif de refus du candidat (`null` = recevable). */
    fun rejection(candidate: Candidate, context: Context): Veto? {
        val lens = candidate.lens
        val relative = sideRelative(candidate, context)
        if (relative < MIN_SIDE_REL) return Veto.TOO_SMALL
        if (lens.aspect > MAX_ASPECT || lens.aspect < MIN_ASPECT) return Veto.STRIP
        if (relative > MAX_SIDE_REL) return Veto.MERGED
        if (lens.fill >= DISC_FILL &&
            lens.aspect >= DISC_ASPECT_MIN && lens.aspect <= DISC_ASPECT_MAX
        ) {
            return Veto.DISC
        }
        if (centreY(candidate.box) >= MAX_CY) return Veto.ROAD_LEVEL
        val priorSide = context.priorLensSide
        if (context.prior != null && priorSide > 0f) {
            val ratio = lens.side / priorSide
            if (ratio > SIZE_JUMP || ratio * SIZE_JUMP < 1f) return Veto.SIZE_JUMP
        }
        return null
    }

    /**
     * Vraisemblance « pictogramme de feu piéton », 0..1 : forme portrait,
     * remplissage partiel, trous internes, hauteur dans la frame. Sert
     * uniquement au départage quand aucun prior frais n'existe.
     */
    fun score(candidate: Candidate): Float {
        val lens = candidate.lens
        val shape = if (lens.aspect in 0.36f..0.78f) 1f else 0f
        val density = if (lens.fill in 0.16f..0.55f) 1f else 0f
        val holes = minOf(1f, lens.holes / 3f)
        val high = if (centreY(candidate.box) <= 0.45f) 1f else 0f
        return 0.34f * shape + 0.30f * density + 0.20f * holes + 0.16f * high
    }

    /**
     * Côté de lentille en **fraction de la largeur de frame** : les pixels du
     * crop ramenés à la frame par le côté de la boîte (`boxPx / cropSide`). Seuil
     * de taille indépendant du rééchantillonnage du crop.
     */
    fun sideRelative(candidate: Candidate, context: Context): Float {
        val fw = maxOf(1, context.frameWidth).toFloat()
        val fh = maxOf(1, context.frameHeight).toFloat()
        val cropSide = maxOf(1, candidate.cropWidth, candidate.cropHeight).toFloat()
        val box = candidate.box
        if (box.size < 4) return 0f
        val boxSide = maxOf((box[2] - box[0]) * fw, (box[3] - box[1]) * fh)
        return candidate.lens.side / cropSide * boxSide / fw
    }

    /** Distance centre/centre au prior, en côtés de boîte prior. */
    fun distance(box: List<Float>, context: Context): Float {
        val prior = context.prior ?: return 0f
        val fw = maxOf(1, context.frameWidth).toFloat()
        val fh = maxOf(1, context.frameHeight).toFloat()
        val dx = (centre(box, 0, 2) - centre(prior, 0, 2)) * fw
        val dy = (centre(box, 1, 3) - centre(prior, 1, 3)) * fh
        val side = maxOf((prior[2] - prior[0]) * fw, (prior[3] - prior[1]) * fh)
        return if (side <= 0f) Float.MAX_VALUE
        else kotlin.math.sqrt(dx * dx + dy * dy) / side
    }

    private fun centre(box: List<Float>, a: Int, b: Int): Float =
        if (box.size < 4) 0f else (box[a] + box[b]) / 2f

    private fun centreY(box: List<Float>): Float = centre(box, 1, 3)

    /**
     * Mesure de la lentille d'un crop de feu piéton (pixels ARGB, ligne majeure).
     *
     * La boîte de la tache est prise dans la **fenêtre centrale** du crop : le
     * crop livré est recentré sur le centroïde de la lampe
     * (`CrossingPixels.recenteredBox`), donc la fenêtre contient la lentille du
     * candidat et écarte les lampes voisines (le rouge éteint au-dessus du vert,
     * un répétiteur à côté). `fill` et les trous sont ensuite comptés sur toute
     * la boîte trouvée, dans le crop entier.
     *
     * Rend `null` si le crop est vide, incohérent ou sans pixel allumé
     * ([PedLightColor.isLampPixel]) — l'appelant doit alors refuser le candidat.
     */
    object LensMeasure {

        /** Marge ignorée de chaque côté : la fenêtre centrale fait 70 % du crop. */
        const val WINDOW = 0.15f

        fun of(argb: IntArray, width: Int, height: Int): PedLightSelection.Lens? {
            if (width <= 0 || height <= 0 || argb.size != width * height) return null
            val mx = (width * WINDOW).toInt()
            val my = (height * WINDOW).toInt()
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            for (y in my until height - my) {
                for (x in mx until width - mx) {
                    if (!PedLightColor.isLampPixel(argb[y * width + x])) continue
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < minX || maxY < minY) return null
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            var lit = 0
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    if (PedLightColor.isLampPixel(argb[y * width + x])) lit++
                }
            }
            return PedLightSelection.Lens(
                width = w.toFloat(),
                height = h.toFloat(),
                fill = lit.toFloat() / (w * h).toFloat(),
                holes = holes(argb, width, minX, minY, w, h),
            )
        }

        /**
         * Trous internes de la tache : pixels éteints de la boîte que le fond ne
         * rejoint pas par le bord. Un pictogramme en a (entre les jambes), un
         * disque de lentille aucun. Remplissage 4-connexe à pile explicite,
         * aucun tableau au-delà du marquage.
         */
        private fun holes(
            argb: IntArray, width: Int, x0: Int, y0: Int, w: Int, h: Int,
        ): Int {
            val seen = BooleanArray(w * h)
            val stack = IntArray(w * h)
            var top = 0
            for (i in 0 until w * h) {
                val x = i % w
                val y = i / w
                val border = x == 0 || y == 0 || x == w - 1 || y == h - 1
                if (!border || seen[i]) continue
                if (PedLightColor.isLampPixel(argb[(y0 + y) * width + x0 + x])) continue
                seen[i] = true
                stack[top++] = i
            }
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                top = push(argb, width, x0, y0, w, h, x - 1, y, seen, stack, top)
                top = push(argb, width, x0, y0, w, h, x + 1, y, seen, stack, top)
                top = push(argb, width, x0, y0, w, h, x, y - 1, seen, stack, top)
                top = push(argb, width, x0, y0, w, h, x, y + 1, seen, stack, top)
            }
            var holes = 0
            for (i in 0 until w * h) {
                if (seen[i]) continue
                val x = i % w
                val y = i / w
                if (!PedLightColor.isLampPixel(argb[(y0 + y) * width + x0 + x])) holes++
            }
            return holes
        }

        private fun push(
            argb: IntArray, width: Int, x0: Int, y0: Int, w: Int, h: Int,
            x: Int, y: Int, seen: BooleanArray, stack: IntArray, top: Int,
        ): Int {
            if (x < 0 || y < 0 || x >= w || y >= h) return top
            val i = y * w + x
            if (seen[i]) return top
            if (PedLightColor.isLampPixel(argb[(y0 + y) * width + x0 + x])) return top
            seen[i] = true
            stack[top] = i
            return top + 1
        }
    }
}
