package dev.sam3vision.core.distance

import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Mise en forme des distances pour l'écran et pour le LLM.
 *
 * Précision volontairement dégressive : le centimètre en deçà d'un mètre, le
 * décimètre jusqu'à dix mètres, le mètre au-delà — afficher « 23,4 m » laisserait
 * croire à une précision que la mesure monocular n'a pas.
 *
 * L'**écran** reste en français ([format], [approximate]) ; seule la **voix** suit la
 * langue choisie ([spoken]).
 */
object DistanceText {

    /** Étiquette française : « 85 cm », « 3,2 m », « 12 m ». */
    fun format(meters: Float): String {
        if (!meters.isFinite() || meters <= 0f) return "?"
        if (meters < 1f) return "${(meters * 100f).roundToInt()} cm"
        if (meters < 10f) return "${decimal(meters, 1, Locale.FRANCE)} m"
        return "${decimal(meters, 0, Locale.FRANCE)} m"
    }

    /** Étiquette avec l'approximation assumée : « ≈ 3,2 m ». */
    fun approximate(meters: Float): String = "≈ ${format(meters)}"

    /**
     * Distance **prononcée** par la voix, dans la langue de la voix.
     *
     * En français, le singulier va jusqu'à 2 (« 1,5 mètre ») ; en anglais il s'arrête à 1
     * (« 1.4 meters », « 1 meter ») et la décimale est un point, comme le lit la voix.
     */
    fun spoken(meters: Float, language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> spokenFrench(meters)
        SpeechLanguage.En -> spokenEnglish(meters)
    }

    /**
     * Distance **courte** pour la voix de marche : le mètre entier (« 2 mètres »,
     * « 1 mètre »), le centimètre sous un mètre (« 50 centimètres »). Une consigne
     * qui coupe la précédente n'a pas le temps des décimales.
     */
    fun spokenShort(meters: Float, language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> spokenShortFrench(meters)
        SpeechLanguage.En -> spokenShortEnglish(meters)
    }

    /** « 85 centimètres », « 1,4 mètre », « 12 mètres ». */
    private fun spokenFrench(meters: Float): String {
        if (!meters.isFinite() || meters <= 0f) return "distance inconnue"
        if (meters < 1f) {
            val centimeters = (meters * 100f).roundToInt()
            return "$centimeters ${if (centimeters < 2) "centimètre" else "centimètres"}"
        }
        val digits = if (meters < 10f) 1 else 0
        val text = decimal(meters, digits, Locale.FRANCE).removeSuffix(",0")
        val rounded = text.replace(',', '.').toFloatOrNull() ?: meters
        return "$text ${if (rounded < 2f) "mètre" else "mètres"}"
    }

    /** « 85 centimeters », « 1.4 meters », « 12 meters ». */
    private fun spokenEnglish(meters: Float): String {
        if (!meters.isFinite() || meters <= 0f) return "distance unknown"
        if (meters < 1f) {
            val centimeters = (meters * 100f).roundToInt()
            return "$centimeters ${if (centimeters == 1) "centimeter" else "centimeters"}"
        }
        val digits = if (meters < 10f) 1 else 0
        val text = decimal(meters, digits, Locale.US).removeSuffix(".0")
        val rounded = text.toFloatOrNull() ?: meters
        return "$text ${if (rounded == 1f) "meter" else "meters"}"
    }

    /** « 50 centimètres », « 1 mètre », « 2 mètres ». */
    private fun spokenShortFrench(meters: Float): String {
        if (!meters.isFinite() || meters <= 0f) return "distance inconnue"
        if (meters < 1f) {
            val centimeters = (meters * 100f).roundToInt()
            return "$centimeters ${if (centimeters < 2) "centimètre" else "centimètres"}"
        }
        val rounded = meters.roundToInt()
        return "$rounded ${if (rounded < 2) "mètre" else "mètres"}"
    }

    /** « 50 centimeters », « 1 meter », « 2 meters ». */
    private fun spokenShortEnglish(meters: Float): String {
        if (!meters.isFinite() || meters <= 0f) return "distance unknown"
        if (meters < 1f) {
            val centimeters = (meters * 100f).roundToInt()
            return "$centimeters ${if (centimeters == 1) "centimeter" else "centimeters"}"
        }
        val rounded = meters.roundToInt()
        return "$rounded ${if (rounded == 1) "meter" else "meters"}"
    }

    /** L'objet le plus proche de la frame, `null` si rien n'est mesuré. */
    fun nearest(readings: List<DistanceReading>): DistanceReading? =
        readings.minByOrNull { it.meters }

    /**
     * Distance la plus courte par étiquette, dans l'ordre d'apparition des objets :
     * c'est la pastille du bas d'écran (« personne 2,1 m »).
     */
    fun perLabel(readings: List<DistanceReading>): List<Pair<String, Float>> {
        val best = LinkedHashMap<String, Float>()
        readings.forEach { reading ->
            val previous = best[reading.label]
            best[reading.label] = if (previous == null) reading.meters else minOf(previous, reading.meters)
        }
        return best.map { (label, meters) -> label to meters }
    }

    /** Ligne courte pour la barre d'état : « plus proche 1,8 m », vide si rien. */
    fun nearestLabel(readings: List<DistanceReading>): String =
        nearest(readings)?.let { "plus proche ${format(it.meters)}" }.orEmpty()

    /**
     * Nombre décimal brut, locale explicite : l'unique formatage `String.format` des
     * nombres affichés (`LiveProbeText` l'utilise aussi — une seule implémentation).
     */
    fun decimal(value: Float, digits: Int, locale: Locale): String =
        String.format(locale, "%.${digits}f", value)
}