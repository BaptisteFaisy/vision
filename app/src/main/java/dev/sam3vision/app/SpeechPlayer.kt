package dev.sam3vision.app

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.iseedesign.SpeechPosition
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fin d'une lecture en file : jouée jusqu'au bout, ou coupée par [SpeechPlayer.stop]. */
enum class PlaybackEnd { Done, Stopped }

/**
 * Lit l'audio MP3 renvoyé par ElevenLabs : fichier temporaire dans le cache privé de
 * l'app, puis `MediaPlayer`. Aucune dépendance ajoutée (pas d'ExoPlayer) et aucun
 * accès réseau ici — les octets sont déjà téléchargés par le client `:core`.
 *
 * Trois usages, un seul moteur de lecture :
 * - [play] : phrase du chat/de l'analyse, écrite dans `voice.mp3` (le cache est vidé) ;
 * - [playFile] : clip déjà présent sur disque (mots du mode tap), aucun vidage — les
 *   clips « Launched »/« Sent »/« Failed » persistent donc entre deux lectures ;
 * - [playQueued] : tranche d'une réponse lue en continu, en attente de sa fin.
 *
 * Une seule lecture à la fois : chaque entrée arrête la précédente.
 * La vitesse ([speed], réglage `ttsSpeed`, ×1,9 par défaut) est appliquée en lecture
 * locale : le modèle ElevenLabs runtime ignore le réglage `speed` du cloud.
 *
 * [output] est relu **à chaque phrase** et posé sur le lecteur lui-même
 * (`preferredDevice`) : c'est ainsi que la voix des lunettes sort dans la paire
 * quand la session est ouverte ([dev.sam3vision.app.glasses.GlassesVoiceRoute]),
 * sans toucher au reste de l'audio du téléphone. `null` = sortie par défaut.
 * Attributs parole d'accessibilité sur flux musique, comme le banc prouvé sur
 * paire (`bt_a2dp`, 27/10) : la production rejoue le chemin qui s'entend.
 */
class SpeechPlayer(
    private val dir: File,
    private val onComplete: () -> Unit = {},
    private val onPlaybackError: (String) -> Unit = {},
    speed: Float = Settings.DEFAULT_TTS_SPEED,
    private val output: () -> AudioDeviceInfo? = { null },
) {
    @Volatile
    private var player: MediaPlayer? = null

    /** Horloge du MP3 réellement joué ; null avant le démarrage et après la fin. */
    fun position(): SpeechPosition? = runCatching {
        val mp = player ?: return null
        if (!mp.isPlaying) null else SpeechPosition(mp.currentPosition.toLong(), mp.duration.toLong())
    }.getOrNull()

    /** Vitesse appliquée à chaque lecture, bornée 0,5..2 (les ViewModels la tiennent à jour). */
    @Volatile
    var speed: Float = Settings.clampTtsSpeed(speed)
        set(value) {
            field = Settings.clampTtsSpeed(value)
        }

    /** Lecture en file en attente de sa fin, `null` hors [playQueued]. */
    @Volatile
    private var waiter: CompletableDeferred<Result<PlaybackEnd>>? = null

    /** Joue [bytes] ; renvoie l'échec au lieu de le lever (l'UI affiche le message). */
    suspend fun play(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            stop()
            playBlocking(writeCache(bytes), onComplete, onPlaybackError)
        }
    }

    /**
     * Joue un fichier déjà écrit dans le stockage privé (clip pré-généré). Aucun vidage
     * du répertoire : [dir] peut contenir les trois clips du mode tap.
     */
    suspend fun playFile(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            stop()
            playBlocking(file, onComplete, onPlaybackError)
        }
    }

    /**
     * Joue [bytes] et suspend jusqu'au bout de la lecture : le chaînage des phrases
     * l'utilise pour enchaîner sans chevauchement. Un [stop] extérieur (nouvelle tenue,
     * annonce) rend [PlaybackEnd.Stopped] au lieu d'échouer : couper n'est pas une
     * erreur. Les rappels du constructeur ne sont pas appelés : l'appelant tient son
     * état lui-même, tranche par tranche.
     */
    suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> {
        val gate = CompletableDeferred<Result<PlaybackEnd>>()
        withContext(Dispatchers.IO) {
            runCatching {
                stop()
                waiter = gate
                playBlocking(
                    writeCache(bytes),
                    onDone = { gate.complete(Result.success(PlaybackEnd.Done)) },
                    onError = { message -> gate.complete(Result.failure(IllegalStateException(message))) },
                )
            }.onFailure { gate.complete(Result.failure(it)) }
        }
        try {
            return gate.await()
        } finally {
            if (waiter === gate) waiter = null
        }
    }

    /**
     * Corps commun : préparation, démarrage ; l'appelant a déjà arrêté la lecture
     * précédente. Les rappels sont ceux du constructeur, sauf en file où l'appelant
     * attend la fin tranche par tranche.
     */
    private fun playBlocking(file: File, onDone: () -> Unit, onError: (String) -> Unit) {
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(audioAttributes())
        // Sortie de la phrase : relue ici, jamais mémorisée à la construction — la
        // paire des lunettes apparaît en Bluetooth quelques secondes après la session.
        // Un appareil refusé par la plateforme laisse la sortie par défaut : le son
        // part quand même, il ne se tait pas pour un routage raté.
        runCatching { output()?.let { device -> mp.preferredDevice = device } }
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { onDone() }
        mp.setOnErrorListener { _, what, extra ->
            onError("lecture audio impossible ($what/$extra)")
            true
        }
        mp.prepare()
        // Vitesse locale (PlaybackParams, API 23+ ; minSdk 29) : le MP3 ElevenLabs
        // est synthétisé à vitesse naturelle, la lecture l'accélère — pitch conservé.
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
        mp.start()
    }

    /**
     * Accessibilité + parole : la voix doit s'entendre et se comprendre dehors.
     * Mêmes attributs que le banc (`bench/GlassesVoice`) : le flux musique y a
     * prouvé la sortie A2DP de la paire, aucun mode global n'est touché.
     */
    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Arrête et libère la lecture en cours ; une file en attente rend [PlaybackEnd.Stopped]. */
    fun stop() {
        waiter?.complete(Result.success(PlaybackEnd.Stopped))
        waiter = null
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
    }

    /** Libération finale (onCleared du ViewModel). */
    fun release() = stop()

    /** Une seule prise dans le cache : l'ancienne est supprimée avant d'écrire la neuve. */
    private fun writeCache(bytes: ByteArray): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "voice.mp3").apply { writeBytes(bytes) }
    }
}
