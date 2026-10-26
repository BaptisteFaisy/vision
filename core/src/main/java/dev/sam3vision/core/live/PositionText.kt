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
    /** Repère lisible **français** (interface et signature de scène) : « en haut à gauche ». */
    val position: String get() = PositionText.positionLabel(centerX, centerY)
}

/**
 * Mise en mots des positions détectées, pour l'annonce vocale ElevenLabs.
 *
 * Tout est **pur et testé en JVM** : les seuils de secteur, l'ordre de lecture
 * (gauche → droite), le dédoublonnage et la troncature à [MAX_OBJECTS] objets.
 *
 * Deux langues, mêmes secteurs : la langue ne change que les mots ([SpeechLanguage]),
 * jamais la géométrie. Français par défaut — les Réglages choisissent la voix.
 *
 * Forme produite (fr) : `Personne à gauche, chien en bas à droite.` — sans article, donc
 * sans faute de genre sur des labels libres (« table », « livre », « personne »…).
 * Avec distance : `Personne à gauche, à 1,4 mètre ; chien au centre, à 3 mètres.`
 * Forme produite (en) : `Person on the left, chair in the bottom right.`
 * Avec distance : `Person on the left, 1.4 meters away; chair in the middle, 3 meters away.`
 */
object PositionText {

    /** Frontière de secteur : en deçà de 0,34 → gauche/haut, au-delà de 0,66 → droite/bas. */
    const val SIDE_EDGE = 0.34f

    /** Objets cités au plus dans une annonce (le reste est résumé par un compte). */
    const val MAX_OBJECTS = 3

    /** Repère horizontal : « à gauche » / « on the left ». */
    fun horizontal(centerX: Float, language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> when (side(centerX)) {
            Side.Left -> "à gauche"
            Side.Right -> "à droite"
            Side.Middle -> "au centre"
        }
        SpeechLanguage.En -> when (side(centerX)) {
            Side.Left -> "on the left"
            Side.Right -> "on the right"
            Side.Middle -> "in the middle"
        }
    }

    /** Repère vertical : « en haut » / « top », ou `null` dans la bande centrale. */
    fun vertical(centerY: Float, language: SpeechLanguage = SpeechLanguage.Fr): String? {
        val band = band(centerY) ?: return null
        return when (language) {
            SpeechLanguage.Fr -> if (band == Band.Top) "en haut" else "en bas"
            SpeechLanguage.En -> if (band == Band.Top) "top" else "bottom"
        }
    }

    /** Position complète : « en haut à gauche » / « in the top left », « au centre » / « in the middle ». */
    fun positionLabel(
        centerX: Float,
        centerY: Float,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String = when (language) {
        SpeechLanguage.Fr -> listOfNotNull(vertical(centerY, language), horizontal(centerX, language))
            .joinToString(" ")
        SpeechLanguage.En -> englishPosition(centerX, centerY)
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
     * position n'est cité qu'une fois ; au-delà de [maxObjects] objets cités, le reste
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
        // déjà à séparer la position de la distance (« à gauche, à 1,4 mètre »).
        val separator = if (shown.any { it.meters != null }) semicolon(language) else ", "
        val body = shown.joinToString(separator) { item(it, language) }
        val hidden = cleaned.size - shown.size
        val text = if (hidden > 0) "$body, ${others(hidden, language)}." else "$body."
        return text.replaceFirstChar { it.uppercaseChar() }
    }

    /** Un objet en mots : « personne à gauche, à 1,4 mètre » / « person on the left, 1.4 meters away ». */
    fun item(label: PlacedLabel, language: SpeechLanguage = SpeechLanguage.Fr): String {
        val place = "${label.label} ${positionLabel(label.centerX, label.centerY, language)}"
        val meters = label.meters ?: return place
        val distance = when (language) {
            SpeechLanguage.Fr -> "à ${DistanceText.spoken(meters, language)}"
            SpeechLanguage.En -> "${DistanceText.spoken(meters, language)} away"
        }
        return "$place, $distance"
    }

    /**
     * Signature de scène (labels + secteurs + distance **arrondie au mètre**),
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

    /** Position anglaise composée : « in the top left », « at the bottom », « on the left ». */
    private fun englishPosition(centerX: Float, centerY: Float): String {
        val word = when (side(centerX)) {
            Side.Left -> "left"
            Side.Right -> "right"
            Side.Middle -> null
        }
        val height = band(centerY)?.let { if (it == Band.Top) "top" else "bottom" }
        return when {
            height == null && word == null -> "in the middle"
            height == null -> "on the $word"
            word == null -> "at the $height"
            else -> "in the $height $word"
        }
    }

    /** Secteur horizontal (géométrie pure, indépendante de la langue). */
    private fun side(centerX: Float): Side = when {
        centerX < SIDE_EDGE -> Side.Left
        centerX > 1f - SIDE_EDGE -> Side.Right
        else -> Side.Middle
    }

    /** Bande verticale, `null` dans la bande centrale. */
    private fun band(centerY: Float): Band? = when {
        centerY < SIDE_EDGE -> Band.Top
        centerY > 1f - SIDE_EDGE -> Band.Bottom
        else -> null
    }

    /** Distance au mètre près pour la signature ; « ? » quand elle est inconnue. */
    private fun bucket(meters: Float?): String =
        if (meters == null || !meters.isFinite()) "?" else meters.roundToInt().toString()

    private enum class Side { Left, Middle, Right }

    private enum class Band { Top, Bottom }
}