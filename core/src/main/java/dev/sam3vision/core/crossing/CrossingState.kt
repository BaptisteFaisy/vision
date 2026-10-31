package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject

/**
 * État d'un feu piéton vu par la caméra : [Red] (« attendez ») ou [Green]
 * (« traversez », annoncé seulement après confirmation, voir
 * [CrossingAnnouncer]). Binaire (ordre user du 31/10, fini le doute) : pas
 * de feu détecté, boîte invalide ou couleur indécise valent [Red] — seul
 * un vert confirmé autorise la traversée, tout le reste attend.
 */
enum class CrossingState { Red, Green }

/**
 * Concepts **minimaux** de la Traversée : le feu (en trois variantes), les
 * voitures, les personnes et le passage lui-même. Six concepts : le feu dit
 * quand traverser, les voitures forcent l'attente, les personnes sont
 * annoncées par positions, et le passage vu garde le mode auto actif (perdu
 * plusieurs frames de suite, retour au mode normal).
 *
 * Couverture « mêmes masques » : [LIGHT] est dans [LivePrompts.default],
 * les variantes ([LIGHT_SHORT], [LIGHT_SIGNAL]) désignent les mêmes masques
 * — le mode traversée actif les envoie en plus ([LIGHTS], câblage `:app`),
 * sans nouveau concept visuel à couvrir.
 */
object CrossingPrompts {

    /**
     * Concept feu principal : le feu **piéton** seul
     * (`docs/MESURES-SAM3-FEUX.md` §2 : rappel 87,5 % sur 16 photos réelles,
     * contre 43,8 % pour `pedestrian signal` et 12,5 % pour un prompt
     * d'état). Les feux voiture ne sont jamais suivis (ordre user du
     * 31/10) : un vert voiture ne doit jamais devenir un ordre de
     * traverser. Variantes d'union : [LIGHT_SHORT], [LIGHT_SIGNAL].
     */
    const val LIGHT = "pedestrian traffic light"

    /**
     * Variantes courtes du feu piéton, envoyées avec [LIGHT] en mode
     * traversée actif : l'union des trois wordings ne peut qu'ajouter des
     * détections du même caisson (mêmes masques). Jamais de wording voiture
     * (`traffic light`, `traffic signal`, `stoplight`) : un vert voiture ne
     * doit jamais devenir un ordre de traverser (A2 bloquant).
     */
    const val LIGHT_SHORT = "pedestrian light"
    const val LIGHT_SIGNAL = "pedestrian signal"

    /** Wordings feu envoyés en mode traversée actif, [LIGHT] d'abord. */
    val LIGHTS: List<String> = listOf(LIGHT, LIGHT_SHORT, LIGHT_SIGNAL)

    /** Concept personne, annoncée par positions en mode Traversée. */
    const val PERSON = "person"

    /** Concept passage, qui garde le mode auto actif tant qu'il est vu. */
    const val CROSSWALK = "crosswalk"

    /** Concepts envoyés à SAM en mode Traversée (anglais, comme les défauts). */
    val PROMPTS: List<String> = LIGHTS + listOf("car", PERSON, CROSSWALK)

    /**
     * Étiquettes (canoniques, voir [KnownSizes.canonical]) qui désignent un
     * feu **piéton** — et lui seul. `traffic light`, `stoplight`,
     * `traffic signal`, `feu` et `feu tricolore` sont des feux voiture (ou
     * ambigus) : exclus (ordre user du 31/10), même suivis à la main dans
     * le champ « Follow ». Pas de `pieton` seul : un passant étiqueté
     * « piéton » n'est pas un feu (voir [PersonLabels]). Les wordings
     * anglais sont [LIGHTS] (alignement structurel : tout wording envoyé
     * est reconnu).
     */
    private val LIGHT_LABELS = LIGHTS.toSet() + setOf(
        "feu pieton",
        "feux pieton",
        "feu pietons",
        "feux pietons",
        "feu pi",
        "signal pieton",
    )

    /**
     * Vrai si [label] (brut, tel que renvoyé par SAM) désigne un feu piéton :
     * phrase connue ([LIGHT_LABELS], dont l'abrégé oral `feu pi`) ou composé
     * piéton + lampe ([isPedestrianLamp]) — « feu piéton rouge » saisi à la
     * main reste un feu. Casse, accents et espaces neutralisés par
     * [KnownSizes.canonical]. `passage pieton` et `pedestrian crossing` n'ont
     * pas de lampe : jamais des feux.
     */
    fun isLightLabel(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (LIGHT_LABELS.contains(key)) return true
        return isPedestrianLamp(key.split(' '))
    }

    /** Composé piéton + lampe : les deux mots exigés, dans n'importe quel ordre. */
    private fun isPedestrianLamp(words: List<String>): Boolean {
        val pedestrian = words.any { it == "pieton" || it == "pietons" || it == "pedestrian" || it == "pedestrians" }
        if (!pedestrian) return false
        return words.any { it == "feu" || it == "feux" || it == "signal" || it == "signaux" || it == "light" || it == "lights" }
    }

    /**
     * Boîte normalisée (`x1,y1,x2,y2` en 0..1) du feu piéton au meilleur
     * score, `null` si aucun n'est détecté ou si la boîte est invalide. Les
     * éclats (boîte sous le dixième de la plus grande — un reflet de 384 px
     * ne bat jamais un caisson, `docs/MESURES-SAM3-FEUX.md` §5a) sont
     * écartés avant le tri au score. La normalisation suit
     * [Detection.normalizedBox] : la boîte se reporte sur le Bitmap décodé
     * côté `:app`, quelle que soit sa taille.
     */
    fun selectLightBox(objects: List<SegObject>, width: Int, height: Int): List<Float>? {
        if (width <= 0 || height <= 0) return null
        val lights = objects.filter { isLightLabel(it.label) }
        val areas = lights.map { boxArea(it.box) }
        val biggest = areas.maxOrNull() ?: return null
        if (biggest <= 0f) return null
        return lights.asSequence()
            .filterIndexed { index, _ -> areas[index] >= biggest / SLIVER_RATIO }
            .maxByOrNull { it.score }
            ?.let { Detection(it.label, it.score, it.box, width, height).normalizedBox() }
            ?.takeIf { box -> box.size == 4 && box[2] > box[0] && box[3] > box[1] }
    }

    /** Éclats ignorés sous le dixième de la plus grande boîte feu (§5a). */
    private const val SLIVER_RATIO = 10f

    /** Aire pixels d'une boîte SAM, `0` si illisible (jamais négative). */
    private fun boxArea(box: List<Float>): Float {
        if (box.size < 4) return 0f
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return 0f
        return w * h
    }
}
