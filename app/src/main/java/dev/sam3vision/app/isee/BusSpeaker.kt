package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.SIDEWALK_SLICE_MS
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.VOICE_SLICE_MS
import dev.sam3vision.app.safety.CrashGuard
import dev.sam3vision.app.safety.redactSecrets
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.speech.IseeClipsEn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * [SpeechPlayer] vu comme [ClipSink] : lecture simple (fin au rappel),
 * tranche 700 ms ([playSlice], verdicts exceptés — dits entiers) ou tranche
 * en file (fin rendue, sans rappel — sans libération du bus entre tranches).
 */
/** Étiquette des diagnostics du mutisme spontané (`logcat -s VoiceMute:*`). */
private const val TAG_MUTE = "VoiceMute"

/**
 * Budget d'une parole du bus (20 s) : une phrase spontanée plus vieille est
 * périmée (la scène a changé), et une parole coincée ne doit jamais épingler
 * le bus — observé sur appareil : paroles acceptées sans issue, niveau actif
 * bloqué, synthèse et clips empilés derrière sans erreur. Au dépassement, la
 * parole est jetée (warning `bus-depassement`, même en release) et le bus
 * rendu si aucune parole plus récente ne l'a pris entre-temps.
 */
internal const val SAY_TIMEOUT_MS = 20_000L

/**
 * Budget de restitution du bus (25 s, après [SAY_TIMEOUT_MS]) : si le bus
 * tient encore la parole passé ce délai, la lecture est coincée côté
 * plateforme (`prepare()` figé, observé sur appareil — ni erreur, ni fin,
 * donc aucun rappel) et le bus est rendu de force (`bus-sans-retour`). Le
 * niveau ET le jeton doivent encore matcher : une parole plus récente ne
 * se fait jamais voler son tour.
 */
internal const val RELEASE_TIMEOUT_MS = 25_000L

/**
 * Scrutation de l'attente parole (50 ms) : la prise du chemin est immédiate
 * quand il est libre, prompte (< 50 ms) à la libération — sans jamais
 * s'accrocher à un mutex abandonné (la boucle relit le mutex courant, un
 * `withLock` sur l'instance coincée attendrait pour toujours).
 */
internal const val SPEECH_POLL_MS = 50L

/**
 * Tranche de lecture pour [level] : verdict dit entier (`null`), trottoir
 * en moins de 500 ms (§ 46, absolu bref), tout le reste en 700 ms (§ 39).
 * Pure, testée en JVM.
 */
internal fun sliceMsForLevel(level: Int): Long? = when (level) {
    CrossingLevels.VERDICT -> null
    OutdoorLevel.SIDEWALK -> SIDEWALK_SLICE_MS
    else -> VOICE_SLICE_MS
}

internal class SpeechPlayerClipSink(private val player: SpeechPlayer) : ClipSink {
    override suspend fun playFile(file: File): Result<Unit> = player.playFile(file)
    override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> = player.playSliceFile(file, sliceMs)
    override suspend fun playQueued(bytes: ByteArray) = player.playQueued(bytes)
    override fun stop() = player.stop()
}

