package dev.sam3vision.core.stairs

import dev.sam3vision.core.distance.DistanceReading

/**
 * Escalier vu par SAM dans les lectures de distance : [detected] quand un
 * libellé escalier ([StairConcepts]) est présent, [direction] son sens.
 *
 * Le sens vient des mots du libellé d'abord (« stairs going up », exact),
 * sinon de la hauteur de la boîte : un escalier cadré net au-dessus du bas
 * de l'image monte devant ([UP_BOTTOM_EDGE]) — une volée descendante ne se
 * voit en marches que depuis son bord, donc collée au bas. Une boîte qui
 * touche le bas ne tranche pas (montée proche ou descente) : [StairDirection.Unknown],
 * on annonce l'escalier sans sens. Heuristique assumée, à valider sur
 * téléphone ; le gradient de profondeur embarqué la remplacera.
 *
 * Pur JVM et testé.
 */
data class StairLabelSignal(
    val detected: Boolean,
    val direction: StairDirection = StairDirection.Unknown,
) {
    companion object {
        /** Aucun escalier nommé dans les lectures. */
        fun none(): StairLabelSignal = StairLabelSignal(detected = false)
    }
}

object StairLabelSignals {

    /**
     * Bord bas net du cadre (normalisé) : en dessous, une boîte d'escalier
     * monte devant ; au-dessus, elle touche les pieds et ne tranche pas.
     */
    const val UP_BOTTOM_EDGE = 0.8f

    /**
     * Escalier nommé dans [readings] ([width] × [height] : taille de l'image
     * analysée), le plus proche d'abord. Image inconnue ou boîte illisible =
     * [StairLabelSignal.none] (sûr : pas d'annonce sur une géométrie douteuse).
     */
    fun of(readings: List<DistanceReading>, width: Int, height: Int): StairLabelSignal {
        if (width <= 0 || height <= 0) return StairLabelSignal.none()
        val stairs = readings
            .filter { StairConcepts.isStairs(it.label) && it.box.size >= 4 }
            .sortedWith(compareBy({ distanceOf(it) }, { it.label }))
        if (stairs.isEmpty()) return StairLabelSignal.none()
        val labeled = stairs.firstOrNull { StairConcepts.direction(it.label) != StairDirection.Unknown }
        if (labeled != null) return StairLabelSignal(true, StairConcepts.direction(labeled.label))
        val nearest = stairs.first()
        val bottom = (nearest.box[3] / height).coerceIn(0f, 1f)
        if (bottom < UP_BOTTOM_EDGE) return StairLabelSignal(true, StairDirection.Up)
        return StairLabelSignal(true, StairDirection.Unknown)
    }

    /** Distance de tri : l'inconnu passe après toutes les mesures. */
    private fun distanceOf(reading: DistanceReading): Float {
        val meters = reading.meters
        return if (!meters.isFinite()) Float.MAX_VALUE else meters
    }
}
