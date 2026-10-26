package dev.sam3vision.core.usecase

import dev.sam3vision.core.audio.SentenceChunker
import dev.sam3vision.core.net.TtsClient

/**
 * Texte de réponse -> morceaux MP3 prêts à être joués dans l'ordre.
 *
 * [invoke] découpe le texte en phrases ([SentenceChunker]), synthétise chaque
 * morceau et s'arrête à la première erreur, remontée telle quelle. Les morceaux
 * déjà fabriqués pour le même texte sont rendus par le [cache] si [cacheEnabled] :
 * rejouer une réponse ne re-consomme pas de caractères ElevenLabs. Un texte blanc
 * n'est pas une erreur — liste vide, l'écran masque alors la lecture.
 */
class SpeechUseCase(
    private val tts: TtsClient,
    private val maxChars: Int = SentenceChunker.MAX_CHARS,
    private val cacheEnabled: Boolean = true,
    private val cache: LinkedHashMap<String, List<ByteArray>> = SpeechCache.slices,
    private val cacheKeyPrefix: String = "",
) {
    suspend fun invoke(text: String): Result<List<ByteArray>> {
        val parts = SentenceChunker.chunk(text, maxChars)
        if (parts.isEmpty()) return Result.success(emptyList())
        val key = SpeechCache.key(cacheKeyPrefix, parts)
        if (cacheEnabled) cache[key]?.let { return Result.success(it) }
        val audio = mutableListOf<ByteArray>()
        for (part in parts) {
            val slice = tts.synthesize(part).getOrElse { return Result.failure(it) }
            audio += slice
        }
        if (cacheEnabled) SpeechCache.put(cache, key, audio)
        return Result.success(audio)
    }
}

/**
 * Cache LRU borné : au-delà de [MAX_ENTRIES] réponses, la plus ancienne tombe.
 * La clé porte la voix et le modèle ([key]) : changer de voix dans Réglages ne
 * rejoue pas l'ancienne synthèse.
 */
object SpeechCache {
    const val MAX_ENTRIES = 6

    /** Partagé : la même réponse rejouée depuis le chat ou l'analyse est gratuite. */
    val slices: LinkedHashMap<String, List<ByteArray>> =
        object : LinkedHashMap<String, List<ByteArray>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ByteArray>>?): Boolean =
                size > MAX_ENTRIES
        }

    /** Insertion + éviction explicites (le LRU d'accès ne suffit pas à borner la taille). */
    fun put(cache: LinkedHashMap<String, List<ByteArray>>, key: String, value: List<ByteArray>) {
        cache[key] = value
        while (cache.size > MAX_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    /** Poids en octets d'une entrée (contrôle du coût mémoire dans les tests). */
    fun bytesOf(value: List<ByteArray>): Int = value.sumOf { it.size }

    /** Clé de cache : [prefix] (voix + modèle) puis les morceaux de texte. */
    fun key(prefix: String, parts: List<String>): String =
        (listOf(prefix) + parts).joinToString("\u0000")
}
