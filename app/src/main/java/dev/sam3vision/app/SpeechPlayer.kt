package dev.sam3vision.app

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import dev.sam3vision.app.isee.SpeechLog
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.iseedesign.SpeechPosition
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Fin d'une lecture en file : jouée jusqu'au bout, ou coupée par [SpeechPlayer.stop]. */
enum class PlaybackEnd { Done, Stopped }

/** Tranche voix : chaque parole tient dans 700 ms (ordre user du 01/11 — un tick, une phrase). */
internal const val VOICE_SLICE_MS = 700L

/** Tranche trottoir : l'annonce absolue tient en moins de 500 ms (ordre user du 02/11, § 46). */
internal const val SIDEWALK_SLICE_MS = 500L

/** Plafond d'accélération d'une tranche (pitch conservé ; au-delà, refus plateforme possible). */
internal const val MAX_SLICE_RATE = 3f

/**
 * Attente max de la préparation async d'une lecture : une préparation locale
 * prend quelques millisecondes, au-delà le natif est figé — le lecteur est
 * abandonné (libéré) et l'échec remonte au lieu de coincer le mutex parole
 * pour toujours (mutisme 02/11). Bien sous le chien de garde bus (20 s).
 */
internal const val PREPARE_TIMEOUT_MS = 2_000L

/** Coupe pendant la préparation async : succès silencieux, le stop a déjà parlé. */
private class PrepareCut : IllegalStateException("coupe pendant la preparation")

/**
 * Erreur plateforme pendant la préparation async : déjà racontée par son
 * rappel, [failure] remonte sans double récit.
 */
private class PrepareSettled(val failure: Throwable) : IllegalStateException(failure.message, failure)

/**
 * Lecture amorcée : lecteur créé, préparation async lancée, en attente du
 * feu vert. [failed] reçoit l'erreur plateforme déjà racontée par son
 * rappel (le démarrage la remonte sans double récit).
 */