/**
 * Porte-parole du [VoiceBus] (SPEC v4) : séquence de clips anglais si [clips]
 * est non vide, clip anglais seul sinon (id fourni, ou retrouvé depuis le
 * texte — [spokenClipId]), sinon voix de proximité ([ProximityVoice] : clip
 * enregistré d'abord, synthèse réseau sinon pour les niveaux GPS seuls).
 *
 * Coupe d'abord le lecteur partagé (préemption physique, chat compris — la
 * tranche coupée attend sa reprise ; tue quand le chemin est prouvé coincé :
 * marteler un lecteur figé n'a jamais rien libéré — 38 paroles, 39 `stop`,
 * zéro issue sur appareil), puis prend le chemin parole : une séquence en
 * cours ne bloque jamais une parole plus prioritaire (la coupe la libère,
 * pas d'interblocage). Quand le lecteur est occupé, un re-post identique
 * (même niveau, texte, clip et clips — [lastWish]) ne coupe ni ne rejoue
 * (le son en cours va au bout), et une remplaçante injouable ([canPlay] :
 * séquence incomplète au catalogue, clip inconnu sans repli proximité
 * certain) ne coupe pas (jet tracé comme après lecture) — au repos, tout
 * se joue comme avant (un verdict répété après `done` reparle).
 * Génération ([generation]) : seule la parole la plus récente rend le repos
 * — une séquence coupée ne libère pas le niveau de sa remplaçante (une
 * parole sautée ne prend pas de génération : la parole en cours reste la
 * plus récente). Les lectures simples libèrent par le rappel du lecteur
 * ([VoiceBus.onSpeechIdle]) ; les séquences (sans rappel) et les jets
 * libèrent ici.
 *
 * Chemin abandonnable ([acquireSpeech]) : un détenteur qui tient passé son
 * propre budget ([sayTimeoutMs]) est coincé dans un appel plateforme non
 * annulable (`prepare()` figé, observé sur appareil — ni erreur, ni fin,
 * donc aucun rappel) : l'attente suivante l'abandonne (orphelin — à son
 * retour éventuel, sa génération périmée ne rend rien) et parle sur un mutex
 * frais. Une tentative bloquée ne coince plus jamais les suivantes ; le
 * superviseur 25 s abandonne de même quand le bus tient encore (cas calme,
 * sans nouvelle parole — sous re-posts continus le bus est légitimement tenu
 * par la dernière, les attentes abandonnent déjà : le superviseur n'a rien
 * à voler).
 *
 * Vigile ([sayTimeoutMs], [SAY_TIMEOUT_MS] en prod) : l'attente du chemin et
 * la parole partagent un seul budget — une parole qui ne parle pas à temps
 * (chemin tenu par une parole vivante, synthèse ou lecteur figés) est jetée
 * et rend le bus. Une phrase spontanée en retard est périmée, jamais rejouée.
 * Une parole qui lève sur le chemin ([speakGuarded]) rend le bus de même
 * (annulation propagée, exception journalisée — jamais de `finally`
 * inconditionnel : seule la parole la plus récente rend le repos).
 */
