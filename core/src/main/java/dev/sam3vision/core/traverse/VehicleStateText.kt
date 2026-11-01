package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Noms parlés des véhicules et phrases d'état (SPEC v4, voiture seule) :
 * « Car. » / « Cars. » (niveau 0, en mouvement — le niveau dit le mouvement,
 * le nom suffit), « Car stopped, 2 meters away. » (l'immobile se situe :
 * distance au mètre entier si mesurée, heure du cadran sinon), « Stop, car
 * moving. » (alerte immédiate). **Le mobile ne se situe pas** : ni distance
 * ni heure, il bouge donc sa position est fausse avant la fin de la phrase.
 */
object VehicleStateText {

    /**
     * Nom parlé du véhicule : [fr] et [en] capitalisés (« Voiture », « Car ») et
     * [clipKey], la clé du pack voix v4 (`car`, `cars`).
     */
    data class VehicleName(val fr: String, val en: String, val clipKey: String)

    /** Alerte pluriel : « Stop, N vehicles moving. » / « Stop, N véhicules en mouvement. ». */
    private val PLURAL_ALERT = Regex("^Stop, \\d+ (vehicles moving|véhicules en mouvement)\\.$")

    private val CAR = VehicleName("Voiture", "Car", "car")

    private val NAMES: Map<String, VehicleName> = mapOf(
        "car" to CAR,
        "voiture" to CAR,
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
     * « Car. » — le niveau 0 ne parle que des voitures en mouvement, donc le nom
     * seul suffit (**sans emplacement** : ni distance ni heure).
     */
    fun movingPhrase(name: VehicleName, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "${name.fr}."
        SpeechLanguage.En -> "${name.en}."
    }

    /**
     * « Cars. » : pluriel générique, **sans emplacement** comme le singulier —
     * [count] ne se dit pas (décision D-02 : le niveau dit le mouvement).
     */
    fun movingPluralPhrase(count: Int, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Voitures."
        SpeechLanguage.En -> "Cars."
    }

    /**
     * Alerte immédiate « Stop, … » (sans distance ni heure, voix de sécurité qui coupe tout).
     */
    fun safetyPhrase(name: VehicleName, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Stop, ${name.fr.replaceFirstChar { it.lowercase() }} en mouvement."
        SpeechLanguage.En -> "Stop, ${name.en.replaceFirstChar { it.lowercase() }} moving."
    }

    /**
     * Clip du pack voix v4 pour un mouvement : `car` (« Car. »). Joué sans
     * réseau ni synthèse.
     */
    fun movingClipId(name: VehicleName): String = name.clipKey

    /** Clip du pluriel (« Cars. »). */
    fun movingPluralClipId(): String = "cars"

    /**
     * Clip d'une phrase **fixe** de l'état véhicule (`null` = variable ou pas
     * de clip) : les mouvements seuls (« Car. », « Cars. » et leurs pendants
     * FR, qui jouent le clip anglais — pack v4 anglais uniquement) ; jamais
     * les phrases situées (arrêt et stationnement portent une distance ou une
     * heure). Pure, testée en JVM.
     */
    fun clipIdFor(phrase: String): String? = CLIPS[phrase]

    private val CLIPS: Map<String, String> = mapOf(
        "Car." to "car",
        "Cars." to "cars",
        "Voiture." to "car",
        "Voitures." to "cars",
    )

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
