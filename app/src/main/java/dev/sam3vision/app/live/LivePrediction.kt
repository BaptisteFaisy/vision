package dev.sam3vision.app.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.predict.BoxPredictor
import dev.sam3vision.core.predict.ObjectAssociator
import dev.sam3vision.core.predict.PredictedObject
import dev.sam3vision.core.predict.ServerObject
import dev.sam3vision.core.predict.SyncReport
import dev.sam3vision.core.predict.serverObjects

/**
 * Une boîte prédite, prête à dessiner : rectangle dans le repère de l'image analysée,
 * couleur du masque de son objet ([colorIndex] = rang de l'objet dans la réponse) et
 * **confiance** (1 = fraîche, 0 = morte : l'overlay l'efface plutôt que de mentir).
 */
data class PredictedBox(
    val id: Int,
    val label: String,
    val colorIndex: Int,
    val confidence: Float,
    val box: Box,
)

/**
 * Couche de prédiction d'une frame caméra : les boîtes à poser sur l'aperçu.
 * [empty] = rien à dessiner — l'overlay retombe sur les masques serveur (repli).
 */
data class PredictionLayer(
    val width: Int = 0,
    val height: Int = 0,
    val boxes: List<PredictedBox> = emptyList(),
) {
    val empty: Boolean get() = width <= 0 || height <= 0 || boxes.isEmpty()
}

/**
 * Branchement du paquet `core/predict` dans le Direct (T8) — **derrière un flag éteint
 * par défaut** ([enabled] = `Settings.boxPredictionEnabled`).
 *
 * Le mode Direct ne renvoie aucun id serveur : [associator] en fabrique un stable
 * (recouvrement IoU), [predictor] en garde l'ancre et la vitesse, et l'overlay ne
 * dessine une boîte que lorsque le masque serveur **n'est plus frais**
 * ([PredictedObject.useMask]) — la prédiction comble l'intervalle, elle ne remplace
 * jamais la mesure.
 *
 * Repli : flag éteint, aucun objet suivi ou aucune boîte morte ⇒ **couche vide**, et
 * l'écran reste exactement celui d'avant (les masques serveur seuls).
 *
 * Pur JVM : ni Android, ni Compose — testable sans appareil.
 */
class LivePrediction(
    val enabled: Boolean = false,
    private val predictor: BoxPredictor = BoxPredictor(),
    private val associator: ObjectAssociator = ObjectAssociator(),
    private val dropAfterMs: Long = BoxPredictor.DEFAULT_DROP_MS,
) {

    /** Un id suivi : sa dernière boîte serveur, la couleur de son masque, son passage. */
    private class Followed(val server: ServerObject, val colorIndex: Int, val seenAtMs: Long)

    private val lock = Any()
    private val followed = LinkedHashMap<Int, Followed>()

    /** Nombre d'ids suivis localement (0 tant que le flag est éteint). */
    val trackedCount: Int get() = synchronized(lock) { followed.size }

    /**
     * Absorbe une réponse serveur datée de [timestampMs] : association des objets à un
     * id stable, puis mise à jour de l'ancre et de la vitesse. Renvoie `null` quand le
     * flag est éteint : rien n'est suivi, rien n'est calculé.
     */
    fun absorb(frame: LiveFrame, timestampMs: Long, width: Int, height: Int): SyncReport? {
        if (!enabled) return null
        val identified = identify(frame, timestampMs)
        return predictor.onServer(identified, timestampMs, width, height)
    }

    /**
     * Boîtes à poser sur la frame caméra de [timestampMs] : celles dont le masque
     * serveur n'est **plus frais** et dont la prédiction vit encore. Couche vide si le
     * flag est éteint ou si rien n'a vieilli — l'overlay retombe alors sur les masques.
     */
    fun boxesAt(timestampMs: Long, width: Int, height: Int): PredictionLayer {
        if (!enabled) return PredictionLayer()
        val stale = predictor.predictAt(timestampMs, width, height).filter { !it.useMask && it.confidence > 0f }
        if (stale.isEmpty()) return PredictionLayer()
        return PredictionLayer(width, height, boxes(stale))
    }

    /** Oublie tout : arrêt du direct, changement de concepts, nouvelle session. */
    fun reset() {
        predictor.reset()
        synchronized(lock) { followed.clear() }
    }

    /**
     * Objets de la réponse avec un **id stable** : l'association par recouvrement
     * reprend les ids connus, un objet nouveau reçoit le plus petit id libre. Le rang
     * de l'objet (sa couleur de masque) est retenu au passage.
     */
    private fun identify(frame: LiveFrame, timestampMs: Long): List<ServerObject> {
        val server = frame.serverObjects()
        val known = synchronized(lock) { followed.mapValues { it.value.server } }
        val byObject = associator.assign(server, known).entries.associate { (id, obj) -> obj to id }
        val used = HashSet(known.keys)
        val identified = ArrayList<Pair<Int, ServerObject>>(server.size)
        server.forEach { obj ->
            val id = byObject[obj] ?: associator.freeId(used).also { used += it }
            identified += obj.id to obj.copy(id = id)
        }
        remember(identified, timestampMs)
        return identified.map { it.second }
    }

    /**
     * Retient, pour chaque id stable, sa dernière boîte serveur (entrée de l'association
     * suivante), sa couleur et son dernier passage ; puis oublie les ids que le
     * prédicteur a lâchés depuis plus de [dropAfterMs].
     */
    private fun remember(identified: List<Pair<Int, ServerObject>>, timestampMs: Long) {
        synchronized(lock) {
            identified.forEach { (rank, obj) -> followed[obj.id] = Followed(obj, rank, timestampMs) }
            val iterator = followed.entries.iterator()
            while (iterator.hasNext()) {
                if (timestampMs - iterator.next().value.seenAtMs > dropAfterMs) iterator.remove()
            }
        }
    }

    /** Boîtes prédites, colorées comme le masque de leur objet. */
    private fun boxes(shown: List<PredictedObject>): List<PredictedBox> {
        val colors = synchronized(lock) { followed.mapValues { it.value.colorIndex } }
        return shown.map {
            PredictedBox(
                id = it.id,
                label = it.label,
                colorIndex = colors[it.id] ?: 0,
                confidence = it.confidence,
                box = it.box,
            )
        }
    }
}
