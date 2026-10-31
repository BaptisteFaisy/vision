package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.AudioFrame
import dev.sam3vision.app.PcmSource
import dev.sam3vision.core.audio.TempWavStore
import dev.sam3vision.core.audio.Wav
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Micro des lunettes Ray-Ban Meta : collecte la piste audio du flux DAT pendant qu'on parle,
 * puis écrit un WAV 16 kHz mono sous `filesDir/glasses-audio/` — le format exact que réclame
 * la transcription (aucun rééchantillonnage à refaire plus loin).
 *
 * Implémente [PcmSource], donc branchable tel quel dans la dictée existante : le transcripteur
 * reste `MuseTranscript` (`DictationUseCase` + `SpeechClient`), rien n'est réimplémenté ici.
 * Le fichier écrit sert de preuve en recette ; le PCM rendu est celui du même tampon, sans
 * relecture disque.
 */
class GlassesAudio(
    private val frames: Flow<AudioFrame>,
    private val choice: AudioChoice,
    dir: File,
    private val scope: CoroutineScope,
    private val onActive: (Boolean) -> Unit = {},
) : PcmSource {

    private val store = TempWavStore(dir, prefix = WAV_PREFIX)
    private val captured = ByteArrayOutputStream()
    private var job: Job? = null

    @Volatile
    private var open = false

    /** Dernier WAV écrit, `null` tant qu'aucune dictée n'a parlé dans les lunettes. */
    var lastWav: File? = null
        private set

    /** Vrai entre [start] et [stopAndCollect] : l'écran des lunettes l'affiche. */
    val active: Boolean get() = open

    override fun start(): Boolean {
        if (open) return true
        // Pas de piste audio négociée (micro refusé côté app Meta AI) : rien à ouvrir.
        if (choice.silent) return false
        synchronized(captured) { captured.reset() }
        open = true
        job = scope.launch {
            // Fin de flux ou erreur : la dictée garde ce qui a été capté, sans planter.
            runCatching { frames.collect(::append) }
        }
        onActive(true)
        return true
    }

    override fun stopAndCollect(): ByteArray = finish(write = true)

    /** Arrêt sans transcription : rien n'est écrit sur disque. */
    override fun abort() {
        finish(write = false)
    }

    private fun finish(write: Boolean): ByteArray {
        val running = job
        if (!open && running == null) return ByteArray(0)
        job = null
        open = false
        running?.cancel()
        onActive(false)
        val raw = synchronized(captured) { captured.toByteArray() }
        val pcm = PcmRate.to16k(raw, choice.hertz)
        if (write && pcm.isNotEmpty()) {
            lastWav = store.write(pcm, Wav.SAMPLE_RATE).getOrNull()
        }
        return pcm
    }

    private fun append(frame: AudioFrame) {
        // Le SDK réutilise son tampon : on copie pendant le callback, jamais après.
        val chunk = frame.buffer.let { buffer ->
            ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
        }
        synchronized(captured) { if (open) captured.write(chunk, 0, chunk.size) }
    }

    private companion object {
        const val WAV_PREFIX = "glasses"
    }
}