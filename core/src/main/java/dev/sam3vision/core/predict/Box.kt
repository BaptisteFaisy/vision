package dev.sam3vision.core.predict

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Boîte 2D `[x1, y1, x2, y2]` en pixels de l'image d'analyse — le même référentiel
 * que le champ `box` de `/live` et `/frame`.
 *
 * Immutable : chaque opération renvoie une nouvelle boîte. L'UI lit l'état depuis un
 * thread de rendu pendant que le réseau l'écrit depuis un autre ; rien n'est jamais
 * muté en place.
 */
data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {

    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f
    val centerY: Float get() = (y1 + y2) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)

    /** `true` si la boîte est non dégénérée (coins dans l'ordre, aire > 0). */
    val isSane: Boolean get() = x2 > x1 && y2 > y1

    /** Translation pure — le coeur de la prédiction de mouvement. */
    fun translated(dx: Float, dy: Float): Box = Box(x1 + dx, y1 + dy, x2 + dx, y2 + dy)

    /** Variation de taille `[dw, dh]` appliquée symétriquement autour du centre. */
    fun grown(dw: Float, dh: Float): Box =
        Box(x1 - dw / 2f, y1 - dh / 2f, x2 + dw / 2f, y2 + dh / 2f)

    /** Homothétie centrée : `sx = sy = 1.2f` agrandit de 20 %. */
    fun scaled(sx: Float, sy: Float): Box = grown(width * (sx - 1f), height * (sy - 1f))

    /** Mélange linéaire : `t = 0` → `this`, `t = 1` → [other]. Sert à lisser l'ancre. */
    fun lerp(other: Box, t: Float): Box {
        val k = t.coerceIn(0f, 1f)
        return Box(
            x1 + (other.x1 - x1) * k,
            y1 + (other.y1 - y1) * k,
            x2 + (other.x2 - x2) * k,
            y2 + (other.y2 - y2) * k,
        )
    }

    /** Ramène la boîte dans `[0,w] × [0,h]` en préservant sa taille quand c'est possible. */
    fun clampedTo(widthPx: Int, heightPx: Int): Box {
        if (widthPx <= 0 || heightPx <= 0) return this
        val w = max(0f, width)
        val h = max(0f, height)
        val nx1 = centerX.coerceIn(w / 2f, max(w / 2f, widthPx - w / 2f)) - w / 2f
        val ny1 = centerY.coerceIn(h / 2f, max(h / 2f, heightPx - h / 2f)) - h / 2f
        return Box(nx1, ny1, nx1 + w, ny1 + h)
    }

    /** Intersection / union. 0 si disjointe ou dégénérée — seuil du garde-fou de dérive. */
    fun iou(other: Box): Float {
        if (!isSane || !other.isSane) return 0f
        val ix = min(x2, other.x2) - max(x1, other.x1)
        val iy = min(y2, other.y2) - max(y1, other.y1)
        if (ix <= 0f || iy <= 0f) return 0f
        val intersection = ix * iy
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        /**
         * `[x1,y1,x2,y2]` du protocole. Accepte des coordonnées inversées, rejette
         * les listes trop courtes, non finies ou dégénérées (`null` = pas d'objet).
         */
        fun fromProto(values: List<Float>?): Box? {
            if (values == null || values.size < 4) return null
            val a = values[0]
            val b = values[1]
            val c = values[2]
            val d = values[3]
            if (listOf(a, b, c, d).any { it.isNaN() || it.isInfinite() }) return null
            val box = Box(min(a, c), min(b, d), max(a, c), max(b, d))
            return if (box.width < MIN_EDGE || box.height < MIN_EDGE) null else box
        }

        /** Sous un pixel de côté, la boîte ne veut rien dire : on l'ignore. */
        const val MIN_EDGE: Float = 1f
    }
}

/** Vitesse en pixels/seconde : translation `[x, y]` + variation de taille `[width, height]`. */
data class Velocity(val x: Float, val y: Float, val width: Float = 0f, val height: Float = 0f) {

    val speed: Float get() = sqrt(x * x + y * y)

    /** Plafonne la norme de translation : un objet ne peut pas traverser l'image en 10 ms. */
    fun limited(maxSpeed: Float): Velocity {
        if (maxSpeed <= 0f || speed <= maxSpeed) return this
        val k = maxSpeed / speed
        return Velocity(x * k, y * k, width * k, height * k)
    }

    /** Déplacement sur une durée, pour l'extrapolation `ancre + vitesse × t`. */
    fun over(seconds: Float): Velocity =
        Velocity(x * seconds, y * seconds, width * seconds, height * seconds)

    companion object {
        val Zero = Velocity(0f, 0f)
    }
}
