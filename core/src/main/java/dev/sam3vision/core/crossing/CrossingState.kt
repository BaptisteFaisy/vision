package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject

/**
 * État d'un feu piéton vu par la caméra : [Red] (« attendez »), [Green]
 * (« traversez », annoncé seulement après confirmation, voir [CrossingAnnouncer])
 * ou [Unknown] (pas de feu détecté, boîte invalide ou couleur indécise).
 *
 * Le silence et le doute valent « ne traversez pas » : seul un vert confirmé
 * autorise la traversée.
 */
enum class CrossingState { Red, Green, Unknown }

/**
 * Prompts du **mode Traversée** : le feu et les voitures, rien d'autre.
 * Deux concepts exactement, bien sous le plafond [LivePrompts.MAX] : la Traversée
 * n'a besoin que du feu et des voitures.
 */
object CrossingPrompts {

    /** Concepts envoyés à SAM en mode Traversée (anglais, comme les défauts). */
    val PROMPTS: List<String> = listOf("traffic light", "car")

    /** Étiquettes (canoniques, voir [KnownSizes.canonical]) qui désignent un feu. */
    private val LIGHT_LABELS = setOf(
        "traffic light",
        "pedestrian light",
        "pedestrian signal",
        "stoplight",
        "traffic signal",
        "feu",
        "feu tricolore",
        "feu pieton",
        "feu pi",
    )

    /**
     * Vrai si [label] (brut, tel que renvoyé par SAM) désigne un feu.
     * Casse, accents et espaces neutralisés par [KnownSizes.canonical].
     */
    fun isLightLabel(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (LIGHT_LABELS.contains(key)) return true
        return key.split(' ').any { LIGHT_LABELS.contains(it) }
    }

    /**
     * Boîte normalisée (`x1,y1,x2,y2` en 0..1) du feu au meilleur score, `null`
     * si aucun feu n'est détecté ou si la boîte est invalide. La normalisation
     * suit [Detection.normalizedBox] : la boîte se reporte sur le Bitmap décodé
     * côté `:app`, quelle que soit sa taille.
     */
    fun selectLightBox(objects: List<SegObject>, width: Int, height: Int): List<Float>? {
        if (width <= 0 || height <= 0) return null
        return objects.asSequence()
            .filter { isLightLabel(it.label) }
            .maxByOrNull { it.score }
            ?.let { Detection(it.label, it.score, it.box, width, height).normalizedBox() }
            ?.takeIf { box -> box.size == 4 && box[2] > box[0] && box[3] > box[1] }
    }
}
