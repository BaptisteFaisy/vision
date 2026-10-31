package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.net.MetaRealtimeSession
import dev.sam3vision.core.net.MuseStreamSession
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dictée temps réel de l'appui long (Meta Muse) : le PCM part en WebSocket
 * **pendant** la tenue (partiels cumulatifs sous la boule), le relâchement
 * ne coûte qu'une fin de tour — au lieu d'un envoi complet + une inférence
 * sur tout l'énoncé.
 *
 * Replis (le batch actuel ne régresse jamais) : dictée Meta non configurée
 * ou drapeau coupé ⇒ `false`, l'appelant démarre les aperçus batch ;
 * session qui meurt (réseau, quota, refus) ⇒ [onFallback] redémarre les
 * aperçus batch et le moteur final retombe sur `speechClient` — le PCM
 * complet est toujours en mémoire, aucun son n'est perdu. Le micro
 * (JVM-intestable) n'est connu qu'en `setChunkListener`, injecté et faux
 * dans les tests.
 */
class IseeStreamDictation(
    private val scope: CoroutineScope,
    private val setChunkListener: (((ByteArray, Int) -> Unit)?) -> Unit,
    private val update: StateUpdate,
    private val openSession: (
        baseUrl: String,
        apiKey: String,
        model: String,
        onPartial: (String) -> Unit,
        onDeath: () -> Unit,
    ) -> MuseStreamSession = { baseUrl, apiKey, model, partial, death ->
        MetaRealtimeSession(baseUrl, apiKey, model, onPartial = partial, onError = { death() })
    },
) {
    @Volatile
    private var session: MuseStreamSession? = null
    @Volatile
    private var fallback: (() -> Unit)? = null

    /** Génération de la tentative : un partiel/mort tardif ne touche jamais la tenue suivante. */
    @Volatile
    private var generation = 0L

    /**
     * Ouvre une session temps réel ; `true` = tentative lancée, `false` =
     * dictée Meta non configurée / drapeau coupé (l'appelant démarre les
     * aperçus batch). [onFallback] redémarre les aperçus batch si la
     * session meurt en route.
     */
    fun start(settings: Settings, onFallback: () -> Unit): Boolean {
        stop()
        if (!settings.museRealtimeReady) return false
        val attempt = ++generation
        val stream = openSession(settings.asrBaseUrl, settings.asrApiKey, settings.asrModel, ::onPartial) {
            onDeath(attempt)
        }
        session = stream
        fallback = onFallback
        setChunkListener { bytes, count -> feed(stream, attempt, bytes, count) }
        scope.launch {
            if (stream.start().isFailure) onDeath(attempt)
        }
        return true
    }

    /**
     * Moteur final de la tenue : la fin de tour quand la session vit (repli
     * batch sur le WAV complet si elle meurt entre le choix et l'envoi), le
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
    private fun feed(stream: MuseStreamSession, attempt: Long, bytes: ByteArray, count: Int) {
        if (attempt != generation || session !== stream) return
        if (!stream.sendPcm(bytes, count)) onDeath(attempt)
    }

    /** Partiel cumulatif (thread lecteur) : vivant seulement tant que le doigt est posé. */
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
 * Moteur final du stream : le WAV est déjà parti morceau par morceau, seule
 * la fin de tour part. Une fin de tour en échec retombe sur le batch avec
 * le WAV complet (mort entre le choix du moteur et l'envoi) : le texte du
 * tour ou le texte batch, jamais un trou.
 */
internal class StreamingFinalClient(
    private val session: MuseStreamSession,
    private val batch: SpeechClient?,
) : SpeechClient {
    override suspend fun transcribe(wav: ByteArray): Result<String> =
        session.endStreamAndFinal().fold(
            onSuccess = { Result.success(it) },
            onFailure = { batch?.transcribe(wav) ?: Result.failure(it) },
        )
}
