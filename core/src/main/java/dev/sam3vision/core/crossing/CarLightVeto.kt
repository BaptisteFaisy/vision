package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.predict.Box
import kotlin.math.max
import kotlin.math.min

/**
 * Veto tricolore voiture : une boîte de feu piéton inscrite dans un gros
 * caisson voiture n'est pas un feu piéton — le prompt « pedestrian traffic
 * light » capte aussi les feux voiture (même tête visuelle) et l'étiquette
 * renvoyée est le wording du prompt, que le filtre d'étiquettes laisse
 * passer. Le veto compare les boîtes, pas les mots.
 *
 * INACTIF tant que [VETO] n'est envoyé par aucune liste (31/10 : wording
 * non mesuré — rappel voiture et 0 capture piéton à prouver sur ~15 photos
 * via `docs/MESURES-SAM3-FEUX.md` §1 avant tout envoi ; un veto qui capte
 * les piétons éteindrait les vrais verts). Testable dès maintenant au
 * champ « Follow » (wording EN ou « feu voiture ») : toute boîte renvoyée
 * sous ces étiquettes fait veto.
 *
 * Métrique : contenance (intersection / aire piéton), pas IoU — le faux
 * positif est inscrit dans le gros caisson, l'IoU resterait faible malgré
 * l'inclusion (`CrossingOnPath`, même leçon sur tailles disparates).
 * Seuil partiel [CONTAINMENT_MIN] : un feu piéton voisin du tricolore
 * (même poteau, quelques mètres) ne doit pas être éteint.
 *
 * Pur Kotlin, testé en JVM (`CrossingStateTest`) sur objets synthétiques.
 */
object CarLightVeto {

    /**
     * Wording du veto, candidat n° 1 (miroir de `pedestrian traffic light`).
     * Non mesuré, envoyé nulle part — voir KDoc. Autres candidats :
     * `three-lamp traffic light`, `stoplight` (à proscrire : `traffic
     * light` / `traffic signal` seuls, tokens partagés avec le piéton).
     */
    const val VETO = "vehicle traffic light"

    /** Contenance piéton-dans-voiture à partir de laquelle le veto tranche. */
    private const val CONTAINMENT_MIN = 0.5f

    /** Étiquettes (canoniques) d'un feu voiture — jamais de piéton dedans. */
    private val CAR_LABELS = setOf(
        "vehicle traffic light",
        "vehicle traffic lights",
        "feu voiture",
        "feux voiture",
        "feu tricolore",
        "feux tricolore",
        "feu tricolores",
        "feux tricolores",
    )

    /**
     * Vrai si [label] (brut, tel que renvoyé par SAM) désigne un feu voiture :
     * wording du veto ou alias français (casse, accents, espaces neutralisés
     * par [KnownSizes.canonical]). Ni `car` (un véhicule n'est pas un feu),
     * ni `traffic light` seul (ambigu, tokens partagés avec le piéton).
     */
    fun isCarLightLabel(label: String): Boolean = CAR_LABELS.contains(KnownSizes.canonical(label))

    /** Boîtes voiture valides (≥ 1 px, finies) parmi [objects]. */
    fun carBoxes(objects: List<SegObject>): List<Box> =
        objects.filter { isCarLightLabel(it.label) }.mapNotNull { Box.fromProto(it.box) }

    /**
     * Vrai si [prompt] (brut, champ Follow ou base traversée) demande un
     * concept feu **voiture** : wordings du veto ([CAR_LABELS]) plus les
     * génériques ambigus (`traffic light`, `traffic signal`, `stoplight` —
     * jamais envoyés, jamais reconnus, sauf ici pour les filtrer). La
     * traversée ne suit jamais un concept voiture (veto fantôme interdit,
     * `docs/MESURES-SAM3-FEUX.md` §10) : `traversePrompts` s'en sert.
     * `pedestrian traffic light` n'est pas un prompt veto (canoniques
     * distincts, testé).
     */
    fun isVetoPrompt(prompt: String): Boolean {
        val key = KnownSizes.canonical(prompt)
        if (CAR_LABELS.contains(key)) return true
        return key == "traffic light" || key == "traffic signal" || key == "stoplight"
    }

    /**
     * Vrai si la boîte piéton [pedBox] (pixels) est pour moitié au moins dans
     * une boîte voiture : recouvrement partiel, pas total (feu piéton voisin
     * préservé). Boîte illisible ⇒ pas de veto (l'aval la rejette).
     */
    fun isVetoed(pedBox: List<Float>, carBoxes: List<Box>): Boolean {
        val ped = Box.fromProto(pedBox) ?: return false
        return carBoxes.any { containment(ped, it) >= CONTAINMENT_MIN }
    }

    /** Part de [ped] couverte par [car] (0 disjoints, 1 inscrit). */
    private fun containment(ped: Box, car: Box): Float {
        val ix = min(ped.x2, car.x2) - max(ped.x1, car.x1)
        val iy = min(ped.y2, car.y2) - max(ped.y1, car.y1)
        if (ix <= 0f || iy <= 0f || ped.area <= 0f) return 0f
        return ix * iy / ped.area
    }
}
