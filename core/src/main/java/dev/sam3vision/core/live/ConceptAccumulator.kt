package dev.sam3vision.core.live

import dev.sam3vision.core.model.SegObject

/**
 * Mémoire des concepts entre deux groupes de [PromptRotation] : `concept →
 * derniers objets + horodatage`. Chaque réponse remplace les objets de ses
 * seuls concepts envoyés, et [snapshot] rend la fusion des concepts non
 * expirés. Voix, overlay et Traversée lisent cette fusion : aucun trou à
 * cause du groupe, mêmes événements qu'en batch complet.
 *
 * Un refresh vide n'efface pas : le serveur tronque au top-16 global par
 * score, un concept tournant évincé une frame reviendrait sinon « parti »
 * à tort (négatif confirmé : deux refreshes vides de suite pour effacer).
 * Expiré ([EXPIRY_MS] sans refresh) = retiré : un obstacle disparu ne hante
 * pas la voix, un concept perdu une frame survit.
 * Mémoire bornée ([MAX_OBJECTS], meilleurs scores gardés) + [reset] à chaque
 * (re)configuration : un masque périmé ne survit ni au temps ni à la séance.
 * Synchronisé : deux frames peuvent se chevaucher (double vol).
 */
class ConceptAccumulator(
    private val expiryMs: Long = EXPIRY_MS,
    private val maxObjects: Int = MAX_OBJECTS,
) {

    /**
     * Derniers objets d'un concept, datés de leur frame de refresh.
     * [misses] = refreshes vides consécutifs depuis les derniers objets.
     */
    private data class Entry(val objects: List<SegObject>, val atMs: Long, val misses: Int = 0)

    /** `concept (minuscules) → entrée` : insertion stable, lectures fusionnées. */
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Réponse d'une frame : les concepts de [sent] sont remplacés par leurs
     * objets, puis la fusion est rendue (voir [snapshot]). Un refresh vide
     * garde les objets (doute : éviction top-16 possible) ; le second vide
     * de suite les efface (négatif confirmé). Un objet dont le label ne
     * figure pas dans [sent] (écho serveur inattendu) est gardé sous son
     * propre label, jamais jeté en silence.
     */
    @Synchronized
    fun update(sent: List<String>, objects: List<SegObject>, nowMs: Long): List<SegObject> {
        val sentKeys = sent.map { it.trim().lowercase() }.toSet()
        val byLabel = objects.groupBy { it.label.trim().lowercase() }
        for (concept in sentKeys) {
            entries[concept] = refreshed(concept, byLabel[concept], nowMs)
        }
        for ((label, owned) in byLabel) {
            if (label !in sentKeys) entries[label] = Entry(owned, nowMs)
        }
        return merged(nowMs)
    }

    /**
     * Nouvelle entrée d'un concept envoyé : objets frais s'il y en a ([misses]
     * remis à zéro), sinon doute puis négatif confirmé au second vide de
     * suite. Un concept déjà expiré ne ressuscite pas ses vieux objets.
     */
    private fun refreshed(concept: String, fresh: List<SegObject>?, nowMs: Long): Entry {
        if (!fresh.isNullOrEmpty()) return Entry(fresh, nowMs)
        val previous = entries[concept]
        if (previous != null && previous.objects.isNotEmpty() &&
            nowMs - previous.atMs <= expiryMs && previous.misses < 1
        ) return previous.copy(atMs = nowMs, misses = 1)
        return Entry(emptyList(), nowMs)
    }

    /** Fusion des concepts non expirés, bornée à [maxObjects] (meilleurs scores). */
    @Synchronized
    fun snapshot(nowMs: Long): List<SegObject> = merged(nowMs)

    /** Concepts encore en mémoire (non expirés) : debug et tests. */
    @Synchronized
    fun concepts(nowMs: Long): Set<String> {
        prune(nowMs)
        return entries.keys.toSet()
    }

    /** Nouvelle séance : tout est oublié, comme avant la première frame. */
    @Synchronized
    fun reset() {
        entries.clear()
    }

    /** Fusion après purge des expirés, dans l'ordre d'insertion des concepts. */
    private fun merged(nowMs: Long): List<SegObject> {
        prune(nowMs)
        val all = entries.values.flatMap { it.objects }
        if (all.size <= maxObjects) return all
        return all.sortedByDescending { it.score }.take(maxObjects)
    }

    /** Un concept vit [expiryMs] après son refresh ; le refresh lui-même a 0 ms. */
    private fun prune(nowMs: Long) {
        val expired = entries.filterValues { nowMs - it.atMs > expiryMs }.keys
        for (key in expired) entries.remove(key)
    }

    companion object {
        /**
         * Vie d'un concept sans refresh : 2500 ms, ~4 cycles de rotation même
         * à 600 ms de cadence foule — un concept tournant survit aux
         * ralentissements, un obstacle parti ne hante la voix que 2,5 s.
         */
        const val EXPIRY_MS = 2500L

        /**
         * Objets gardés au pire : 64, 4 réponses à 16 objets — la fusion de 3
         * groupes tient sans trimmer, au-delà les meilleurs scores gagnent.
         */
        const val MAX_OBJECTS = 64
    }
}
