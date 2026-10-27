package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WireMarks
import kotlinx.coroutines.CompletableDeferred

/**
 * Client de test sans transport : chaque `live` consigne son envoi — avec le nombre de
 * frames déjà publiées à cet instant — et **attend** que le test livre la réponse, dans
 * l'ordre et au moment qu'il choisit.
 */
internal class ProbeTestClient(private val journal: MutableList<String>) : LiveClient {

    /** Chronologie de l'écran : le transport daterait ici la fin du corps. */
    var marks: WireMarks? = null

    /** Frames publiées à l'instant de l'envoi : la preuve que le cycle ne s'arrête pas. */
    var publishedFrames: () -> Int = { -1 }

    var sent = 0
        private set

    private val waiting = mutableMapOf<String, CompletableDeferred<Result<LiveFrame>>>()

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        val slot = CompletableDeferred<Result<LiveFrame>>()
        waiting[jpegB64] = slot
        sent += 1
        journal += "envoi $jpegB64 (frames=${publishedFrames()})"
        return slot.await()
    }

    /** Livre la réponse de [jpeg] : c'est le test qui décide de l'ordre des frames. */
    fun respond(jpeg: String, frame: LiveFrame) {
        val slot = waiting.remove(jpeg) ?: error("aucune frame « $jpeg » en attente")
        marks?.onBody()
        journal += "reponse $jpeg"
        slot.complete(Result.success(frame))
    }

    override suspend fun health(): Result<LiveHealth> =
        Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
}
