package dev.sam3vision.glasses

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.net.TtsClient
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Sortie voix du spike lunettes : synthèse ElevenLabs via le client de `:core`
 * ([ElevenLabsTtsClient]) puis lecture locale du MP3 par [MediaPlayer].
 *
 * Aucun appel HTTP n'est réécrit ici : la clé et l'identifiant de voix viennent du
 * constructeur ([BuildConfig.TTS_API_KEY] / [BuildConfig.TTS_VOICE_ID] côté appelant).
 * Un seul son à la fois, une nouvelle phrase coupe la précédente.
 *
 * ⚠️ Le routage réel vers les lunettes passe par `MediaPlayer.setPreferredDevice`
 * (API 23+) : la voix part sur la sortie choisie **sans changer le mode audio global**
 * du téléphone. Exige `MODIFY_AUDIO_SETTINGS` (permission normale, déclarée au
 * manifeste du module) ; sans sortie Bluetooth utilisable, la lecture reste sur la
 * sortie par défaut et [routeToGlasses] le dit.
 */
class GlassesVoice(
    context: Context,
    private val apiKey: String,
    private val voiceId: String,
) {

    private val appContext = context.applicationContext

    /** Client de `:core` : zéro duplication du transport, erreurs déjà typées et lisibles. */
    private val tts: TtsClient = ElevenLabsTtsClient(apiKey, voiceId)

    private val audio: AudioManager? = appContext.getSystemService(AudioManager::class.java)
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
     * depuis l'écran du spike. Le nom de la sortie est dans la ligne, pas le mot
     * « lunettes » : ce choix prend aussi une paire d'écouteurs si elle est là.
     */
    fun routeToGlasses(preferredName: String? = null): String {
        val manager = audio ?: return logLine("voix : sortie par défaut (AudioManager indisponible)")
        val available = candidates(manager)
        val picked = pickBluetoothOutput(available.map { it.first }, preferredName)
        route = available.firstOrNull { it.first == picked }?.second
        return logLine(
            if (picked != null) {
                "voix : Bluetooth « ${picked.name} » (type ${picked.type})"
            } else {
                "voix : haut-parleur — aucune sortie Bluetooth utilisable"
            },
        )
    }

    /** Double le journal : logcat pour le test automatisé, retour pour l'écran du spike. */
    private fun logLine(line: String): String {
        Log.i(TAG, line)
        return line
    }

    /** Sorties Bluetooth proposées par la plateforme, avec leur objet Android d'origine. */
    private fun candidates(manager: AudioManager): List<Pair<AudioOutput, AudioDeviceInfo>> =
        runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            .getOrDefault(emptyList())
            .filter { isBluetoothOutput(it.type) }
            .map { device ->
                AudioOutput(
                    name = device.productName?.toString().orEmpty(),
                    type = device.type,
                    isSink = device.isSink,
                    rank = rankOf(device.type),
                ) to device
            }

    /** Ce qui peut sortir du son vers une paire : BLE d'abord, A2DP (la musique des lunettes) ensuite. */
    private fun rankOf(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
        AudioDeviceInfo.TYPE_HEARING_AID -> 3
        else -> 4
    }

    @Suppress("InlinedApi")
    private fun isBluetoothOutput(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
        type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_HEARING_AID

    /**
     * Synthétise [text] puis lance la lecture ; rend `null` dès que le son démarre,
     * ou un message d'erreur court (déjà rédigé par `:core` quand c'est une
     * [dev.sam3vision.core.net.ApiError]).
     * La synthèse est hors du thread principal, la lecture reste sur le main.
     */
    suspend fun say(text: String): String? {
        val phrase = text.trim()
        if (phrase.isEmpty()) return "rien à lire"
        if (apiKey.isBlank()) return "clé ElevenLabs absente"
        val mp3 = withContext(Dispatchers.IO) { tts.synthesize(phrase) }
            .getOrElse { return shortErrorMessage(it) }
        val file = withContext(Dispatchers.IO) { writeCache(mp3) }
            ?: return "audio impossible à écrire"
        return play(file)
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

    // ---- Lecture -------------------------------------------------------------

    /** Prépare en asynchrone et démarre ; le résultat arrive via les callbacks du lecteur. */
    private suspend fun play(file: File): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String?> { cont ->
            releasePlayer(player)
            val mp = MediaPlayer().also { player = it }
            var settled = false
            var watchdog: Runnable? = null

            fun settle(error: String?) {
                if (settled) return
                settled = true
                watchdog?.let { mainHandler.removeCallbacks(it) }
                if (cont.isActive) cont.resume(error)
            }

            val timeout = Runnable {
                settle("lecture audio : délai dépassé")
                releasePlayer(mp)
            }
            watchdog = timeout
            mainHandler.postDelayed(timeout, PREPARE_TIMEOUT_MS)

            val ready = runCatching {
                mp.setAudioAttributes(audioAttributes())
                // La sortie (lunettes, ou rien) posée sur le lecteur lui-même : aucun
                // réglage global du téléphone n'est touché, la phrase repart ailleurs.
                route?.let { device -> runCatching { mp.preferredDevice = device } }
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener { prepared ->
                    prepared.start()
                    settle(null)
                }
                mp.setOnCompletionListener { done -> releasePlayer(done) }
                mp.setOnErrorListener { failed, what, extra ->
                    settle("lecture audio impossible ($what/$extra)")
                    releasePlayer(failed)
                    true
                }
                mp.prepareAsync()
            }
            ready.onFailure { error ->
                settle("lecture audio impossible : ${shortErrorMessage(error)}")
                releasePlayer(mp)
            }
            cont.invokeOnCancellation { mainHandler.post { releasePlayer(mp) } }
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

    // ---- Cache et erreurs ----------------------------------------------------

    /**
     * Écrit le MP3 dans le cache privé sous un nom unique, puis purge les anciens
     * fichiers (un lecteur encore ouvert garde son descripteur, rien ne casse).
     */
    private fun writeCache(bytes: ByteArray): File? = runCatching {
        val dir = File(appContext.cacheDir, CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "voice-${System.nanoTime()}.mp3")
        file.writeBytes(bytes)
        dir.listFiles()?.forEach { old -> if (old != file) old.delete() }
        file
    }.getOrNull()

    private companion object {
        const val TAG = "GlassesVoice"
        const val CACHE_DIR = "glasses-voice"
        const val PREPARE_TIMEOUT_MS = 10_000L
    }
}