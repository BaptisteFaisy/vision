package dev.sam3vision.app

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.iseedesign.SpeechPosition
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Fin d'une lecture en file : jouée jusqu'au bout, ou coupée par [SpeechPlayer.stop]. */
enum class PlaybackEnd { Done, Stopped }

/** Tranche voix : chaque parole tient dans 700 ms (ordre user du 01/11 — un tick, une phrase). */
internal const val VOICE_SLICE_MS = 700L

/** Plafond d'accélération d'une tranche (pitch conservé ; au-delà, refus plateforme possible). */
internal const val MAX_SLICE_RATE = 3f

/** Étiquette des diagnostics du mutisme spontané (`logcat -s VoiceMute:*`). */
private const val TAG_MUTE = "VoiceMute"

/**
 * Fraîcheur d'une lecture simple en file (5 s) : passé ce délai d'attente,
 * la phrase est périmée (la scène a changé) — jetée sans jouer, bus rendu.
 * Les tranches en file (chat, séquences) n'ont pas de délai : chaque tranche
 * compte, c'est [QUEUED_TIMEOUT_MS] qui borne l'attente côté appelant.
 */
internal const val AUDIO_FRESH_MS = 5_000L

/**
 * Borne d'une tranche en file (60 s) : au-delà, la tranche est rendue
 * [PlaybackEnd.Stopped] (reprise plus tard, comme une coupe — jamais
 * d'exception, jamais d'attente infinie sur un lecteur figé).
 */
internal const val QUEUED_TIMEOUT_MS = 60_000L

/** Seuil d'un appel plateforme lent (`audio-lent`, warning même en release). */
internal const val AUDIO_SLOW_MS = 500L

/** Un fil audio dédié par lecteur (démon : jamais de processus retenu). */
private fun defaultAudioDispatcher(): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iSeeAudio").apply { isDaemon = true }
    }.asCoroutineDispatcher()

/**
 * Vitesse pour tenir [durationMs] dans [sliceMs] : jamais sous [baseSpeed]
 * (réglage user), jamais au-dessus de [MAX_SLICE_RATE] (au-delà, inintelligible
 * et refus possible du lecteur — le reliquat dépasse alors la tranche).
 * Durée inconnue (≤ 0) : vitesse de base. Pur, testé en JVM.
 */
internal fun sliceRateFor(durationMs: Long, sliceMs: Long = VOICE_SLICE_MS, baseSpeed: Float = Settings.DEFAULT_TTS_SPEED): Float {
    if (durationMs <= 0L || sliceMs <= 0L) return baseSpeed
    return maxOf(baseSpeed, minOf(durationMs.toFloat() / sliceMs, MAX_SLICE_RATE))
}

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
 *
 * Fil audio dédié ([audio], un par lecteur) : aucun appel plateforme
 * (`MediaPlayer`) ne tourne jamais sur le fil appelant — un `prepare()` figé
 * (observé sur appareil : ni fin, ni erreur, bus vocal épinglé) ne bloque que
 * le fil audio, jamais le Main ni le pool IO. Les lectures simples sont
 * feu-et-oublie ([play], [playFile], [playSlice], [playSliceFile] rendent
 * « accepté » aussitôt, fin au rappel) ; les périmées ([AUDIO_FRESH_MS]) et
 * les doublons (dernier gagnant) sont jetés sans jouer, bus rendu. Seule
 * [playQueued] suspend, jusqu'à la fin de sa tranche ou [QUEUED_TIMEOUT_MS].
 *
 * [audio] et [nowMs] sont injectés pour les tests (file pilotée au pas,
 * horloge maîtrisée) — la prod garde le fil dédié et l'horloge réelle.
 */
