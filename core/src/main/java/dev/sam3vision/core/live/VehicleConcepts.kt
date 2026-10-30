package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes

/**
 * Véhicules que la voix nomme au-delà de sa portée : voitures, vélos et
 * trottinettes électriques — les mobiles rapides qui justifient une mention
 * anticipée (`docs/VOIX-DANGER.md` § 16).
 *
 * Reconnaissance miroir de `HazardConcepts` : casse, accents et pluriel
 * simple neutralisés (`KnownSizes.canonical`), français reconnu (« voiture »,
 * « vélo ») comme l'anglais des concepts SAM.
 */
object VehicleConcepts {

    /**
     * Portée des mentions informatives (m) : sous ce seuil, un véhicule se
     * dit même au-delà des 4 m du guidage (`Guidance.RANGE_METERS`) ; à
     * 10 m et plus, silence total — un véhicule plus loin n'est pas une info
     * de marche. Seuil produit V1, pas une mesure.
     */
    const val MENTION_RANGE_M = 10f

    /** Concepts véhicules, déjà canoniques (`KnownSizes.canonical`). */
    private val VEHICLES = setOf("car", "bicycle", "electric scooter", "voiture", "velo")

    /** Vrai si [label] désigne un véhicule suivi (casse, accents, pluriel neutralisés). */
    fun isVehicle(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (VEHICLES.contains(key)) return true
        if (key.endsWith("s") && key.length > 3) return VEHICLES.contains(key.dropLast(1))
        return false
    }

    /**
     * La mention informative dit-elle [label] ? À 4 m ou moins, tout se dit
     * (inchangé) ; au-delà et sous [MENTION_RANGE_M], les véhicules seuls ;
     * à 10 m et plus, silence ; distance inconnue ou non finie, dit
     * (prudence, inchangé — comme `Guidance`, qui bloque sur l'inconnu).
     */
    fun audible(label: PlacedLabel): Boolean {
        val meters = label.meters?.takeIf { it.isFinite() } ?: return true
        if (meters <= Guidance.RANGE_METERS) return true
        return meters < MENTION_RANGE_M && isVehicle(label.label)
    }

    /** Les labels que la mention informative garde : les autres restent tus. */
    fun audible(labels: List<PlacedLabel>): List<PlacedLabel> = labels.filter(::audible)
}
