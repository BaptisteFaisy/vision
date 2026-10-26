package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth

/** Frame de test : mêmes dimensions que le direct réel (512×1138, un objet). */
internal val SAMPLE_FRAME = LiveFrame(width = 512, height = 1138, elapsedMs = 118, objects = emptyList())

/** Health de test : conteneur chaud, GPU A100. */
internal val SAMPLE_HEALTH = LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100")

/**
 * Client live de test, sans réseau : compte les appels et renvoie le scénario fourni.
 * Utilisé par [WsLiveClientTest] — une seule implémentation de faux client.
 */
internal class RecordingLiveClient(
    private val frames: (String) -> Result<LiveFrame> = { Result.success(SAMPLE_FRAME) },
) : LiveClient {

    var liveCalls = 0
        private set
    var healthCalls = 0
        private set
    var closed = false
        private set

    /** Dernière image reçue, pour vérifier ce qui a été transmis au transport. */
    var lastJpeg: String? = null
        private set

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        liveCalls++
        lastJpeg = jpegB64
        return frames(jpegB64)
    }

    override suspend fun health(): Result<LiveHealth> {
        healthCalls++
        return Result.success(SAMPLE_HEALTH)
    }

    override fun close() {
        closed = true
    }
}