class SpeechPlayer(
    private val dir: File,
    private val onComplete: () -> Unit = {},
    private val onPlaybackError: (String) -> Unit = {},
    speed: Float = Settings.DEFAULT_TTS_SPEED,
    private val output: () -> AudioDeviceInfo? = { null },
    private val audio: CoroutineDispatcher = defaultAudioDispatcher(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** File d'attente du fil audio (gardée par [queueLock], FIFO strict). */
    private val pending = ArrayDeque<AudioUnit>()
    private val queueLock = Any()

    /** Un signal par unité mise en file (jamais de conflat : 1 signal = 1 unité). */
    private val signal = Channel<Unit>(Channel.UNLIMITED)

    /** Portée du fil audio (supervisée : une unité en échec ne tue pas la pompe). */
    private val scope = CoroutineScope(audio + SupervisorJob())

    /** Fermé ([release]) : les lectures sont refusées, la pompe s'arrête. */
    @Volatile
    private var closed = false

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

    /** Generation des lectures (modele BusSpeaker) : seule la lecture courante rappelle. */
    @Volatile
    private var generation = 0L

    init {
        scope.launch { pump() }
    }

    /**
     * Joue [bytes] (séquentiel : jamais coalescé, jamais périmé — chaque
     * phrase compte) ; succès = accepté, la fin part au rappel. Échec
     * synchrone (rien à jouer, lecteur fermé) : erreur rendue ET rappel
     * d'erreur, comme avant.
     */
    suspend fun play(bytes: ByteArray): Result<Unit> {
        if (bytes.isEmpty()) return syncFailure("rien à jouer : octets vides")
        if (closed) return syncFailure("lecteur fermé")
        enqueue(AudioUnit.Play(nowMs(), generation, file = null, bytes = bytes, sliceMs = null, preemptible = false))
        return Result.success(Unit)
    }

    /**
     * Joue un fichier déjà écrit dans le stockage privé (clip pré-généré). Aucun vidage
     * du répertoire : [dir] peut contenir les trois clips du mode tap.
     * Préemptible : doublons coalescés, périmés jetés (dernier gagnant).
     */
    suspend fun playFile(file: File): Result<Unit> {
        fileCheck(file)?.let { return it }
        if (closed) return syncFailure("lecteur fermé")
        enqueue(AudioUnit.Play(nowMs(), generation, file = file, bytes = null, sliceMs = null, preemptible = true))
        return Result.success(Unit)
    }

    /**
     * Joue un fichier en tranche ([sliceMs], 700 ms voix) : la vitesse monte
     * pour tenir la durée réelle dans la tranche ([sliceRateFor], pitch conservé,
     * plafond [MAX_SLICE_RATE]). Verdicts et chat : hors tranche (entiers).
     * Préemptible : doublons coalescés, périmés jetés (dernier gagnant).
     */
    suspend fun playSliceFile(file: File, sliceMs: Long = VOICE_SLICE_MS): Result<Unit> {
        fileCheck(file)?.let { return it }
        if (closed) return syncFailure("lecteur fermé")
        enqueue(AudioUnit.Play(nowMs(), generation, file = file, bytes = null, sliceMs = sliceMs, preemptible = true))
        return Result.success(Unit)
    }

    /**
     * Joue [bytes] en tranche ([sliceMs]) : synthèse GPS et confirmations, jamais le chat.
     * Préemptible : doublons coalescés, périmés jetés (dernier gagnant).
     */
    suspend fun playSlice(bytes: ByteArray, sliceMs: Long = VOICE_SLICE_MS): Result<Unit> {
        if (bytes.isEmpty()) return syncFailure("rien à jouer : octets vides")
        if (closed) return syncFailure("lecteur fermé")
        enqueue(AudioUnit.Play(nowMs(), generation, file = null, bytes = bytes, sliceMs = sliceMs, preemptible = true))
        return Result.success(Unit)
    }

    /**
     * Joue [bytes] et suspend jusqu'au bout de la lecture : le chaînage des phrases
     * l'utilise pour enchaîner sans chevauchement. Un [stop] extérieur (nouvelle tenue,
     * annonce) rend [PlaybackEnd.Stopped] au lieu d'échouer : couper n'est pas une
     * erreur. Les rappels du constructeur ne sont pas appelés : l'appelant tient son
     * état lui-même, tranche par tranche. Au-delà de [QUEUED_TIMEOUT_MS] (lecteur
     * figé), rendu [PlaybackEnd.Stopped], comme une coupe — reprise plus tard.
     */
    suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> {
        if (closed) return Result.failure(IllegalStateException("lecteur fermé"))
        val gate = CompletableDeferred<Result<PlaybackEnd>>()
        enqueue(AudioUnit.Queued(nowMs(), generation, bytes, gate))
        val end = withTimeoutOrNull(QUEUED_TIMEOUT_MS) { gate.await() }
        if (end != null) {
            if (waiter === gate) waiter = null
            return end
        }
        stop()
        return Result.success(PlaybackEnd.Stopped)
    }

    /** Contrôle synchrone d'un fichier à jouer (`null` = bon pour la file). */
    private fun fileCheck(file: File): Result<Unit>? {
        if (closed) return syncFailure("lecteur fermé")
        if (!file.isFile || file.length() <= 0L) return syncFailure("clip illisible : ${file.name}")
        return null
    }

    /** Échec synchrone : erreur rendue ET rappel d'erreur (contrat historique). */
    private fun syncFailure(message: String): Result<Unit> {
        reportSyncFailure(IllegalStateException(message))
        return Result.failure(IllegalStateException(message))
    }

    /**
     * Échec synchrone (fichier illisible, préparation refusée) : le rappel
     * d'erreur part comme en lecture — sinon aucun rappel ne part jamais et
     * l'appelant qui attend la fin (bus vocal) reste coincé pour toujours.
     * Le rappel ne lève jamais (le [Result] d'échec est déjà renvoyé).
     */
    private fun reportSyncFailure(cause: Throwable) {
        val detail = cause.message ?: cause.javaClass.simpleName
        runCatching { onPlaybackError("lecture audio impossible ($detail)") }
    }

    /**
     * Corps commun : préparation, démarrage ; l'appelant a déjà arrêté la lecture
     * précédente. Les rappels sont ceux du constructeur, sauf en file où l'appelant
     * attend la fin tranche par tranche.
     */
    private fun playBlocking(file: File, onDone: () -> Unit, onError: (String) -> Unit, sliceMs: Long? = null) {
        val mp = MediaPlayer()
        val current = ++generation
        player = mp
        mp.setAudioAttributes(audioAttributes())
        // Sortie de la phrase : relue ici, jamais mémorisée à la construction — la
        // paire des lunettes apparaît en Bluetooth quelques secondes après la session.
        // Un appareil refusé par la plateforme laisse la sortie par défaut : le son
        // part quand même, il ne se tait pas pour un routage raté.
        runCatching { output()?.let { device -> mp.preferredDevice = device } }
        timed("source") { mp.setDataSource(file.absolutePath) }
        mp.setOnCompletionListener { finishIfCurrent(current, onDone) }
        mp.setOnErrorListener { _, what, extra ->
            finishIfCurrent(current) { onError("lecture audio impossible ($what/$extra)") }
            true
        }
        timed("prepare") { mp.prepare() }
        // Vitesse locale (PlaybackParams, API 23+ ; minSdk 29) : le MP3 ElevenLabs
        // est synthétisé à vitesse naturelle, la lecture l'accélère — pitch conservé.
        // Tranche : la vitesse monte (pitch conservé) pour tenir la durée réelle
        // dans la tranche — un refus plateforme rejoue à la vitesse du réglage.
        val rate = if (sliceMs == null) speed else runCatching { sliceRateFor(mp.duration.toLong(), sliceMs, speed) }.getOrDefault(speed)
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(rate) }
        timed("demarrage") { mp.start() }
    }

    /**
     * Fin de lecture gardee : le rappel ne passe que si [gen] est la lecture
     * courante, une seule fois. Un rappel tardif (lecture tuee par [stop]) est
     * ignore, et la plateforme ne rappelle jamais deux fois. Pur, teste en JVM.
     */
    internal fun finishIfCurrent(gen: Long, block: () -> Unit) {
        if (gen == generation) {
            generation++
            block()
        }
    }

    /**
     * Arme une lecture fictive (tests JVM : `MediaPlayer` indisponible) ; rend sa
     * generation, a terminer par [finishIfCurrent] comme le ferait la plateforme.
     */
    internal fun armPlaybackForTest(): Long = ++generation

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

    /**
     * Unité de travail du fil audio : [Play] simple (feu-et-oublie, fin au
     * rappel), [Queued] en file (fin rendue au [gate]), [Halt] coupe (libère
     * le lecteur capturé). [genAtEnqueue] = génération à la mise en file :
     * une coupe extérieure entre-temps rend l'unité caduque (jamais jouée).
     */
    private sealed interface AudioUnit {
        val enqueuedMs: Long
        val genAtEnqueue: Long
        data class Play(
            override val enqueuedMs: Long,
            override val genAtEnqueue: Long,
            val file: File?,
            val bytes: ByteArray?,
            val sliceMs: Long?,
            val preemptible: Boolean,
        ) : AudioUnit
        data class Queued(
            override val enqueuedMs: Long,
            override val genAtEnqueue: Long,
            val bytes: ByteArray,
            val gate: CompletableDeferred<Result<PlaybackEnd>>,
        ) : AudioUnit
        data class Halt(
            override val enqueuedMs: Long,
            override val genAtEnqueue: Long,
            val mp: MediaPlayer?,
        ) : AudioUnit
    }

    /** Verrou des champs partagés (génération, attente, lecteur courant). */
    private val fieldsLock = Any()

    /**
     * Arrête la lecture en cours : les champs sont coupés aussitôt (synchrone,
     * jamais bloquant), la libération du lecteur part sur le fil audio. Une
     * file en attente rend [PlaybackEnd.Stopped].
     */
    fun stop() {
        val mp = stopFields(bump = true)
        if (closed) {
            timed("arret") { releasePart(mp) }
        } else {
            enqueue(AudioUnit.Halt(nowMs(), generation, mp))
        }
    }

    /** Libération finale (onCleared du ViewModel) : champs coupés, fil arrêté. */
    fun release() {
        closed = true
        val mp = stopFields(bump = true)
        timed("arret") { releasePart(mp) }
        scope.cancel()
        (audio as? ExecutorCoroutineDispatcher)?.close()
    }

    /**
     * Coupe les champs (génération, attente, lecteur courant) et rend le
     * lecteur capturé à libérer. [bump] : la coupe extérieure invalide les
     * unités en file ; la coupe de routine d'une unité ne bump pas (sinon
     * chaque lecture invaliderait la suivante).
     */
    private fun stopFields(bump: Boolean): MediaPlayer? = synchronized(fieldsLock) {
        if (bump) generation++
        waiter?.complete(Result.success(PlaybackEnd.Stopped))
        waiter = null
        val mp = player
        player = null
        mp
    }

    /** Libère [mp] (capturé, jamais relu) : arrêt, réinit, libération. */
    private fun releasePart(mp: MediaPlayer?) {
        mp ?: return
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.reset() }
        runCatching { mp.release() }
    }

    /** Met [unit] en file (une unité = un signal, jamais de conflat). */
    private fun enqueue(unit: AudioUnit) {
        synchronized(queueLock) { pending.addLast(unit) }
        signal.trySend(Unit)
    }

    /** Pompe du fil audio : une unité par signal, dans l'ordre, chacune protégée. */
    private suspend fun pump() {
        for (ignored in signal) {
            val unit = synchronized(queueLock) { pending.removeFirstOrNull() } ?: continue
            runCatching { runUnit(unit) }
        }
    }

    /** Exécute [unit] sur le fil audio (les appels bloquants sont contenus ici). */
    private fun runUnit(unit: AudioUnit) {
        when (unit) {
            is AudioUnit.Halt -> timed("arret") { releasePart(unit.mp) }
            is AudioUnit.Play -> runPlay(unit)
            is AudioUnit.Queued -> runQueued(unit)
        }
    }

    /** Lecture simple : jetée si coupée, périmée ou doublée, jouée sinon. */
    private fun runPlay(unit: AudioUnit.Play) {
        if (unit.genAtEnqueue != generation) return settleDrop("coupe-avant-lecture")
        if (unit.preemptible && nowMs() - unit.enqueuedMs > AUDIO_FRESH_MS) return settleDrop("perime")
        if (unit.preemptible && synchronized(queueLock) { pending.isNotEmpty() }) return settleDrop("coalesce")
        val genBeforePreamble = generation
        timed("arret") { releasePart(stopFields(bump = false)) }
        if (generation != genBeforePreamble) return settleDrop("coupe-avant-lecture")
        runCatching {
            val file = unit.file ?: writeCache(unit.bytes ?: return settleDrop("vide"))
            playBlocking(file, onComplete, onPlaybackError, unit.sliceMs)
        }.onFailure { reportSyncFailure(it) }
    }

    /** Tranche en file : jouée sauf coupe entre-temps (fidélité, jamais jetée sinon). */
    private fun runQueued(unit: AudioUnit.Queued) {
        if (unit.genAtEnqueue != generation) {
            unit.gate.complete(Result.success(PlaybackEnd.Stopped))
            return
        }
        val genBeforePreamble = generation
        timed("arret") { releasePart(stopFields(bump = false)) }
        if (generation != genBeforePreamble) {
            unit.gate.complete(Result.success(PlaybackEnd.Stopped))
            return
        }
        synchronized(fieldsLock) { waiter = unit.gate }
        runCatching {
            playBlocking(
                writeCache(unit.bytes),
                onDone = { unit.gate.complete(Result.success(PlaybackEnd.Done)) },
                onError = { message -> unit.gate.complete(Result.failure(IllegalStateException(message))) },
            )
        }.onFailure { unit.gate.complete(Result.failure(it)) }
    }

    /** Jet propre (coupe, péremption, doublon) : log en debug, bus rendu sans erreur. */
    private fun settleDrop(motif: String) {
        if (dev.sam3vision.app.BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "audio-jet motif=$motif")
        runCatching { onComplete() }
    }

    /** Chronomètre un appel plateforme lent (`audio-lent`, warning même en release). */
    private inline fun <T> timed(op: String, block: () -> T): T {
        val start = nowMs()
        try {
            return block()
        } finally {
            val dt = nowMs() - start
            if (dt > AUDIO_SLOW_MS) android.util.Log.w(TAG_MUTE, "audio-lent op=$op ms=$dt")
        }
    }

    /** Une seule prise dans le cache : l'ancienne est supprimée avant d'écrire la neuve. */
    private fun writeCache(bytes: ByteArray): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "voice.mp3").apply { writeBytes(bytes) }
    }
}
