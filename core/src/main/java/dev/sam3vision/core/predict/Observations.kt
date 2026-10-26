package dev.sam3vision.core.predict

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.TrackedObject
import dev.sam3vision.core.model.TrackFrame

/**
 * Pont entre le protocole (`box: List<Float>`) et le prédicteur ([ServerObject]).
 * Un objet dont la boîte est illisible est ignoré : mieux vaut un id de moins qu'une
 * boîte aberrante qui ferait sauter le modèle.
 */
fun TrackedObject.toServerObject(): ServerObject? {
    val parsed = Box.fromProto(box) ?: return null
    return ServerObject(id = id, label = label, score = score, box = parsed)
}

/** Objets exploitables d'une réponse `/frame`, dans l'ordre du serveur. */
fun TrackFrame.serverObjects(): List<ServerObject> = objects.mapNotNull { it.toServerObject() }

/**
 * Objets exploitables d'une réponse `/live`, dans l'ordre du serveur.
 *
 * Le mode Direct ne renvoie **aucun id** : le rang de l'objet dans la réponse tient
 * lieu d'identité provisoire — il sert aussi de **couleur de masque** (le masque
 * `i` est peint de la couleur `i`). La continuité dans le temps (« le même objet
 * d'une frame à l'autre ») est rétablie en amont, par
 * [ObjectAssociator] (recouvrement) puis par [BoxPredictor].
 */
fun LiveFrame.serverObjects(): List<ServerObject> =
    objects.mapIndexedNotNull { rank, obj -> obj.toServerObject(rank) }

/** Un objet de `/live` converti pour le prédicteur ; `rank` = position chez le serveur. */
private fun SegObject.toServerObject(rank: Int): ServerObject? {
    val parsed = Box.fromProto(box) ?: return null
    return ServerObject(id = rank, label = label, score = score, box = parsed)
}