package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject
import dev.sam3vision.core.speech.SpeechLanguage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Un objet nommé à une position **normalisée** (0..1), avec sa distance estimée
 * quand elle est connue : ce que la voix met en mots. Les bords ([x0], [x1])
 * servent au trait droit ([Guidance]) ; `null` = inconnus (repli au centre).
 */
data class PlacedLabel(
    val label: String,
    val centerX: Float,
    val centerY: Float = 0.5f,
    val meters: Float? = null,
    val x0: Float? = null,
    val x1: Float? = null,
    /**
     * Identité de la piste ([PersonTracker]) : `null` quand l'appelant n'a pas
     * suivi la scène. Sert à ne prévenir qu'une fois par personne et à juger
     * l'approche (audit foule du 01/11).
     */
    val trackId: Int? = null,
    /**
     * Vitesse de fermeture de la piste (m/s, positif = ça se rapproche),
     * `null` sans piste : [Timing.projectDistance] rend alors la mesure telle
     * quelle au lieu de deviner un mouvement.
     */
    val closingMps: Float? = null,
) {
    /** Repère lisible **français** (interface et signature de scène) : « à 9 heures ». */
    val position: String get() = PositionText.clockLabel(centerX)
}

/**
 * Mise en mots des positions détectées, pour l'annonce vocale ElevenLabs.
 *
 * Tout est **pur et testé en JVM** : le cadran horaire, l'ordre de lecture
 * (**le plus proche d'abord, puis le plus en face**), le dédoublonnage et la
 * troncature à [MAX_OBJECTS] objets.
 *
 * Deux langues, même cadran : la langue ne change que les mots ([SpeechLanguage]),
 * jamais la géométrie. Français par défaut — les Réglages choisissent la voix.
 *
 * Forme produite (fr) : `Personne à 9 heures, chien à 3 heures.` — sans article, donc
 * sans faute de genre sur des labels libres (« table », « livre », « personne »…).
 * Avec distance : `Personne à 1 mètre, chien à 3 mètres.` — le cadran tombe, la
 * distance au mètre entier suffit, et l'ordre dit déjà qui est en face.
 * Forme produite (en) : `Person at 9 o'clock, chair at 3 o'clock.`
 * Avec distance : `Person 1 meter away, chair 3 meters away.`
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
     * [base] (sans point) située au cadran : « Attention à la barrière, à 2
     * heures. » — [centerX] `null` (géométrie inconnue) = [base] seule, jamais
     * d'heure devinée. Les « Attention … » (marche, escaliers, mobilier) y
     * passent : l'info dit où, au cadran des aveugles (ordre user du 29/10).
     */
    fun withHour(base: String, centerX: Float?, language: SpeechLanguage): String =
        if (centerX == null) "$base." else "$base, ${clockLabel(centerX, language)}."

    /**
     * Direction parlée au cadran depuis [centerX] : « à 11 heures », « à
     * midi », « à 3 heures » (ou « at 11 o'clock », « at 12 o'clock ») — la
     * convention des déplacements sans voir, plus précise que « à gauche /
     * au centre / à droite ».
     */
    fun clockLabel(centerX: Float, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        hourLabel(clockHour(centerX), language)

    /**
     * Heure du cadran parlée : « à 11 heures », « à midi », « à 1 heure »
     * (ou « at 11 o'clock ») — [clockLabel] y passe par [clockHour], le
     * trouvé de la chasse picto y passe par son heure directe.
     */
    fun hourLabel(hour: Int, language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr ->
            if (hour == 12) "à midi" else "à $hour ${if (hour == 1) "heure" else "heures"}"
        SpeechLanguage.En -> "at $hour o'clock"
    }

    /** Détection (boîte en pixels) → label placé ; `null` si la géométrie est inutilisable. */
    fun fromDetection(detection: Detection): PlacedLabel? {
        val box = detection.normalizedBox() ?: return null
        if (detection.label.isBlank()) return null
        return PlacedLabel(
            detection.label, (box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f,
            x0 = box[0], x1 = box[2],
        )
    }

    /** Objet suivi (boîte déjà normalisée) → label placé ; `null` si la boîte est illisible. */
    fun fromSeenObject(obj: SeenObject): PlacedLabel? {
        if (obj.label.isBlank() || obj.box.size < 4) return null
        return PlacedLabel(
            obj.label, (obj.box[0] + obj.box[2]) / 2f, (obj.box[1] + obj.box[3]) / 2f,
            x0 = obj.box[0], x1 = obj.box[2],
        )
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
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        val cleaned = labels
            .filter { it.label.isNotBlank() }
            .map { it.copy(label = it.label.trim()) }
            // La signature reste française : elle ne dépend pas de la langue de la voix.
            .distinctBy { it.label.lowercase() to it.position }
            .sortedWith(compareBy({ distanceOf(it) }, { abs(it.centerX - 0.5f) }, { it.label }))
        if (cleaned.isEmpty()) return null
        val shown = cleaned.take(maxObjects.coerceAtLeast(1))
        val body = shown.joinToString(", ") { item(it, language) }
        val hidden = cleaned.size - shown.size
        val text = if (hidden > 0) "$body, ${others(hidden, language)}." else "$body."
        return text.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Un objet en mots : « personne à 2 mètres » / « person 2 meters away » quand la
     * distance est connue, sinon « personne à 9 heures » / « person at 9 o'clock ».
     */
    fun item(label: PlacedLabel, language: SpeechLanguage = SpeechLanguage.DEFAULT): String {
        val meters = label.meters
        if (meters == null || !meters.isFinite()) {
            return "${label.label} ${clockLabel(label.centerX, language)}"
        }
        return when (language) {
            SpeechLanguage.Fr -> "${label.label} à ${DistanceText.spokenShort(meters, language)}"
            SpeechLanguage.En -> "${label.label} ${DistanceText.spokenShort(meters, language)} away"
        }
    }

    /**
     * Mise en lumière d'un objet suivi : « Les clés sont devant toi, à 9
     * heures. » — dite quand l'utilisateur cherche cet objet précis (chasse
     * picto, « mode find my X »), jamais dans l'annonce générale ([phrase]).
     * Le pluriel se lit à la finale (« clés », « keys » : « Les … sont » /
     * « The … are ») ; au singulier, pas d'article en français (le genre du
     * label est inconnu : « Téléphone devant toi, à 9 heures »). La distance
     * connue remplace le cadran, comme dans [item]. [label] n'est jamais vide
     * (l'appelant ne retient que des labels visés).
     */
    fun spotlight(label: PlacedLabel, language: SpeechLanguage = SpeechLanguage.DEFAULT): String {
        val name = label.label.trim()
        val plural = name.endsWith('s', ignoreCase = true) && !name.endsWith("ss", ignoreCase = true)
        val where = if (label.meters == null || !label.meters.isFinite()) {
            clockLabel(label.centerX, language)
        } else when (language) {
            SpeechLanguage.Fr -> "à ${DistanceText.spokenShort(label.meters, language)}"
            SpeechLanguage.En -> "${DistanceText.spokenShort(label.meters, language)} away"
        }
        return when (language) {
            SpeechLanguage.Fr -> if (plural) "Les ${name.lowercase()} sont devant toi, $where."
            else "${name.replaceFirstChar { it.uppercaseChar() }} devant toi, $where."
            SpeechLanguage.En -> "The ${name.lowercase()} ${if (plural) "are" else "is"} in front of you, $where."
        }
    }

    /**
     * Signature de scène (labels + heures + distance **au seau**),
     * insensible à l'ordre : sert à ne pas répéter la même scène. Le seau
     * ([bucketOf], [StableBucket] en usage : arrondi au mètre avec
     * hystérésis) est ce qui fait parler un objet qui s'approche (3 m → 2 m
     * → 1 m) sans le faire bavarder à chaque frame ni à chaque tremblé de
     * mesure. Indépendante de la langue de la voix.
     */
    fun key(
        labels: List<PlacedLabel>,
        bucketOf: (id: String, meters: Float?) -> String = { _, meters -> sceneBucket(meters) },
    ): String = labels
        .filter { it.label.isNotBlank() }
        .map {
            val id = "${it.label.trim().lowercase()}|${it.position}"
            "$id|${bucketOf(id, it.meters)}"
        }
        .distinct()
        .sorted()
        .joinToString(";")

    /** « et 1 autre objet » / « et 3 autres objets » — « and 2 other objects » en anglais. */
    private fun others(count: Int, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> if (count == 1) "et 1 autre objet" else "et $count autres objets"
        SpeechLanguage.En -> if (count == 1) "and 1 other object" else "and $count other objects"
    }

    /** Distance de tri : l'inconnu passe après toutes les mesures. */
    private fun distanceOf(label: PlacedLabel): Float {
        val meters = label.meters
        return if (meters == null || !meters.isFinite()) Float.MAX_VALUE else meters
    }

    /**
     * Distance au mètre près pour les signatures anti-répétition ; « ? » quand elle
     * est inconnue. Partagé avec [Guidance.key] : une seule définition du seau.
     */
    fun sceneBucket(meters: Float?): String =
        if (meters == null || !meters.isFinite()) "?" else meters.roundToInt().toString()
}

/**
 * Seau de distance avec hystérésis : la mémoire anti-tremblé de la
 * déduplication ([PositionText.key]).
 *
 * Le seau brut ([PositionText.sceneBucket], mètre arrondi) bascule à chaque
 * passage de frontière : un objet immobile mesuré 1,49 m puis 1,51 m
 * changerait de signature à chaque frame et se réannoncerait à chaque
 * délai. Ici, une fois le seau N adopté, on n'en sort qu'au-delà de
 * N ± (0,5 + [marginM]) : le tremblé de mesure reste dans le seau, une
 * vraie approche d'un mètre en sort (et parle).
 *
 * Pur et testé en JVM. Une instance par annonceur ([PositionAnnouncer] en
 * détient une) : la mémoire est par libellé ([id] « label|heure »), une
 * scène vide ou une nouvelle session l'oublie ([reset]).
 */
class StableBucket(private val marginM: Float = STABLE_BUCKET_MARGIN_M) {

    private val last: MutableMap<String, Int> = mutableMapOf()

    /**
     * Seau de [meters] pour [id] : « ? » si inconnue (sans toucher la
     * mémoire — une mesure perdue une frame ne ré-arme rien), sinon le seau
     * adopté (mémorisé).
     */
    fun bucketOf(id: String, meters: Float?): String {
        val measured = meters?.takeIf { it.isFinite() } ?: return "?"
        val rounded = measured.roundToInt()
        val previous = last[id]
        val kept = if (previous != null && abs(measured - previous) <= 0.5f + marginM) {
            previous
        } else {
            rounded
        }
        last[id] = kept
        return kept.toString()
    }

    /** Nouvelle scène / session : les seaux adoptés sont oubliés. */
    fun reset() {
        last.clear()
    }

    companion object {
        /**
         * Marge d'hystérésis (mètres) : un tremblé de ±5 cm autour d'une
         * frontière ne change pas de seau, un pas franc vers le mètre
         * suivant (0,6 m dès 2,4 m) en change — la ré-annonce « au mètre
         * près » est préservée.
         */
        const val STABLE_BUCKET_MARGIN_M = 0.05f
    }
}
