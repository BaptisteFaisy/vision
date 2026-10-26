package dev.sam3vision.core.net

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.SegmentResult

/** Client LLM vision (InferHub, API compatible OpenAI). */
interface LlmClient {
    suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis>

    /**
     * Question/réponse sur l'image courante. [context] est un contexte libre
     * (journal caméra, notes…) joint à la question ; `null` = question simple.
     */
    suspend fun ask(
        jpegB64: String,
        question: String,
        history: List<ChatTurn>,
        context: String? = null,
    ): Result<String>
}

/** Client de segmentation SAM 3.1 (endpoint HTTP optionnel, contrat de l'ancien backend Modal). */
interface SamClient {
    suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult>

    suspend fun health(): Result<Boolean>
}

/**
 * SAM 3.1 en mode **vidéo** : le fichier enregistré est envoyé tel quel (multipart),
 * la réponse est un résumé texte court. [sendVideo] renvoie [Result.failure] avec une
 * [ApiError] typée — l'UI traduit n'importe quel échec par l'annonce « Failed ».
 */
interface VideoClient {
    suspend fun sendVideo(video: java.io.File, prompt: String?): Result<String>

    suspend fun health(): Result<Boolean>
}

/**
 * Flux live (endpoint `/live` du direct) : une frame à la fois, jamais de file d'attente.
 * [live] renvoie [Result.failure] avec une [ApiError] typée sur erreur réseau/HTTP/parsing.
 */
interface LiveClient {
    suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame>

    suspend fun health(): Result<LiveHealth>

    /** Libère les ressources (socket, connexions) : sans effet pour un client HTTP. */
    fun close() {}
}

/**
 * Synthèse vocale (ElevenLabs TTS) : la réponse du LLM devient de l'audio.
 * [synthesize] renvoie des octets MP3, ou [Result.failure] avec une [ApiError] typée —
 * notamment [ApiError.PaymentRequired] quand le compte n'a pas de crédits.
 */
interface TtsClient {
    suspend fun synthesize(text: String): Result<ByteArray>
}

/**
 * Analyse continue d'une frame du direct par un LLM vision : [labels] sont les objets
 * suivis, [previous] la description précédente (continuité « qu'est-ce qui a changé ? »).
 * Renvoie une phrase courte en français.
 */
interface LiveLlmClient {
    suspend fun describeFrame(
        jpegB64: String,
        labels: List<String>,
        previous: String,
    ): Result<String>
}

/**
 * Transcription vocale (Meta Muse ASR) : un WAV PCM 16 bits mono -> texte.
 * [transcribe] renvoie [Result.failure] avec une [ApiError] typée ; un texte
 * vide n'est pas une erreur réseau (le cas d'usage décide quoi en faire).
 */
interface SpeechClient {
    suspend fun transcribe(wav: ByteArray): Result<String>
}
