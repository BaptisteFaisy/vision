package dev.sam3vision.core.voicenav

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille l'entrée vocale complète ([VoiceDestinationUseCase]) : PCM →
 * dictée injectée → intention → destination normalisée.
 *
 * Contrat : succès = destination propre + transcript brut ; tout échec (dictée,
 * pas d'intention, normalisation vide) = [Result.failure] avec un message
 * français, jamais d'exception.
 */
class VoiceDestinationUseCaseTest {

    private val pcm = ByteArray(16)

    private fun useCase(
        dictate: suspend (ByteArray) -> Result<String>,
    ): VoiceDestinationUseCase = VoiceDestinationUseCase(dictate)

    @Test
    fun `la dictee reconnue rend destination et transcript`() = runBlocking {
        val result = useCase({ Result.success("direction gare de Lyon") }).invoke(pcm)

        assertTrue(result.isSuccess)
        val found = result.getOrThrow()
        assertEquals("gare de Lyon", found.destination)
        assertEquals("direction gare de Lyon", found.transcript)
    }

    @Test
    fun `la destination est normalisee et le transcript rogne`() = runBlocking {
        val result =
            useCase({ Result.success("  je veux aller à   la gare de Lyon  ") }).invoke(pcm)

        assertTrue(result.isSuccess)
        val found = result.getOrThrow()
        assertEquals("la gare de Lyon", found.destination)
        assertEquals("je veux aller à   la gare de Lyon", found.transcript)
    }

    @Test
    fun `l echec de dictee est propage tel quel`() = runBlocking {
        val failure = Result.failure<String>(Exception("dictée impossible : Meta KO, Scribe KO"))
        val result = useCase({ failure }).invoke(pcm)

        assertTrue(result.isFailure)
        assertEquals("dictée impossible : Meta KO, Scribe KO", result.exceptionOrNull()?.message)
    }

    @Test
    fun `une question echoue en francais sans exception`() = runBlocking {
        val result = useCase({ Result.success("où est la pharmacie ?") }).invoke(pcm)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is VoiceDestinationError.NoDestination)
        assertEquals(
            "aucune destination reconnue : dis par exemple « direction gare de Lyon »",
            error?.message,
        )
    }

    @Test
    fun `un transcript vide echoue en pas de destination`() = runBlocking {
        val result = useCase({ Result.success("   ") }).invoke(pcm)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VoiceDestinationError.NoDestination)
    }

    @Test
    fun `une doublure de parse est honoree`() = runBlocking {
        val useCase = VoiceDestinationUseCase(
            dictate = { Result.success("n'importe quoi") },
            parse = { "12 rue de la Paix" },
        )

        val result = useCase.invoke(pcm)

        assertTrue(result.isSuccess)
        assertEquals("12 rue de la Paix", result.getOrThrow().destination)
    }
}
