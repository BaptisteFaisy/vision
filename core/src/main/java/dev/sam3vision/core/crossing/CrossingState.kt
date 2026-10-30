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
 * Prompts du **mode Traversée** : le feu, les voitures, les personnes et le
 * passage lui-même. Quatre concepts, bien sous le plafond [LivePrompts.MAX] :
 * le feu dit quand traverser, les voitures forcent l'attente, les personnes
 * sont annoncées par positions, et le passage vu garde le mode auto actif
 * (perdu plusieurs frames de suite, retour au mode normal).
 */
object CrossingPrompts {

    /** Concept feu, demandé aussi par le mode traversée du direct. */
    const val LIGHT = "traffic light"

    /** Concept personne, annoncée par positions en mode Traversée. */
    const val PERSON = "person"

    /** Concept passage, qui garde le mode auto actif tant qu'il est vu. */
    const val CROSSWALK = "crosswalk"

    /** Concepts envoyés à SAM en mode Traversée (anglais, comme les défauts). */
    val PROMPTS: List<String> = listOf(LIGHT, "car", PERSON, CROSSWALK)

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
