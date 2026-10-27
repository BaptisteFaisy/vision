package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.roundToInt

/**
 * Un objet nommé à une position **normalisée** (0..1), avec sa distance estimée
 * quand elle est connue : ce que la voix met en mots.
 */
data class PlacedLabel(
    val label: String,
    val centerX: Float,
    val centerY: Float = 0.5f,
    val meters: Float? = null,
) {
    /** Repère lisible **français** (interface et signature de scène) : « à 9 heures ». */
    val position: String get() = PositionText.clockLabel(centerX)
}

/**
 * Mise en mots des positions détectées, pour l'annonce vocale ElevenLabs.
 *
 * Tout est **pur et testé en JVM** : le cadran horaire, l'ordre de lecture
 * (gauche → droite), le dédoublonnage et la troncature à [MAX_OBJECTS] objets.
 *
 * Deux langues, même cadran : la langue ne change que les mots ([SpeechLanguage]),
 * jamais la géométrie. Français par défaut — les Réglages choisissent la voix.
 *
 * Forme produite (fr) : `Personne à 9 heures, chien à 3 heures.` — sans article, donc
 * sans faute de genre sur des labels libres (« table », « livre », « personne »…).
 * Avec distance : `Personne à 9 heures, à 1,4 mètre ; chien à midi, à 3 mètres.`
 * Forme produite (en) : `Person at 9 o'clock, chair at 3 o'clock.`
 * Avec distance : `Person at 9 o'clock, 1.4 meters away; chair at 12 o'clock, 3 meters away.`
 */
object PositionText {

    /**
     * Frontière du couloir central : en deçà → côté gauche, au-delà de 0,66 →
     * côté droit. Ne sert plus qu'au couloir de [Guidance] — le parlé est au
     * cadran ([clockLabel]), sans repère vertical : la hauteur dans l'image
     * dépend du tangage de la caméra, elle n'oriente pas quelqu'un qui marche.
     */
    const val SIDE_EDGE = 0.34f

    /** Objets cités au plus dans une annonce (le reste est résumé par un compte). */
    const val MAX_OBJECTS = 3

    /** Heures du cadran, de gauche à droite : 9 h … midi … 3 h. */
    private val clockHours = intArrayOf(9, 10, 11, 12, 1, 2, 3)

    /**
     * Heure du cadran pour [centerX] (0..1) : 9 tout à gauche, 12 (midi) au centre,
     * 3 tout à droite. Sept bandes égales ; un bord tombe dans la bande du dessus,
     * et 1.0 reste à 3 h.
     */
    fun clockHour(centerX: Float): Int {
        val index = (centerX * clockHours.size).toInt().coerceIn(0, clockHours.size - 1)
        return clockHours[index]
    }

    /**
     * Direction parlée au cadran : « à 11 heures », « à midi », « à 3 heures »
     * (ou « at 11 o'clock », « at 12 o'clock ») — la convention des déplacements
     * sans voir, plus précise que « à gauche / au centre / à droite ».
     */
    fun clockLabel(centerX: Float, language: SpeechLanguage = SpeechLanguage.Fr): String {
        val hour = clockHour(centerX)
        return when (language) {
            SpeechLanguage.Fr ->
                if (hour == 12) "à midi" else "à $hour ${if (hour == 1) "heure" else "heures"}"
            SpeechLanguage.En -> "at $hour o'clock"
        }
    }

    /** Détection (boîte en pixels) → label placé ; `null` si la géométrie est inutilisable. */
    fun fromDetection(detection: Detection): PlacedLabel? {
        val box = detection.normalizedBox() ?: return null
        if (detection.label.isBlank()) return null
        return PlacedLabel(detection.label, (box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f)
    }

    /** Objet suivi (boîte déjà normalisée) → label placé ; `null` si la boîte est illisible. */
    fun fromSeenObject(obj: SeenObject): PlacedLabel? {
        if (obj.label.isBlank() || obj.box.size < 4) return null
        return PlacedLabel(obj.label, (obj.box[0] + obj.box[2]) / 2f, (obj.box[1] + obj.box[3]) / 2f)
    }

    /** Les détections exploitables d'une frame, dans l'ordre d'arrivée. */
    fun placed(detections: List<Detection>): List<PlacedLabel> = detections.mapNotNull(::fromDetection)

    /**
     * Phrase annoncée, ou `null` s'il n'y a rien à dire. Un label répété à la même
     * heure n'est cité qu'une fois ; au-delà de [maxObjects] objets cités, le reste
     * est résumé (« et 2 autres objets » / « and 2 other objects »).
     */
    fun phrase(
        labels: List<PlacedLabel>,
        maxObjects: Int = MAX_OBJECTS,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String? {
        val cleaned = labels
            .filter { it.label.isNotBlank() }
            .map { it.copy(label = it.label.trim()) }
            // La signature reste française : elle ne dépend pas de la langue de la voix.
            .distinctBy { it.label.lowercase() to it.position }
            .sortedWith(compareBy({ it.centerX }, { it.label }))
        if (cleaned.isEmpty()) return null
        val shown = cleaned.take(maxObjects.coerceAtLeast(1))
        // Des distances dans la phrase imposent le point-virgule : la virgule sert
        // déjà à séparer la position de la distance (« à 9 heures, à 1,4 mètre »).
        val separator = if (shown.any { it.meters != null }) semicolon(language) else ", "
        val body = shown.joinToString(separator) { item(it, language) }
        val hidden = cleaned.size - shown.size
        val text = if (hidden > 0) "$body, ${others(hidden, language)}." else "$body."
        return text.replaceFirstChar { it.uppercaseChar() }
    }

    /** Un objet en mots : « personne à 9 heures, à 1,4 mètre » / « person at 9 o'clock, 1.4 meters away ». */
    fun item(label: PlacedLabel, language: SpeechLanguage = SpeechLanguage.Fr): String {
        val place = "${label.label} ${clockLabel(label.centerX, language)}"
        val meters = label.meters ?: return place
        val distance = when (language) {
            SpeechLanguage.Fr -> "à ${DistanceText.spoken(meters, language)}"
            SpeechLanguage.En -> "${DistanceText.spoken(meters, language)} away"
        }
        return "$place, $distance"
    }

    /**
     * Signature de scène (labels + heures + distance **arrondie au mètre**),
     * insensible à l'ordre : sert à ne pas répéter la même scène. Arrondir est ce
     * qui fait parler un objet qui s'approche (3 m → 2 m → 1 m) sans le faire
     * bavarder à chaque frame. Indépendante de la langue de la voix.
     */
    fun key(labels: List<PlacedLabel>): String = labels
        .filter { it.label.isNotBlank() }
        .map { "${it.label.trim().lowercase()}|${it.position}|${bucket(it.meters)}" }
        .distinct()
        .sorted()
        .joinToString(";")

    /** « et 1 autre objet » / « et 3 autres objets » — « and 2 other objects » en anglais. */
    private fun others(count: Int, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> if (count == 1) "et 1 autre objet" else "et $count autres objets"
        SpeechLanguage.En -> if (count == 1) "and 1 other object" else "and $count other objects"
    }

    /** Séparateur des objets quand la phrase porte des distances. */
    private fun semicolon(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> " ; "
        SpeechLanguage.En -> "; "
    }

    /** Distance au mètre près pour la signature ; « ? » quand elle est inconnue. */
    private fun bucket(meters: Float?): String =
        if (meters == null || !meters.isFinite()) "?" else meters.roundToInt().toString()
}