private class PendingPlay(
    val mp: MediaPlayer,
    val gen: Long,
    val file: File,
    val prepared: CompletableDeferred<Unit>,
) {
    @Volatile
    var failed: Throwable? = null
}

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
 * Trace voix ([onEvent], défaut muet : aucun appelant n'est tenu de câbler) :
 * `play` à chaque demande de lecture, `stop` à chaque coupe demandée, `done`
 * en fin normale, `cut` quand la coupe interrompt une lecture en vol. Les
 * échecs (synchrone comme plateforme) partent déjà par [onPlaybackError],
 * canal inchangé : `onEvent` ne porte que le cycle de vie, jamais le détail
 * d'erreur.
 *
 * Journal voix ([onLog], défaut : puits `ISEE_VOIX`) : `PLAYER error …` à
 * tout échec (plateforme, synchrone, nettoyage refusé, routage tombé),
 * `PLAYER route …` à chaque sortie choisie — les morts du lecteur ne sont
 * plus invisibles. Stop et lectures sont sérialisés (un seul verrou) : un
 * `stop` ne tombe jamais dans une fenêtre `prepare`→`start` (coupe Redmi
 * 02/11 : `start called in state 64`, erreur `-38`), et un lecteur en
 * erreur est libéré aussitôt — la lecture suivante repart d'un neuf.
 */
class SpeechPlayer(
    private val dir: File,
    private val onComplete: () -> Unit = {},
    private val onPlaybackError: (String) -> Unit = {},
    speed: Float = Settings.DEFAULT_TTS_SPEED,
    private val output: () -> AudioDeviceInfo? = { null },
    private val onEvent: (String) -> Unit = {},
    private val onLog: (String) -> Unit = { line -> runCatching { SpeechLog.sink(SpeechLog.TAG, line) } },
) {
    @Volatile
    private var player: MediaPlayer? = null

    /**
     * Sérialise stop et lectures : `stop` + amorce (`beginPlay`), puis démarrage
     * gardé (`commitPlay`), forment des sections atomiques — un stop extérieur
     * ne tombe jamais dans une fenêtre `prepare`→`start` (la race prouvée par
     * la coupe 02/11). Réentrant (`synchronized`), jamais tenu pendant une attente.
     */
    private val lock = Any()

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

    /**
     * Préparation async en vol (`null` hors fenêtre prepare→start) : le stop
     * la réveille pour que la coupe pendant la préparation soit immédiate
     * (sans lui, l'attente prendrait tout le timeout). Sous [lock].
     */
    private var prepareGate: CompletableDeferred<Unit>? = null

    /** Generation des lectures (modele BusSpeaker) : seule la lecture courante rappelle. */
    @Volatile
    private var generation = 0L

    /** Joue [bytes] ; renvoie l'échec au lieu de le lever (l'UI affiche le message). */
    suspend fun play(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        launchPlay({ writeCache(bytes) }, onComplete, onPlaybackError)
    }

    /**
     * Joue un fichier déjà écrit dans le stockage privé (clip pré-généré). Aucun vidage
     * du répertoire : [dir] peut contenir les trois clips du mode tap.
     */
    suspend fun playFile(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        launchPlay({ file }, onComplete, onPlaybackError)
    }

    /**
     * Joue [bytes] et suspend jusqu'au bout de la lecture : le chaînage des phrases
     * l'utilise pour enchaîner sans chevauchement. Un [stop] extérieur (nouvelle tenue,
     * annonce) rend [PlaybackEnd.Stopped] au lieu d'échouer : couper n'est pas une
     * erreur. Les rappels du constructeur ne sont pas appelés : l'appelant tient son
     * état lui-même, tranche par tranche.
     */
    /**
     * Joue un fichier en tranche ([sliceMs], 700 ms voix) : la vitesse monte
     * pour tenir la durée réelle dans la tranche ([sliceRateFor], pitch conservé,
     * plafond [MAX_SLICE_RATE]). Verdicts et chat : hors tranche (entiers).
     */
    suspend fun playSliceFile(file: File, sliceMs: Long = VOICE_SLICE_MS): Result<Unit> = withContext(Dispatchers.IO) {
        launchPlay({ file }, onComplete, onPlaybackError, sliceMs)
    }

    /** Joue [bytes] en tranche ([sliceMs]) : synthèse GPS et confirmations, jamais le chat. */
    suspend fun playSlice(bytes: ByteArray, sliceMs: Long = VOICE_SLICE_MS): Result<Unit> = withContext(Dispatchers.IO) {
        launchPlay({ writeCache(bytes) }, onComplete, onPlaybackError, sliceMs)
    }

    suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> {
        val gate = CompletableDeferred<Result<PlaybackEnd>>()
        withContext(Dispatchers.IO) {
            launchPlay(
                files = { writeCache(bytes) },
                onDone = { gate.complete(Result.success(PlaybackEnd.Done)) },
                onError = { message -> gate.complete(Result.failure(IllegalStateException(message))) },
                afterStop = { waiter = gate },
                onSyncFailure = { cause, file -> failQueued(gate, cause, file) },
            )
        }
        try {
            return gate.await()
        } finally {
            if (waiter === gate) waiter = null
        }
    }

    /**
     * Échec synchrone en file : tracé comme les autres, rendu au chaînage,
     * jamais levé (les rappels du constructeur ne sont pas appelés en file).
     */
    private fun failQueued(gate: CompletableDeferred<Result<PlaybackEnd>>, cause: Throwable, file: File?) {
        logSyncFailure(cause, file)
        gate.complete(Result.failure(cause))
    }

    /**
     * Lancement commun : stop + amorce atomiques sous verrou, attente de la
     * préparation HORS verrou (à timeout réel), démarrage gardé. Une coupe
     * pendant la préparation rend le succès sans récit (le stop a déjà parlé :
     * couper n'est pas une erreur) ; une erreur plateforme déjà racontée par
     * son rappel rend l'échec sans double récit.
     */
    private suspend fun launchPlay(
        files: () -> File,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        sliceMs: Long? = null,
        afterStop: () -> Unit = {},
        onSyncFailure: (Throwable, File?) -> Unit = ::reportSyncFailure,
    ): Result<Unit> {
        var file: File? = null
        val outcome = runCatching {
            val pending = synchronized(lock) {
                stop()
                afterStop()
                onEvent("play")
                val f = files()
                file = f
                beginPlay(f, onDone, onError)
            }
            awaitPrepared(pending)
            synchronized(lock) { commitPlay(pending, sliceMs) }
        }
        if (outcome.exceptionOrNull() is PrepareCut) return Result.success(Unit)
        val settled = outcome.exceptionOrNull() as? PrepareSettled
        if (settled != null) return Result.failure(settled.failure)
        return outcome.onFailure { onSyncFailure(it, file) }
    }

    /**
     * Amorce atomique (sous verrou) : le neuf est créé, routé, écouté, et sa
     * préparation ASYNC est lancée — aucun appel JNI bloquant ici, le verrou
     * est tenu quelques millisecondes. Un échec d'amorce libère le mort-né et
     * remonte (la lecture suivante repartira d'un neuf).
     */
    private fun beginPlay(file: File, onDone: () -> Unit, onError: (String) -> Unit): PendingPlay {
        val mp = MediaPlayer()
        var current = -1L
        try {
            current = ++generation
            player = mp
            mp.setAudioAttributes(audioAttributes())
            routeOutput(mp)
            mp.setDataSource(file.absolutePath)
            val pending = PendingPlay(mp, current, file, CompletableDeferred())
            prepareGate = pending.prepared
            mp.setOnPreparedListener { pending.prepared.complete(Unit) }
            mp.setOnCompletionListener { finishDone(current, onDone) }
            mp.setOnErrorListener { _, what, extra ->
                val failure = IllegalStateException("lecture audio impossible ($what/$extra)")
                pending.failed = failure
                pending.prepared.complete(Unit)
                finishIfCurrent(current) {
                    logPlayerError("plateforme what=$what extra=$extra fichier=${file.name}")
                    onError(failure.message ?: failure.javaClass.simpleName)
                    abandonPlayer(mp, current)
                }
                true
            }
            mp.prepareAsync()
            return pending
        } catch (t: Throwable) {
            abandonPlayer(mp, current)
            throw t
        }
    }

    /**
     * Attente de la préparation HORS verrou, à timeout réel : le `stop` reste
     * instantané même si le natif fige (le verrou n'est jamais tenu ici — un
     * thread figé en JNI ignorait `withTimeoutOrNull` et coinçait le mutex
     * parole, mutisme 02/11). Timeout → le fautif est abandonné (libéré, sa
     * génération consommée : un rappel tardif sera ignoré) et l'échec remonte.
     */
    private suspend fun awaitPrepared(pending: PendingPlay) {
        val woke = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { pending.prepared.await() }
        if (woke == null) {
            abandonPlayer(pending.mp, pending.gen)
            logPlayerError("prepare timeout ${PREPARE_TIMEOUT_MS}ms fichier=${pending.file.name} — lecteur abandonne")
            throw IllegalStateException("préparation audio expirée (${pending.file.name})")
        }
    }

    /**
     * Démarrage gardé (sous verrou) : si la préparation a déjà raconté son
     * erreur, l'échec remonte sans double récit ; si un stop (ou une lecture
     * plus fraîche) a pris la main entre-temps, coupe silencieuse — sinon
     * vitesse + `start` atomiques, aucun stop ne tombe dans la fenêtre.
     */
    private fun commitPlay(pending: PendingPlay, sliceMs: Long?) {
        pending.failed?.let { throw PrepareSettled(it) }
        if (pending.gen != generation || player !== pending.mp) throw PrepareCut()
        val mp = pending.mp
        try {
            // Vitesse locale (PlaybackParams, API 23+ ; minSdk 29) : le MP3 ElevenLabs
            // est synthétisé à vitesse naturelle, la lecture l'accélère — pitch conservé.
            // Tranche : la vitesse monte (pitch conservé) pour tenir la durée réelle
            // dans la tranche — un refus plateforme rejoue à la vitesse du réglage.
            val rate = if (sliceMs == null) speed else runCatching { sliceRateFor(mp.duration.toLong(), sliceMs, speed) }.getOrDefault(speed)
            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(rate) }
                .onFailure { logPlayerError("vitesse refusee rate=$rate fichier=${pending.file.name} cause=[${shortCause(it)}]") }
            mp.start()
        } catch (t: Throwable) {
            abandonPlayer(mp, pending.gen)
            throw t
        }
    }

    /**
     * Sortie de la phrase : relue ici, jamais mémorisée à la construction — la
     * paire des lunettes apparaît en Bluetooth quelques secondes après la session.
     * Un appareil refusé par la plateforme laisse la sortie par défaut : le son
     * part quand même, il ne se tait pas pour un routage raté. Chaque décision
     * est journalisée (corrélation des muets : où le son est-il parti ?).
     */
    private fun routeOutput(mp: MediaPlayer) {
        val device = runCatching { output() }
            .onFailure { logPlayerError("route lecture refusee cause=[${shortCause(it)}] — sortie par defaut") }
            .getOrNull() ?: return
        val label = "appareil=[${deviceName(device)}] type=${deviceType(device)}"
        runCatching { mp.preferredDevice = device }
            .onSuccess { logPlayerRoute(label) }
            .onFailure { logPlayerError("route refusee $label cause=[${shortCause(it)}] — sortie par defaut") }
    }

    /** Nom d'appareil sans levée (journal seul). */
    private fun deviceName(device: AudioDeviceInfo): String =
        runCatching { device.productName.toString() }.getOrDefault("?")

    /** Type d'appareil sans levée (journal seul). */
    private fun deviceType(device: AudioDeviceInfo): String =
        runCatching { device.type.toString() }.getOrDefault("?")

    /**
     * Sort un lecteur mort de [player] et le libère : sa génération est
     * consommée si c'est encore la sienne (un rappel tardif du natif sera
     * ignoré), `release` s'il est encore le nôtre — un lecteur abandonné ne
     * retient plus le HW audio partagé entre les lecteurs.
     */
    private fun abandonPlayer(mp: MediaPlayer, gen: Long) {
        val ours = synchronized(lock) {
            val mine = player === mp
            if (mine) player = null
            if (gen == generation) generation++
            mine
        }
        if (ours) cleanup("release") { mp.release() }
    }

    /** Nettoyage avalé-tracé : l'échec part au journal voix, jamais silencieux. */
    private inline fun cleanup(quoi: String, block: () -> Unit) {
        runCatching(block).onFailure { logPlayerError("$quoi refuse cause=[${shortCause(it)}]") }
    }

    /** Cause courte pour le journal : classe + message. */
    private fun shortCause(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message ?: "?"}"

    /**
     * Échec synchrone (fichier illisible, préparation refusée ou expirée,
     * état illégal) : journalisé avec le fichier, puis le rappel d'erreur
     * part comme en lecture — sinon aucun rappel ne part jamais et l'appelant
     * qui attend la fin (bus vocal) reste coincé pour toujours. Le rappel ne
     * lève jamais (le [Result] d'échec est déjà renvoyé).
     */
    private fun reportSyncFailure(cause: Throwable, file: File?) {
        logSyncFailure(cause, file)
        val detail = cause.message ?: cause.javaClass.simpleName
        runCatching { onPlaybackError("lecture audio impossible ($detail)") }
    }

    /** Échec synchrone vers le journal voix (`PLAYER error …`, tag `ISEE_VOIX`). */
    private fun logSyncFailure(cause: Throwable, file: File?) {
        val where = file?.let { " fichier=${it.name}" } ?: ""
        logPlayerError("echec synchrone$where cause=[${shortCause(cause)}]")
    }

    /**
     * Une ligne d'échec vers le journal voix (`PLAYER error …`, tag
     * `ISEE_VOIX`), préfixée du lecteur (cinq instances partagent le HW
     * audio : le dossier distingue qui meurt). Ne lève jamais.
     */
    private fun logPlayerError(detail: String) {
        runCatching { onLog("PLAYER error lecteur=[${dir.name}] $detail") }
    }

    /** Décision de routage vers le journal voix (`PLAYER route …`). Ne lève jamais. */
    private fun logPlayerRoute(detail: String) {
        runCatching { onLog("PLAYER route lecteur=[${dir.name}] $detail") }
    }

    /**
     * Fin de lecture gardee : le rappel ne passe que si [gen] est la lecture
     * courante, une seule fois. Un rappel tardif (lecture tuee par [stop]) est
     * ignore, et la plateforme ne rappelle jamais deux fois. Pur, teste en JVM.
     */
    internal fun finishIfCurrent(gen: Long, block: () -> Unit) {
        synchronized(lock) {
            if (gen == generation) {
                generation++
                block()
            }
        }
    }

    /**
     * Fin normale gardée : émet `done` puis [block], une seule fois, si [gen]
     * est la lecture courante. La fin en erreur (rejet plateforme) passe par
     * [finishIfCurrent] sans `done` : l'erreur part déjà par le rappel dédié.
     */
    internal fun finishDone(gen: Long, block: () -> Unit) {
        finishIfCurrent(gen) { onEvent("done"); block() }
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
     * Arrête et libère la lecture en cours ; une file en attente rend
     * [PlaybackEnd.Stopped], une préparation en vol est réveillée (coupe
     * immédiate). Nettoyage avalé-tracé : tout refus part au journal voix.
     */
    fun stop() {
        synchronized(lock) {
            generation++
            onEvent("stop")
            if (waiter != null || playingNow()) onEvent("cut")
            waiter?.complete(Result.success(PlaybackEnd.Stopped))
            waiter = null
            prepareGate?.complete(Unit)
            prepareGate = null
            player?.let { mp ->
                cleanup("stop") { if (mp.isPlaying) mp.stop() }
                cleanup("reset") { mp.reset() }
                cleanup("release") { mp.release() }
            }
            player = null
        }
    }

    /** Lecture réellement en cours (un lecteur terminé non libéré n'est pas coupé). */
    private fun playingNow(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Libération finale (onCleared du ViewModel). */
    fun release() = stop()

    /** Une seule prise dans le cache : l'ancienne est supprimée avant d'écrire la neuve. */
    private fun writeCache(bytes: ByteArray): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "voice.mp3").apply { writeBytes(bytes) }
    }
}
