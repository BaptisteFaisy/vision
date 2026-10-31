package dev.sam3vision.app.glasses.bench

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.sam3vision.app.glasses.bluetoothOutputs
import dev.sam3vision.app.glasses.pickBluetoothOutput
import dev.sam3vision.app.ttsClient
import dev.sam3vision.core.net.TtsClient
import dev.sam3vision.core.settings.Settings
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Sortie voix du banc lunettes : mince adaptateur entre la synthèse de l'app et la
 * sortie audio des lunettes.
 *
 * La synthèse est celle d'iSee — [ttsClient], le même client que la voix du chat —
 * donc aucun appel HTTP ni identifiant de voix n'est réécrit ici, et les refus
 * (texte vide, clé ou voix absente) remontent déjà rédigés par `:core`.
 *
 * Ne restent ici que les trois choses propres aux lunettes :
 * - **choisir la sortie** : [routeToGlasses] lit `AudioManager` et délègue la décision
 *   à [pickBluetoothOutput] (pur, testé sur JVM) ;
 * - **lire sur cette sortie** : `MediaPlayer.preferredDevice`, posé sur le lecteur lui
 *   même — aucun mode audio global n'est touché, le reste du téléphone continue ;
 * - **attendre le démarrage et nommer l'échec** : [say] rend `null` quand le son part,
 *   ou un message court que l'écran du banc affiche.
 *
 * Un seul son à la fois : une nouvelle phrase coupe la précédente. Exige
 * `MODIFY_AUDIO_SETTINGS` (permission normale, déclarée au manifeste de `:app`) ;
 * sans sortie Bluetooth utilisable, la lecture reste sur la sortie par défaut et
 * [routeToGlasses] le dit.
 */
