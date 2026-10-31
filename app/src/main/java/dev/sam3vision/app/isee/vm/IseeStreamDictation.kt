package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.net.ScribeRealtimeSession
import dev.sam3vision.core.net.ScribeStreamSession
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dictée temps réel de l'appui long : le PCM part en WebSocket **pendant**
 * la tenue (partiels sous la boule, ~150 ms annoncés), le relâchement ne
 * coûte qu'un commit — au lieu d'un envoi complet + une inférence complète.
 *
 * Replis (le batch actuel ne régresse jamais) : pas de clé ou drapeau
 * coupé ⇒ `false`, l'appelant démarre les aperçus batch ; session qui meurt
 * (réseau, quota, refus) ⇒ [onFallback] redémarre les aperçus batch et le
 * moteur final retombe sur `speechClient` — le PCM complet est toujours en
 * mémoire, aucun son n'est perdu. Le micro (JVM-intestable) n'est connu
 * qu'en `setChunkListener`, injecté et faux dans les tests.
 */
class IseeStreamDictation(
    private val scope: CoroutineScope,
    private val setChunkListener: (((ByteArray, Int) -> Unit)?) -> Unit,
    private val update: StateUpdate,
    private val openSession: (
        apiKey: String,
        onPartial: (String) -> Unit,
        onDeath: () -> Unit,
    ) -> ScribeStreamSession = { key, partial, death ->
        ScribeRealtimeSession(key, onPartial = partial, onError = { death() })
    },
) {
    @Volatile
    private var session: ScribeStreamSession? = null
    @Volatile
    private var fallback: (() -> Unit)? = null

    /** Génération de la tentative : un partiel/mort tardif ne touche jamais la tenue suivante. */
    @Volatile
    private var generation = 0L

    /**
     * Ouvre une session temps réel ; `true` = tentative lancée, `false` =
     * pas de clé / drapeau coupé (l'appelant démarre les aperçus batch).
     * [onFallback] redémarre les aperçus batch si la session meurt en route.
     */
    fun start(settings: Settings, onFallback: () -> Unit): Boolean {
        stop()
        if (!settings.scribeRealtimeReady) return false
        val attempt = ++generation
        val stream = openSession(settings.ttsApiKey, ::onPartial) { onDeath(attempt) }
        session = stream
        fallback = onFallback
        setChunkListener { bytes, count -> feed(stream, attempt, bytes, count) }
        scope.launch {
            if (stream.start().isFailure) onDeath(attempt)
        }
        return true
    }

    /**
     * Moteur final de la tenue : le commit quand la session vit (repli batch
     * sur le WAV complet si le commit meurt entre le choix et l'envoi), le
     * batch directement sinon. Jamais `null` quand [batch] ne l'est pas.
     */
    fun finalClient(batch: SpeechClient?): SpeechClient? {
        val stream = session
        if (stream == null || !stream.isLive) return batch
        return StreamingFinalClient(stream, batch)
    }

    /** Annulation, relâchement traité, fin d'écran : session fermée, écran nettoyé. */
    fun stop() {
        generation++
        session?.abort()
        session = null
        fallback = null
        setChunkListener(null)
        update { it.copy(userSpeaking = false, liveTranscript = "") }
    }

    /** Morceau de pompe (thread micro) : copié par la session, jamais conservé ici. */
    private fun feed(stream: ScribeStreamSession, attempt: Long, bytes: ByteArray, count: Int) {
        if (attempt != generation || session !== stream) return
        if (!stream.sendPcm(bytes, count)) onDeath(attempt)
    }

    /** Partiel (thread lecteur) : vivant seulement tant que le doigt est posé. */
    private fun onPartial(text: String) {
        update { if (it.holding) it.copy(userSpeaking = true, liveTranscript = text) else it }
    }

    /** Session morte : repli batch (aperçus + moteur final), une seule fois par tenue. */
    private fun onDeath(attempt: Long) {
        if (attempt != generation) return
        generation++
        session = null
        val retry = fallback
        fallback = null
        setChunkListener(null)
        update { it.copy(userSpeaking = false, liveTranscript = "") }
        retry?.invoke()
    }
}

/**
 * Moteur final du stream : le WAV est déjà parti morceau par morceau, seul
 * le commit part. Un commit en échec retombe sur le batch avec le WAV
 * complet (mort entre le choix du moteur et l'envoi) : le texte committed
 * ou le texte batch, jamais un trou.
 */
internal class StreamingFinalClient(
    private val session: ScribeStreamSession,
    private val batch: SpeechClient?,
) : SpeechClient {
    override suspend fun transcribe(wav: ByteArray): Result<String> =
        session.commitAndFinal().fold(
            onSuccess = { Result.success(it) },
            onFailure = { batch?.transcribe(wav) ?: Result.failure(it) },
        )
}
