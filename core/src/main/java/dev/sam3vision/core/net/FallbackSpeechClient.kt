package dev.sam3vision.core.net

/**
 * Chaîne de transcription : tente [primary] (Meta), puis [fallback] (ElevenLabs)
 * quand le premier échoue — facturation, panne réseau, clé absente.
 *
 * Sans repli configuré, l'erreur du primaire remonte telle quelle. Si les deux
 * échouent, [ApiError.AllProviders] expose les deux messages dans l'ordre essayé,
 * pour que l'utilisateur sache quoi corriger.
 */
class FallbackSpeechClient(
    private val primary: SpeechClient,
    private val fallback: SpeechClient? = null,
    private val primaryName: String = "Meta",
    private val fallbackName: String = "ElevenLabs",
) : SpeechClient {

    override suspend fun transcribe(wav: ByteArray): Result<String> {
        val first = primary.transcribe(wav)
        first.fold(onSuccess = { return Result.success(it) }, onFailure = { })
        val second = fallback ?: return first
        return second.transcribe(wav).fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                Result.failure(
                    ApiError.AllProviders("$primaryName : ${first.exceptionOrNull()?.message} — $fallbackName : ${error.message}"),
                )
            },
        )
    }
}