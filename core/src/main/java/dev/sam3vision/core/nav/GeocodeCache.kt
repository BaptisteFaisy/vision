package dev.sam3vision.core.nav

/**
 * Cache mémoire des adresses récentes : re-demander « gare de Lyon » ne
 * re-touche ni le géocodeur ni le réseau. LRU borné, clé = requête
 * normalisée, les entrées étant minuscules (quelques dizaines d'octets)
 * la capacité couvre une session de dictées.
 *
 * Thread-safe : chat, navigation et dictées partagent [shared] depuis des
 * threads et coroutines différents (un `LinkedHashMap` nu lèverait
 * `ConcurrentModificationException`). Chaque accès est synchronisé sur
 * l'instance ; toute itération directe exige `synchronized(cache) { … }`.
 */
object GeocodeCache {
    /** Résultats récents gardés : au-delà, le plus ancien tombe. */
    const val MAX_ENTRIES = 30

    /**
     * LRU dont `get`/`put`/`remove`/`clear`/`containsKey`/`size` sont
     * synchronisés sur l'instance. Sous-classe (et non décorateur) pour
     * garder le type `LinkedHashMap` des appelants ([GeocodeChain]).
     */
    private class SyncedLruMap : LinkedHashMap<String, GeocodedPlace>(32, 0.75f, true) {
        @Synchronized
        override fun get(key: String): GeocodedPlace? = super.get(key)

        @Synchronized
        override fun put(key: String, value: GeocodedPlace): GeocodedPlace? = super.put(key, value)

        @Synchronized
        override fun remove(key: String): GeocodedPlace? = super.remove(key)

        @Synchronized
        override fun clear() = super.clear()

        @Synchronized
        override fun containsKey(key: String): Boolean = super.containsKey(key)

        @Synchronized
        override fun isEmpty(): Boolean = super.isEmpty()

        override val size: Int
            @Synchronized get() = super.size
    }

    /** Cache frais (tests, ou instance isolée). Accès = rafraîchissement LRU. */
    fun fresh(): LinkedHashMap<String, GeocodedPlace> = SyncedLruMap()

    /** Partagé : chat, navigation et dictées rejouent le même résultat. */
    val shared: LinkedHashMap<String, GeocodedPlace> = fresh()

    /**
     * Insertion + éviction explicite (le LRU d'accès ne borne pas la
     * taille). Le parcours d'éviction tient le verrou de la map, aucun
     * autre accès synchronisé ne peut s'intercaler.
     */
    fun put(cache: LinkedHashMap<String, GeocodedPlace>, key: String, value: GeocodedPlace) {
        synchronized(cache) {
            cache[key] = value
            while (cache.size > MAX_ENTRIES) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }
}
