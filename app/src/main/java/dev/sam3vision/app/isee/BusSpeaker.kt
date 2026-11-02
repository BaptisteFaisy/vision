package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.VOICE_SLICE_MS
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.speech.IseeClipsEn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * enregistré d'abord, synthèse réseau sinon, tous niveaux).
 *
 * Coupe d'abord le lecteur partagé (préemption physique, chat compris — la
 * tranche coupée attend sa reprise), puis parle sous mutex : une séquence en
 * cours ne bloque jamais une parole plus prioritaire (la coupe la libère,
 * pas d'interblocage). Génération ([generation]) : seule la parole la plus
 * récente rend le repos — une séquence coupée ne libère pas le niveau de sa
 * remplaçante. Les lectures simples libèrent par le rappel du lecteur
 * ([VoiceBus.onSpeechIdle]) ; les séquences (sans rappel) et les jets
 * libèrent ici.
 *
 * Vigile ([sayTimeoutMs], [SAY_TIMEOUT_MS] en prod) : l'attente du mutex et
 * la parole partagent un seul budget — une parole qui ne parle pas à temps
 * (mutex tenu par une parole coincée, synthèse ou lecteur figés) est jetée
 * et rend le bus. Une phrase spontanée en retard est périmée, jamais rejouée.
 */
internal class BusSpeaker(
    private val scope: CoroutineScope,
    private val player: SpeechPlayer,
    private val clips: IseeClipPlayer,
    private val proximity: ProximityVoice,
    private val sayTimeoutMs: Long = SAY_TIMEOUT_MS,
) : VoiceBusSpeaker {

    /** Parole en cours sous mutex : une seule lecture du bus à la fois. */
    private val speech = Mutex()

    /** Compteur des paroles : seule la plus récente rend le repos. */
    private var generation = 0L

    override fun idle(): Boolean = player.position() == null

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        // Lue sous le verrou de post() : c'est bien la parole acceptée.
        val (heldLevel, heldToken) = VoiceBus.hold()
        if (BuildConfig.DEBUG) {
            val alive = scope.coroutineContext[Job]?.isActive ?: false
            android.util.Log.d(TAG_MUTE, "bus-parole level=$level texte=[$text] scope-actif=$alive loc=${hashCode()}")
        }
        // Superviseur de restitution (frère, pas enfant : il survit à la
        // parole elle-même — une lecture figée ne rend jamais le bus).
        scope.launch {
            delay(RELEASE_TIMEOUT_MS)
            if (VoiceBus.holds(heldLevel, heldToken)) {
                android.util.Log.w(TAG_MUTE, "bus-sans-retour level=$level texte=[$text] ms=$RELEASE_TIMEOUT_MS")
                VoiceBus.onSpeechIdle()
            }
        }
        scope.launch {
            // Hors mutex : la coupe libère la séquence qui le tient.
            player.stop()
            var current = -1L
            var played = false
            val done = withTimeoutOrNull(sayTimeoutMs) {
                speech.withLock {
                    current = ++generation
                    if (clips.isNotEmpty()) {
                        val seqOk = this@BusSpeaker.clips.speakSequence(clips, urgent = true)
                        played = seqOk
                        if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "bus-seq level=$level ok=$seqOk n=${clips.size}")
                        if (current == generation) VoiceBus.onSpeechIdle()
                        return@withLock
                    }
                    val id = clipId ?: spokenClipId(text)
                    val slice = if (level == CrossingLevels.VERDICT) null else VOICE_SLICE_MS
                    val clipOk = id != null && this@BusSpeaker.clips.speak(id, urgent = true, sliceMs = slice)
                    val proxOk = clipOk || proximity.speak(text, clipId = null, level = level)
                    played = clipOk || proxOk
                    muteOutcome(level, text, clipId, id, clipOk, proxOk)
                    if (!clipOk && !proxOk && current == generation) {
                        VoiceBus.onSpeechIdle()
                    }
                }
            }
            if (done == null) {
                android.util.Log.w(TAG_MUTE, "bus-depassement level=$level texte=[$text] ms=$sayTimeoutMs joue=$played")
                if (current == generation) VoiceBus.onSpeechIdle()
            }
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
