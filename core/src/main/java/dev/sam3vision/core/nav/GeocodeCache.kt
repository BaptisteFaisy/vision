package dev.sam3vision.core.nav

/**
 * Cache mémoire des adresses récentes : re-demander « gare de Lyon » ne
 * re-touche ni le géocodeur ni le réseau. Même idiome que [dev.sam3vision.core.usecase.SpeechCache]
 * (LRU borné, clé = requête normalisée), les entrées étant minuscules (quelques
 * dizaines d'octets) la capacité couvre une session de dictées.
 */
object GeocodeCache {
    /** Résultats récents gardés : au-delà, le plus ancien tombe. */
    const val MAX_ENTRIES = 30

    /** Cache frais (tests, ou instance isolée). Accès = rafraîchissement LRU. */
    fun fresh(): LinkedHashMap<String, GeocodedPlace> =
        LinkedHashMap(32, 0.75f, true)

    /** Partagé : chat, navigation et dictées rejouent le même résultat. */
    val shared: LinkedHashMap<String, GeocodedPlace> = fresh()

    /** Insertion + éviction explicite (le LRU d'accès ne borne pas la taille). */
    fun put(cache: LinkedHashMap<String, GeocodedPlace>, key: String, value: GeocodedPlace) {
        cache[key] = value
        while (cache.size > MAX_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }
}
