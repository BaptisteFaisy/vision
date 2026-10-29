package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject

/**
 * Ce qu'une frame donne à **lire** (ses étiquettes) et à **dire** (ses objets placés).
 *
 * Sorti de `LiveProbe` pour deux raisons : ce ViewModel doit rester sous la limite de
 * lignes, et surtout cette logique est pure — donc mesurable et verrouillable en JVM,
 * sans téléphone ni réseau.
 */

/**
 * Étiquettes d'une frame : doublons retirés, ordre de première apparition conservé.
 *
 * Un ensemble **réutilisé** d'une frame à l'autre plutôt que
 * `objects.map { it.label }.distinct()`, qui construisait une liste, puis un ensemble,
 * puis la liste finale — trois allocations par frame publiée. Mesuré au banc JVM
 * (`LiveFrameBudgetTest`) : 583 → 215 octets par frame.
 */
class FrameLabels {

    private val seen = LinkedHashSet<String>()

    /** Étiquettes de [objects], sans doublon. La liste rendue est neuve (elle part en état). */
    fun of(objects: List<SegObject>): List<String> {
        seen.clear()
        var i = 0
        while (i < objects.size) {
            seen.add(objects[i].label)
            i++
        }
        return seen.toList()
    }
}

/**
 * Table boîte → mètres, la plus petite structure qui réponde à la question.
 *
 * **Le balayage direct a été essayé et rejeté, chiffres en main.** Comparer la boîte de
 * chaque objet à celles de toutes les lectures est en `n²` comparaisons *structurelles*
 * (`List<Float>.equals` compare les quatre flottants quand les instances diffèrent), soit
 * 7,0 µs et 4 423 octets pour 12 objets, contre 4,5 µs et 1 775 octets pour la table.
 * L'identité ne sauve rien : la lecture qui correspond partage bien la boîte de l'objet
 * (`DistanceReading(…, detection.box)`), mais toutes celles qui la précèdent sont
 * différentes et se comparent quand même. Mesure : [LiveFrameBudgetTest].
 */
private fun metersByBox(readings: List<DistanceReading>): Map<List<Float>, Float> =
    readings.associate { it.box to it.meters }

/**
 * Objets de [frame] placés pour l'annonce vocale, chacun avec sa distance quand elle est
 * mesurée. Un objet dont la géométrie est inutilisable est simplement omis.
 *
 * Le `PlacedLabel` est construit **avec** sa distance et ses bords : la version
 * précédente le construisait puis le recopiait (`copy(meters = …)`), soit un objet de
 * plus par objet détecté et par frame.
 */
fun placedLabels(frame: LiveFrame, readings: List<DistanceReading>): List<PlacedLabel> {
    val meters = metersByBox(readings)
    val objects = frame.objects
    val placed = ArrayList<PlacedLabel>(objects.size)
    var i = 0
    while (i < objects.size) {
        val obj = objects[i]
        val detection = Detection(obj.label, obj.score, obj.box, frame.width, frame.height)
        val position = PositionText.fromDetection(detection)
        if (position != null) {
            placed += PlacedLabel(
                position.label, position.centerX, position.centerY, meters[obj.box], position.x0, position.x1,
            )
        }
        i++
    }
    return placed
}