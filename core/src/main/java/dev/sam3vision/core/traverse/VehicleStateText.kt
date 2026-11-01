package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Noms parlés des véhicules et phrases d'état FR/EN : « Voiture en mouvement. » (jamais
 * située), « Car stopped, 2 meters away. », « Bus en stationnement… ». L'immobile se situe —
 * distance au mètre entier si mesurée, heure du cadran sinon (jamais d'heure devinée sans
 * géométrie) ; **le mobile ne se situe pas** : il bouge, donc sa position est fausse avant
 * la fin de la phrase (ordre user du 01/11/2026).
 */
object VehicleStateText {

    /** Nom parlé du véhicule : [fr] et [en] capitalisés (« Voiture », « Car »). */
    data class VehicleName(val fr: String, val en: String)

    /** Alerte pluriel : « Stop, N vehicles moving. » / « Stop, N véhicules en mouvement. ». */
    private val PLURAL_ALERT = Regex("^Stop, \\d+ (vehicles moving|véhicules en mouvement)\\.$")

    private val NAMES: Map<String, VehicleName> = mapOf(
        "car" to VehicleName("Voiture", "Car"),
        "voiture" to VehicleName("Voiture", "Car"),
        "bus" to VehicleName("Bus", "Bus"),
        "buses" to VehicleName("Bus", "Bus"),
        "truck" to VehicleName("Camion", "Truck"),
        "camion" to VehicleName("Camion", "Truck"),
        "motorcycle" to VehicleName("Moto", "Motorcycle"),
        "moto" to VehicleName("Moto", "Motorcycle"),
        "bicycle" to VehicleName("Vélo", "Bicycle"),
        "bike" to VehicleName("Vélo", "Bicycle"),
        "velo" to VehicleName("Vélo", "Bicycle"),
        "scooter" to VehicleName("Trottinette", "Scooter"),
        "electric scooter" to VehicleName("Trottinette", "Scooter"),
        "trottinette" to VehicleName("Trottinette", "Scooter"),
        "trottinette electrique" to VehicleName("Trottinette", "Scooter"),
    )

    /** Nom parlé de [label], `null` si ce n'est pas un véhicule nommé. */
    fun nameOf(label: String): VehicleName? {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return null
        NAMES[key]?.let { return it }
        if (key.endsWith("s") && key.length > 3) return NAMES[key.dropLast(1)]
        return null
    }

    /**
     * « Voiture en mouvement. » — **sans emplacement** : ni distance ni heure. Un mobile
     * n'a pas de position qui tienne le temps d'être dite (ordre user du 01/11/2026) ;
     * le danger, lui, reste ([safetyPhrase]).
     */
    fun movingPhrase(name: VehicleName, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "${name.fr} en mouvement."
        SpeechLanguage.En -> "${name.en} moving."
    }

    /**
     * « 2 vehicles moving. » : pluriel générique ([count] types mobiles, pistes par type)
     * et, comme le singulier, **sans emplacement** — des mobiles n'ont pas de position
     * qui tienne le temps d'être dite.
     */
    fun movingPluralPhrase(count: Int, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "$count véhicules en mouvement."
        SpeechLanguage.En -> "$count vehicles moving."
    }

    /**
     * Alerte immédiate « Stop, … » (sans distance ni heure, voix de sécurité qui coupe tout).
     */
    fun safetyPhrase(name: VehicleName, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Stop, ${name.fr.replaceFirstChar { it.lowercase() }} en mouvement."
        SpeechLanguage.En -> "Stop, ${name.en.replaceFirstChar { it.lowercase() }} moving."
    }

    /** Alerte immédiate pluriel : « Stop, 2 vehicles moving. » — même canal sécurité. */
    fun safetyPluralPhrase(count: Int, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Stop, $count véhicules en mouvement."
        SpeechLanguage.En -> "Stop, $count vehicles moving."
    }

    /**
     * Vrai si [phrase] est une alerte mouvement (chaque [safetyPhrase] FR/EN ou [safetyPluralPhrase]) :
     * la sonde y lit l'urgence parlée en Traversée (seul mode où le stop se dit). Pure, testée en JVM.
     */
    fun isSafetyPhrase(phrase: String): Boolean {
        if (PLURAL_ALERT.matches(phrase)) return true
        return NAMES.values.any { name ->
            phrase == safetyPhrase(name, SpeechLanguage.Fr) ||
                phrase == safetyPhrase(name, SpeechLanguage.En)
        }
    }

    /** « Voiture à l'arrêt… », à la distance mesurée ou à l'heure. */
    fun stoppedPhrase(
        name: VehicleName,
        meters: Float?,
        centerX: Float?,
        language: SpeechLanguage,
    ): String = situate(
        base = when (language) {
            SpeechLanguage.Fr -> "${name.fr} à l'arrêt"
            SpeechLanguage.En -> "${name.en} stopped"
        },
        meters = meters,
        centerX = centerX,
        language = language,
    )

    /** « Voiture en stationnement… », à la distance mesurée ou à l'heure. */
    fun parkedPhrase(
        name: VehicleName,
        meters: Float?,
        centerX: Float?,
        language: SpeechLanguage,
    ): String = situate(
        base = if (language == SpeechLanguage.Fr) "${name.fr} en stationnement" else "${name.en} parked",
        meters = meters,
        centerX = centerX,
        language = language,
    )

    /** Situe [base] — arrêt et stationnement seulement : distance au mètre entier si mesurée, heure sinon. */
    private fun situate(
        base: String,
        meters: Float?,
        centerX: Float?,
        language: SpeechLanguage,
    ): String {
        if (meters != null && meters.isFinite() && meters > 0f) return when (language) {
            SpeechLanguage.Fr -> "$base, à ${DistanceText.spokenShort(meters, language)}."
            SpeechLanguage.En -> "$base, ${DistanceText.spokenShort(meters, language)} away."
        }
        return PositionText.withHour(base, centerX, language)
    }
}
