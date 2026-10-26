package dev.sam3vision.glasses

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
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
 * ⚠️ Le routage réel vers les lunettes exige `MODIFY_AUDIO_SETTINGS` dans le manifest
 * du module ; sans elle, [routeToGlasses] retombe silencieusement sur le haut-parleur.
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

    /** Vrai quand une sortie Bluetooth (lunettes) a été effectivement sélectionnée. */
    private var routedToGlasses = false

    /**
     * Route la sortie audio vers les lunettes Bluetooth si un tel périphérique est
     * connecté, sinon laisse (ou remet) le haut-parleur. Ne lève jamais : un refus de
     * la plateforme est seulement journalisé.
     */
    fun routeToGlasses() {
        val manager = audio
        if (manager == null) {
            Log.i(TAG, "AudioManager indisponible : sortie par défaut conservée")
            return
        }
        val device = findBluetoothOutput(manager)
        routedToGlasses = device != null && applyRoute(manager, device)
        if (!routedToGlasses) fallbackToSpeaker(manager)
        Log.i(
            TAG,
            if (routedToGlasses) {
                "voix routée vers les lunettes (type ${device?.type})"
            } else {
                "aucune lunette connectée : sortie haut-parleur"
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
     * Le flux demandé ([AudioManager.STREAM_VOICE_CALL] avec les lunettes, sinon
     * [AudioManager.STREAM_MUSIC]) est posé d'abord ; le couple usage/type appliqué
     * ensuite est ce que la plateforme retient vraiment. Le routage Bluetooth ne
     * dépend donc pas du flux mais de [AudioManager] dans [routeToGlasses].
     */
    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setLegacyStreamType(
            if (routedToGlasses) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC,
        )
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ---- Routage audio -------------------------------------------------------

    /** Premier périphérique Bluetooth disponible, dans l'ordre de préférence. */
    private fun findBluetoothOutput(manager: AudioManager): AudioDeviceInfo? {
        val devices = runCatching { outputDevices(manager) }.getOrDefault(emptyList())
        return PREFERRED_TYPES.firstNotNullOfOrNull { type ->
            devices.firstOrNull { it.type == type }
        }
    }

    /** Sorties connues : devices de communication sur API 31+, sorties classiques avant. */
    private fun outputDevices(manager: AudioManager): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.availableCommunicationDevices
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }

    /** Sélectionne [device] : communication device sur API 31+, SCO historique avant. */
    @Suppress("DEPRECATION")
    private fun applyRoute(manager: AudioManager, device: AudioDeviceInfo): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val selected = manager.setCommunicationDevice(device)
                if (selected) manager.mode = AudioManager.MODE_IN_COMMUNICATION
                selected
            } else {
                manager.isBluetoothScoOn = true
                manager.startBluetoothSco()
                // MODE_IN_COMMUNICATION : la seule constante qui fait router le SCO.
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                true
            }
        }.getOrElse { error ->
            // Souvent MODIFY_AUDIO_SETTINGS absente → SecurityException : on reste en local.
            Log.i(TAG, "routage Bluetooth refusé : ${error.message}")
            false
        }

    /** Repli explicite : haut-parleur / sortie par défaut, sans lever. */
    @Suppress("DEPRECATION")
    private fun fallbackToSpeaker(manager: AudioManager) {
        runCatching { manager.mode = AudioManager.MODE_NORMAL }
        runCatching { manager.isBluetoothScoOn = false }
        runCatching { manager.stopBluetoothSco() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.clearCommunicationDevice() }
        } else {
            runCatching { manager.isSpeakerphoneOn = true }
        }
    }

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

        /** Périphériques visés, BLE d'abord : c'est ce qu'exposent les lunettes récentes. */
        @Suppress("InlinedApi")
        val PREFERRED_TYPES = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
    }
}