internal class BusSpeaker(
    private val scope: CoroutineScope,
    private val player: SpeechPlayer,
    private val clips: IseeClipPlayer,
    private val proximity: ProximityVoice,
    private val sayTimeoutMs: Long = SAY_TIMEOUT_MS,
    /**
     * Sonde « lecteur occupé » (`null` = le vrai lecteur ([idle] inversé) —
     * faux en test JVM où `MediaPlayer` n'existe pas, donc jamais occupé).
     */
    private val isBusy: (() -> Boolean)? = null,
    /**
     * Horloge des âges de détention (`elapsedRealtime`, monotone : un saut
     * NTP ne doit ni abandonner une parole vivante ni garder une coincée ;
     * temps virtuel injecté en test JVM).
     */
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : VoiceBusSpeaker {

    /** Vœu confié au lecteur : l'égalité décide du doublon (re-post identique). */
    private data class SpokenWish(val level: Int, val text: String, val clipId: String?, val clips: List<String>)

    /**
     * Chemin parole : une seule lecture du bus à la fois — mais remplaçable :
     * un détenteur expiré est abandonné ([abandonStuckSpeech]), jamais attendu.
     * Lu/écrit sous [gate] ; rendu par son preneur ([releaseSpeech]), même
     * orphelin.
     */
    private var speech = Mutex()

    /**
     * Garde des sections critiques courtes (prise/abandon du chemin — jamais
     * de parole dedans, donc jamais tenue : aucun interblocage possible).
     */
    private val gate = Mutex()

    /** Prise du chemin par son détenteur ([nowMs]) : son âge prouve le coincement (sous [gate]). */
    private var speechSinceMs: Long = 0L

    /** Chemin prouvé coincé (abandon sans retour depuis) : la coupe pré-verrou est tue. */
    @Volatile
    private var speechJammed = false

    /** Compteur des paroles : seule la plus récente rend le repos. */
    private var generation = 0L

    /** Dernier vœu confié à une parole (sautés exclus) : re-post identique + lecteur occupé = no-op. */
    @Volatile
    private var lastWish: SpokenWish? = null

    override fun idle(): Boolean = player.position() == null

    /** Lecteur occupé : sonde injectée en test, vrai lecteur en prod. */
    private fun playerBusy(): Boolean = isBusy?.invoke() ?: !idle()

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        // Lue sous le verrou de post() : c'est bien la parole acceptée.
        val (heldLevel, heldToken) = VoiceBus.hold()
        if (BuildConfig.DEBUG) {
            val alive = scope.coroutineContext[Job]?.isActive ?: false
            android.util.Log.d(TAG_MUTE, "bus-parole level=$level texte=[$text] scope-actif=$alive loc=${hashCode()}")
        }
        watchRelease(heldLevel, heldToken, level, text)
        val wish = SpokenWish(level, text, clipId, clips)
        if (playerBusy()) {
            if (wish == lastWish) {
                // Re-post identique pendant la lecture : ni coupe ni rejou —
                // le son en cours va au bout et rend le bus (rien ne s'est
                // terminé : aucun onSpeechIdle, aucune fuite de jeton).
                if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-doublon level=$level texte=[$text]")
                return
            }
            if (!canPlay(level, text, clipId, clips)) {
                // Remplaçante injouable : le son en cours continue (aucun
                // stop), le jet est tracé comme après lecture.
                logPreStopJet(level, text, clipId, clips)
                return
            }
        }
        lastWish = wish
        launchGuarded(heldLevel, heldToken, level, text) {
            // Hors chemin : la coupe libère la parole qui le tient — sauf
            // chemin prouvé coincé (marteler un lecteur figé n'a jamais
            // rien libéré).
            if (!speechJammed) player.stop()
            var current = -1L
            var played = false
            val done = withTimeoutOrNull(sayTimeoutMs) {
                val (taken, ticket) = acquireSpeech()
                current = ticket
                try {
                    played = speakGuarded(level, text, clipId, clips, current)
                } finally {
                    releaseSpeech(taken)
                }
            }
            if (done == null) {
                android.util.Log.w(TAG_MUTE, "bus-depassement level=$level texte=[$text] ms=$sayTimeoutMs joue=$played")
                if (current == generation) VoiceBus.onSpeechIdle()
            }
        }
    }

    /**
     * Filet de la coroutine de parole ([say] ne suspend jamais : tout le travail part d'ici).
     *
     * Le scope vient du ViewModel (`viewModelScope`, sans `CoroutineExceptionHandler`) : une
     * exception qui s'en échappait allait au handler non capturé du fil principal, donc à la
     * mort du process — une simple faute de réglages, de pack de clips ou d'I/O suffisait.
     * Ici, l'annulation se propage (coopération intacte) et **toute** autre faute est tracée
     * ([CrashGuard]) puis rend le bus : plus de crash depuis la parole, plus de niveau tenu
     * à vie par une parole morte ([yieldBus]).
     */
    private fun launchGuarded(
        heldLevel: Int,
        heldToken: Long,
        level: Int,
        text: String,
        body: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                body()
            } catch (cancel: CancellationException) {
                yieldBus(heldLevel, heldToken)
                throw cancel
            } catch (error: Throwable) {
                android.util.Log.w(
                    TAG_MUTE,
                    "bus-parole-ko level=$level texte=[$text] ${error.javaClass.simpleName}: ${redactSecrets(error.message)}",
                )
                CrashGuard.note("bus-speech", error)
                yieldBus(heldLevel, heldToken)
            }
        }
    }

    /**
     * Rend le bus si c'est encore **notre** parole (niveau et jeton : une parole plus récente
     * ne se fait jamais voler son tour). Gardé : le rappel de repos peut lui-même lever.
     */
    private fun yieldBus(heldLevel: Int, heldToken: Long) {
        runCatching { if (VoiceBus.holds(heldLevel, heldToken)) VoiceBus.onSpeechIdle() }
    }

    /**
     * Prend le chemin parole : immédiat si libre, prompt à la libération
     * ([SPEECH_POLL_MS]) — et jamais otage d'un détenteur expiré (son âge
     * passé [sayTimeoutMs] : abandonné, orphelin). Rend le mutex pris et le
     * ticket de génération (pris sous [gate] : les prises sont sérialisées
     * même après abandon). Annulable (budget de [say]).
     */
    private suspend fun acquireSpeech(): Pair<Mutex, Long> {
        while (true) {
            val got = gate.withLock {
                if (speech.tryLock()) {
                    speechSinceMs = nowMs()
                    speech to ++generation
                } else {
                    null
                }
            }
            if (got != null) return got
            if (abandonStuckSpeech("attente")) continue
            delay(SPEECH_POLL_MS)
        }
    }

    /**
     * Abandonne le chemin parole si son détenteur est expiré (tient passé
     * [sayTimeoutMs], son propre budget — appel plateforme non annulable) :
     * mutex frais, coupe tue ([speechJammed]) jusqu'au prochain retour. Vrai
     * si abandonné. Partagée par l'attente ([acquireSpeech]) et le
     * superviseur 25 s ([watchRelease]) : sobre (un warning par abandon,
     * jamais par parole).
     */
    private suspend fun abandonStuckSpeech(origin: String): Boolean {
        val now = nowMs()
        val age = gate.withLock {
            val held = now - speechSinceMs
            if (speech.isLocked && held > sayTimeoutMs) {
                speech = Mutex()
                speechSinceMs = now
                speechJammed = true
                held
            } else {
                null
            }
        }
        if (age != null) android.util.Log.w(TAG_MUTE, "bus-mutex-abandon ms=$age loc=$origin")
        return age != null
    }

    /**
     * Rend le chemin parole pris par [acquireSpeech] : tout retour (même
     * orphelin abandonné) prouve le lecteur vivant et rallume la coupe
     * ([speechJammed]) — un chemin encore coincé se re-prouve au prochain
     * abandon. Inannulable : une annulation ne doit jamais fuir le verrou.
     */
    private suspend fun releaseSpeech(taken: Mutex) {
        withContext(NonCancellable) {
            gate.withLock {
                speechJammed = false
                taken.unlock()
            }
        }
    }

    /**
     * Superviseur de restitution (frère, pas enfant : il survit à la parole
     * elle-même — une lecture figée ne rend jamais le bus). Lancé pour
     * chaque parole acceptée, sautée comprise : il suit le jeton le plus
     * récent — sans lui, le superviseur de la parole en cours regarderait
     * un jeton périmé et le bus fuirait sur lecture coincée. Quand il rend
     * le bus, il abandonne aussi le chemin parole s'il est coincé (le
     * timeout libère le chemin, pas seulement le tour).
     */
    private fun watchRelease(heldLevel: Int, heldToken: Long, level: Int, text: String) {
        scope.launch {
            try {
                delay(RELEASE_TIMEOUT_MS)
                if (VoiceBus.holds(heldLevel, heldToken)) {
                    android.util.Log.w(TAG_MUTE, "bus-sans-retour level=$level texte=[$text] ms=$RELEASE_TIMEOUT_MS")
                    abandonStuckSpeech("superviseur")
                    VoiceBus.onSpeechIdle()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // Même filet que la parole : ce frère ne doit jamais tuer le process.
                CrashGuard.note("bus-watchdog", error)
            }
        }
    }

    /**
     * Vrai si la remplaçante peut jouer (sondé AVANT toute coupe) : séquence
     * complète (tous les clips au catalogue — [IseeClipPlayer] tait une
     * phrase incomplète sans rien jouer), clip seul connu, ou repli
     * proximité possible (hors parole user sauf niveau 0, clip pack ou
     * niveau GPS — porte, moteur et réseau se décident à la lecture, comme
     * avant). Conservateur : le doute joue (comportement inchangé) — seule
     * l'impasse certaine épargne la coupe.
     */
    private fun canPlay(level: Int, text: String, clipId: String?, clips: List<String>): Boolean {
        if (clips.isNotEmpty()) return clips.all { IseeClipsEn.all.containsKey(it) }
        val id = clipId ?: spokenClipId(text)
        if (id != null && IseeClipsEn.all.containsKey(id)) return true
        if (text.isBlank()) return false
        if (level != OutdoorLevel.CAR_MOVING && proximity.userIsSpeaking()) return false
        if (recordedClipId(text) != null) return true
        return level == OutdoorLevel.GPS_REMINDER || level == OutdoorLevel.GPS_EVENT
    }

    /**
     * Jet sans coupe (remplaçante injouable, lecteur occupé) : mêmes traces
     * qu'une parole jetée après lecture (séquence : `bus-seq ok=false` ;
     * sinon `SpeechLog.fail` + warning `PAROLE JETEE`) — le son en cours
     * continue et rend le bus lui-même (aucun `onSpeechIdle` ici).
     */
    private fun logPreStopJet(level: Int, text: String, clipId: String?, clips: List<String>) {
        if (clips.isNotEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-seq level=$level ok=false n=${clips.size}")
            return
        }
        SpeechLog.fail("bus", "clip+synthèse KO")
        muteOutcome(level, text, clipId, clipId ?: spokenClipId(text), clipOk = false, proxOk = false)
    }

    /**
     * Parole sur le chemin ([current] = sa génération) : séquence si [clips]
     * est non vide, sinon clip puis synthèse — vrai si parlé. Une annulation
     * (budget dépassé) rend le bus si dernière et se propage ; une exception
     * est journalisée ([SpeechLog.fail]) et rend le bus si dernière (faux).
     * Jamais de `finally` inconditionnel : une parole dépassée ne doit pas
     * voler le repos de sa remplaçante.
     */
    private suspend fun speakGuarded(level: Int, text: String, clipId: String?, clips: List<String>, current: Long): Boolean {
        return try {
            if (clips.isNotEmpty()) {
                val seqOk = this@BusSpeaker.clips.speakSequence(clips, urgent = true)
                if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-seq level=$level ok=$seqOk n=${clips.size}")
                if (current == generation) VoiceBus.onSpeechIdle()
                seqOk
            } else {
                val id = clipId ?: spokenClipId(text)
                val slice = VOICE_SLICE_MS.takeUnless { level == CrossingLevels.VERDICT }
                val clipOk = id != null && this@BusSpeaker.clips.speak(id, urgent = true, sliceMs = slice)
                val proxOk = clipOk || proximity.speak(text, clipId = null, level = level)
                if (proxOk) SpeechLog.play(if (clipOk) "clip" else "synthèse", text)
                else SpeechLog.fail("bus", "clip+synthèse KO")
                muteOutcome(level, text, clipId, id, clipOk, proxOk)
                if (!clipOk && !proxOk && current == generation) {
                    VoiceBus.onSpeechIdle()
                }
                clipOk || proxOk
            }
        } catch (cancel: CancellationException) {
            if (current == generation) VoiceBus.onSpeechIdle()
            throw cancel
        } catch (e: Exception) {
            SpeechLog.fail("bus", "exception ${e.message}")
            if (current == generation) VoiceBus.onSpeechIdle()
            false
        }
    }

    /**
     * Issue d'une parole au logcat : warning si jetée (visible en release),
     * détail en debug — distingue « pas de clip » (`resolu=null`), « clip
     * fantôme » (`catalogue=false`), « fichier/voix » (`clipOk=false`) et la
     * synthèse (`proxOk=false`, motif dans `ProximityVoice`).
     */
    private fun muteOutcome(level: Int, text: String, clipId: String?, id: String?, clipOk: Boolean, proxOk: Boolean) {
        if (!clipOk && !proxOk) {
            android.util.Log.w(TAG_MUTE, "PAROLE JETEE level=$level texte=[$text] clip=[$clipId] resolu=[$id]")
            return
        }
        if (!BuildConfig.DEBUG) return
        val inCatalog = id != null && IseeClipsEn.all.containsKey(id)
        android.util.Log.d(TAG_MUTE, "bus-out level=$level texte=[$text] resolu=[$id] catalogue=$inCatalog clipOk=$clipOk proxOk=$proxOk")
    }

    /** Coupe la lecture du bus (parole du user) : directe, jamais bloquante. */
    override fun cut() {
        player.stop()
    }
}
