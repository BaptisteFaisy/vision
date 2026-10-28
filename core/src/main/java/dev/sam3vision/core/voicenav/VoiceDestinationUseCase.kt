package dev.sam3vision.core.voicenav

import dev.sam3vision.core.nav.DestinationQuery

/**
 * Entrée vocale d'une destination : PCM brut du micro → destination reconnue.
 *
 * Chaîne complète, sans rien réécrire : la dictée (PCM → WAV → STT Meta puis
 * repli Scribe) reste dans [dev.sam3vision.core.usecase.DictationUseCase], la
 * capture micro dans l'app (`MuseTranscript` / `DictationRecorder`, arbitrée
 * par `MicBus`) ; ce cas d'usage ajoute la reconnaissance d'intention
 * ([VoiceDestinationParser]) et la normalisation ([DestinationQuery.normalize]).
 *
 * ## API pour l'UI (rédacteur de l'écran navigation)
 *
 * ```kotlin
 * val speech = speechClient(settings) ?: return // Meta puis Scribe, null si rien configuré
 * val dictate = DictationUseCase(speech)::invoke // PCM 16 kHz mono 16 bits
 * val useCase = VoiceDestinationUseCase(dictate)
 *
 * // Hold-to-talk existant : start() à l'appui, pcm au relâchement.
 * val recorder = DictationRecorder() // PcmSource, arbitré par MicBus
 * recorder.start()
 * // … au relâchement :
 * val pcm: ByteArray = recorder.stopAndCollect() // abort() pour jeter
 * val result: Result<VoiceDestinationUseCase.VoiceDestination> = useCase.invoke(pcm)
 * result
 *     .onSuccess { (destination, transcript) ->
 *         // destination : « gare de Lyon » (normalisée, prête pour Maps) ;
 *         // transcript : texte STT brut (lecture vocale « Navigation vers … »).
 *     }
 *     .onFailure { error ->
 *         // error.message est affichable/annonçable tel quel, en français :
 *         // trop court, aucune parole, STT injoignable, ou pas de destination.
 *     }
 * ```
 *
 * Règles : [dictate] injectée (les tests branchent une doublure, l'app branche
 * `DictationUseCase`) ; [parse] et [normalize] injectés avec leurs défauts ;
 * jamais d'exception — l'échec (STT, pas d'intention, normalisation vide) se
 * rend par [Result.failure] avec un message français.
 */
class VoiceDestinationUseCase(
    private val dictate: suspend (ByteArray) -> Result<String>,
    private val parse: (String) -> String? = VoiceDestinationParser::parse,
    private val normalize: (String) -> Result<String> = DestinationQuery::normalize,
) {

    /** Destination reconnue : [destination] normalisée, [transcript] STT brut rogné. */
    data class VoiceDestination(val destination: String, val transcript: String)

    suspend fun invoke(pcm: ByteArray): Result<VoiceDestination> {
        val transcript = dictate(pcm).getOrElse { return Result.failure(it) }
        val raw = runCatching { parse(transcript) }.getOrNull()
            ?: return Result.failure(VoiceDestinationError.NoDestination())
        val clean = runCatching { normalize(raw) }.getOrNull()?.getOrNull()
            ?: return Result.failure(VoiceDestinationError.NoDestination())
        return Result.success(VoiceDestination(clean, transcript.trim()))
    }
}

/**
 * Erreurs de l'entrée vocale, message directement affichable dans l'UI.
 * Jamais levées : renvoyées par [Result.failure].
 */
sealed class VoiceDestinationError(message: String) : Exception(message) {

    /** La dictée ne contient aucun ordre de navigation reconnu. */
    class NoDestination :
        VoiceDestinationError("aucune destination reconnue : dis par exemple « direction gare de Lyon »")
}