class GlassesVoice(
    context: Context,
    apiKey: String,
    voiceId: String,
) {

    /** Réglages réduits à la voix : la clé et l'identifiant viennent du banc. */
    private val settings = Settings.Default.copy(ttsApiKey = apiKey, ttsVoiceId = voiceId)

    /** Synthèse de l'app : zéro duplication du transport, erreurs déjà typées et lisibles. */
    private val tts: TtsClient = ttsClient(settings)

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Lecture courante ; `null` quand rien ne joue. Toujours manipulé sur le main. */
    private var player: MediaPlayer? = null

    /** Sortie choisie pour la voix ; `null` = sortie par défaut (haut-parleur, écouteurs filaires). */
    private var route: AudioDeviceInfo? = null

    /**
     * Choisit la sortie audio de la voix : la paire de lunettes si son nom est
     * connu ([preferredName], celui que le SDK DAT annonce), sinon la meilleure
     * sortie Bluetooth réellement disponible (BLE, A2DP, puis SCO). Ne lève jamais.
     *
     * Rend la ligne du journal : avec les lunettes sur les yeux, savoir **où** le son
     * est sorti est la seule preuve audible de la chaîne, et logcat ne se lit pas
     * depuis l'écran du banc. Le nom de la sortie est dans la ligne, pas le mot
     * « lunettes » : ce choix prend aussi une paire d'écouteurs si elle est là.
     */
    fun routeToGlasses(preferredName: String? = null): String {
        val manager = audio ?: return logLine("voice: default output (AudioManager unavailable)")
        // Lecture d'`AudioManager` et rang des types : partagés avec la production
        // (`glasses/GlassesAudioRoute.kt`), une seule décision pour les deux.
        val available = bluetoothOutputs(manager)
        val picked = pickBluetoothOutput(available.map { it.first }, preferredName)
        route = available.firstOrNull { it.first == picked }?.second
        return logLine(
            if (picked != null) {
                "voice: Bluetooth « ${picked.name} » (type ${picked.type})"
            } else {
                "voice: speaker — no usable Bluetooth output"
            },
        )
    }

    /**
     * Synthétise [text] puis lance la lecture ; rend `null` dès que le son démarre,
     * ou un message d'erreur court (déjà rédigé par `:core` quand c'est une
     * [dev.sam3vision.core.net.ApiError]).
     * La synthèse est hors du thread principal, la lecture reste sur le main.
     */
    suspend fun say(text: String): String? {
        val mp3 = withContext(Dispatchers.IO) { tts.synthesize(text.trim()) }
            .getOrElse { return shortErrorMessage(it) }
        return play(mp3)
    }

    /** Stoppe la lecture en cours et libère [MediaPlayer] ; sans effet si rien ne joue. */
    fun stop() {
        val current = player ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releasePlayer(current)
        } else {
            mainHandler.post { releasePlayer(current) }
        }
    }

    // ---- Briques propres aux lunettes ----------------------------------------

    /** Double le journal : logcat pour le test automatisé, retour pour l'écran du banc. */
    private fun logLine(line: String): String {
        Log.i(TAG, line)
        return line
    }

    // ---- Lecture -------------------------------------------------------------

    /** Prépare en asynchrone et démarre ; le résultat arrive via les callbacks du lecteur. */
    private suspend fun play(mp3: ByteArray): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            releasePlayer(player)
            val mp = MediaPlayer().also { player = it }
            val settled = Settle(cont)
            runCatching { prepare(mp, mp3, settled) }.onFailure { error ->
                settled.finish("audio playback impossible: ${shortErrorMessage(error)}")
                releasePlayer(mp)
            }
            cont.invokeOnCancellation { mainHandler.post { releasePlayer(mp) } }
        }
    }

    /** Branche les callbacks du lecteur sur [settled] et arme le délai de préparation. */
    private fun prepare(mp: MediaPlayer, mp3: ByteArray, settled: Settle) {
        mp.setAudioAttributes(audioAttributes())
        // La sortie (lunettes, ou rien) est posée sur le lecteur lui-même : aucun
        // réglage global du téléphone n'est touché, la phrase repart ailleurs.
        route?.let { device -> runCatching { mp.preferredDevice = device } }
        mp.setDataSource(Mp3Source(mp3))
        mp.setOnPreparedListener { prepared ->
            prepared.start()
            settled.finish(null)
        }
        mp.setOnCompletionListener { done -> releasePlayer(done) }
        mp.setOnErrorListener { failed, what, extra ->
            settled.finish("audio playback impossible ($what/$extra)")
            releasePlayer(failed)
            true
        }
        settled.arm(PREPARE_TIMEOUT_MS) { releasePlayer(mp) }
        mp.prepareAsync()
    }

    /**
     * Issue d'une tentative de lecture : ne se résout qu'une fois, et coupe la
     * surveillance du délai que la phrase démarre, échoue ou dépasse le délai.
     */
    private inner class Settle(private val cont: CancellableContinuation<String?>) {

        private var done = false
        private var watchdog: Runnable? = null

        /** Au-delà de [timeoutMs] sans démarrage, [onLate] libère le lecteur et l'attente rend la main. */
        fun arm(timeoutMs: Long, onLate: () -> Unit) {
            val late = Runnable {
                finish("audio playback: timed out")
                onLate()
            }
            watchdog = late
            mainHandler.postDelayed(late, timeoutMs)
        }

        fun finish(error: String?) {
            if (done) return
            done = true
            watchdog?.let { mainHandler.removeCallbacks(it) }
            if (cont.isActive) cont.resume(error)
        }
    }

    /** Libère [target] si c'est bien la lecture courante (idempotent). */
    private fun releasePlayer(target: MediaPlayer?) {
        if (target == null) return
        if (player === target) player = null
        runCatching { if (target.isPlaying) target.stop() }
        runCatching { target.reset() }
        runCatching { target.release() }
    }

    /**
     * Accessibilité + parole : la voix doit s'entendre et se comprendre dehors.
     *
     * Flux musique dans tous les cas : la sortie (lunettes ou haut-parleur) est
     * choisie par [routeToGlasses] et appliquée sur le lecteur, pas par un mode
     * audio global — `MODE_IN_COMMUNICATION` rabaisserait les lunettes en SCO mono
     * 16 kHz et couperait le reste du téléphone.
     */
    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private companion object {
        const val TAG = "GlassesVoice"
        const val PREPARE_TIMEOUT_MS = 10_000L
    }
}

/**
 * MP3 déjà en mémoire, présenté directement à `MediaPlayer` : plus de fichier
 * temporaire à écrire, à purger et à laisser traîner dans le cache (le lecteur
 * garde le tampon le temps de la phrase).
 */
private class Mp3Source(bytes: ByteArray) : MediaDataSource() {

    private val data = bytes.copyOf()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit
}
