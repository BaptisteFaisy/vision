package dev.sam3vision.core.usecase

import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.TtsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faux client TTS : compte les appels et rend des octets reconnaissables. */
private class FakeTts(
    private val reply: (String) -> Result<ByteArray> = { Result.success("mp3:${it.length}".toByteArray()) },
) : TtsClient {
    val texts = mutableListOf<String>()

    override suspend fun synthesize(text: String): Result<ByteArray> {
        texts += text
        return reply(text)
    }
}

class SpeechUseCaseTest {

    private fun freshCache() = object : LinkedHashMap<String, List<ByteArray>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ByteArray>>?) = false
    }

    @Test
    fun `un texte long est synthetise morceau par morceau dans l'ordre`() {
        val tts = FakeTts()
        val cache = freshCache()
        val text = "Première phrase. Deuxieme phrase. Troisième phrase."
        val audio = runBlocking { SpeechUseCase(tts, maxChars = 19, cache = cache).invoke(text) }.getOrThrow()
        assertEquals(3, audio.size)
        assertEquals(listOf("Première phrase.", "Deuxieme phrase.", "Troisième phrase."), tts.texts)
    }

    @Test
    fun `la meme réponse rejouee ne re-consomme pas de caracteres`() {
        val tts = FakeTts()
        val cache = freshCache()
        val useCase = SpeechUseCase(tts, cache = cache, cacheKeyPrefix = "voice\u0001eleven_v3")
        runBlocking { useCase.invoke("Une description de la photo.") }
        val again = runBlocking { useCase.invoke("Une description de la photo.") }
        assertEquals(1, tts.texts.size)
        assertEquals(1, again.getOrThrow().size)
    }

    @Test
    fun `changer de voix invalide le cache`() {
        val tts = FakeTts()
        val cache = freshCache()
        runBlocking { SpeechUseCase(tts, cache = cache, cacheKeyPrefix = "jade").invoke("bonjour") }
        runBlocking { SpeechUseCase(tts, cache = cache, cacheKeyPrefix = "sarah").invoke("bonjour") }
        assertEquals(2, tts.texts.size)
    }

    @Test
    fun `une erreur de synthese remonte et coupe la serie`() {
        val tts = FakeTts { i ->
            if (i.contains("deuxieme")) Result.failure(ApiError.PaymentRequired()) else Result.success(ByteArray(1))
        }
        val cache = freshCache()
        val error = runBlocking {
            SpeechUseCase(tts, maxChars = 12, cache = cache).invoke("premiere. deuxieme. troisieme.")
        }.exceptionOrNull()
        assertTrue(error is ApiError.PaymentRequired)
        assertEquals(listOf("premiere.", "deuxieme."), tts.texts)
    }

    @Test
    fun `un texte blanc n'est pas une erreur`() {
        val tts = FakeTts()
        val audio = runBlocking { SpeechUseCase(tts, cache = freshCache()).invoke("   ") }.getOrThrow()
        assertTrue(audio.isEmpty())
        assertTrue(tts.texts.isEmpty())
    }

    @Test
    fun `cache desactive = synthese a chaque demande`() {
        val tts = FakeTts()
        val useCase = SpeechUseCase(tts, cacheEnabled = false, cache = freshCache())
        runBlocking { useCase.invoke("encore") }
        runBlocking { useCase.invoke("encore") }
        assertEquals(2, tts.texts.size)
    }

    @Test
    fun `le cache LRU borne le nombre de reponses retenues`() {
        val cache = SpeechCache.slices
        cache.clear()
        repeat(SpeechCache.MAX_ENTRIES + 3) { index ->
            SpeechCache.put(cache, "cle$index", listOf(ByteArray(1)))
        }
        assertEquals(SpeechCache.MAX_ENTRIES, cache.size)
        assertTrue(!cache.containsKey("cle0"))
        assertEquals(7, SpeechCache.bytesOf(listOf(ByteArray(7))))
    }
}
