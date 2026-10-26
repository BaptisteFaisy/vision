package dev.sam3vision.core.predict

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