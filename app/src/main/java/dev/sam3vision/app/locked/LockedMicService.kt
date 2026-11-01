package dev.sam3vision.app.locked

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.sam3vision.app.DictationRecorder
import dev.sam3vision.app.MicBus
import dev.sam3vision.app.VoiceAnnouncer
import dev.sam3vision.app.llmClient
import dev.sam3vision.app.loadedSettings
import dev.sam3vision.app.speechClient
import dev.sam3vision.app.tap.hasAudioPermission
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.speech.LockedMic
import dev.sam3vision.core.speech.LockedMicPhase
import dev.sam3vision.core.speech.LockedMicStartFx
import dev.sam3vision.core.speech.LockedMicStartRunner
import dev.sam3vision.core.speech.LockedMicState
import dev.sam3vision.core.speech.MicStartOutcome
import dev.sam3vision.core.speech.parseMicStartAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Micro **mains libres** de l'écran verrouillé, en service de premier plan.
 *
 * Pourquoi un service, et pourquoi armé depuis l'activité : Android 11+ refuse le micro à une
 * app en arrière-plan, et Android 12+ refuse de démarrer un service de premier plan depuis
 * l'arrière-plan. La seule séquence légale est donc : iSee est visible → le service est armé
 * (type `microphone`, notification prête) → l'écran se verrouille → l'action de la
 * notification (ou la tuile) parle à un service **déjà vivant**.
 *
 * Ce que le service fait, dans l'ordre : ouvrir le micro ([DictationRecorder] borné par
 * [LockedMic.MAX_RECORD_MS] — aucune relâche ne viendra), rendre le PCM à [LockedMicAnswer],
 * dire la réponse, publier l'état affiché. Rien d'autre : les règles sont ailleurs, testées.
 */
class LockedMicService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** État courant : une seule source, republiée dans [LockedMicBridge] à chaque changement. */
    private var state: LockedMicState = LockedMicState()

    private var recorder: DictationRecorder? = null
    private var autoSend: Job? = null
    private var wake: PowerManager.WakeLock? = null

    private val store: SettingsStore by lazy { SettingsStoreFactory.create(this) }
    private val voice by lazy { VoiceAnnouncer(this, VOICE_DIR) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Le canal peut manquer sa création (système saturé au démarrage à froid) : le
        // démarrage s'en sortira (premier plan refusé = arrêt parlé), jamais un FATAL ici.
        runCatching { LockedMicNotifications.ensureChannel(this) }
            .onFailure { Log.w(LockedMic.TAG, "canal de notification : ${it.javaClass.simpleName} — ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Même si le service était mort, le geste le remet debout avant d'agir : un
        // PendingIntent de notification réveille le service, et armer juste après évite
        // le cas « la notification ne répond plus » (processus tué entre deux).
        //
        // Premier plan ou arrêt, sans exception possible : la décision vit dans le runner
        // (`:core`, testé en JVM, chaque effet gardé — une notification qui lève ou un
        // premier plan refusé n'empêchent jamais l'arrêt). Le `catch` ne sert qu'en dernier
        // ressort : si le runner lui-même était interrompu, le service s'arrête quand même.
        val outcome = try {
            LockedMicStartRunner(startFx()).run(
                action = parseMicStartAction(intent?.action),
                current = state,
                hasAudio = hasAudioPermission(this),
            )
        } catch (t: Throwable) {
            Log.w(LockedMic.TAG, "démarrage interrompu : ${t.javaClass.simpleName} — ${t.message}")
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        outcome.warnings.forEach { Log.w(LockedMic.TAG, it) }
        return when (outcome) {
            is MicStartOutcome.Armed -> START_STICKY
            is MicStartOutcome.Refused -> {
                Log.w(LockedMic.TAG, "armement refusé : ${outcome.reason}")
                START_NOT_STICKY
            }
            is MicStartOutcome.Stopped -> START_NOT_STICKY
        }
    }

    /**
     * Les effets du démarrage, un par appel Android : le runner les garde un par un, donc
     * chacun reste naïf ici (celui qui lève est noté, le suivant part quand même).
     */
    private fun startFx() = object : LockedMicStartFx {
        override fun publish(state: LockedMicState) = this@LockedMicService.publish(state)

        override fun enterForeground(state: LockedMicState) {
            ServiceCompat.startForeground(
                this@LockedMicService,
                LockedMicNotifications.NOTIFICATION_ID,
                LockedMicNotifications.build(this@LockedMicService, state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }

        override fun stopForeground() =
            ServiceCompat.stopForeground(this@LockedMicService, ServiceCompat.STOP_FOREGROUND_REMOVE)

        override fun stopSelf() {
            this@LockedMicService.stopSelf()
        }

        override fun toggle() = this@LockedMicService.toggle()

        override fun cancelAutoSend() {
            autoSend?.cancel()
            autoSend = null
        }

        override fun releaseRecorder() {
            recorder?.let { rec ->
                rec.stopAndCollect()
                MicBus.release(MicBus.Owner.DICTATION)
            }
            recorder = null
        }

        override fun releaseWakeLock() = this@LockedMicService.releaseWakeLock()
        override fun stopVoice() = voice.stop()
    }

    /** Le geste unique : fermé → écoute ; en écoute → envoie. */
    @Synchronized
    private fun toggle() {
        Log.i(LockedMic.TAG, "geste reçu en phase ${state.phase}")
        if (state.phase == LockedMicPhase.LISTENING) send() else listen()
    }

    /** Ouvre le micro : refus explicite si le micro est pris (vidéo du mode tap). */
    @Synchronized
    private fun listen() {
        if (state.phase == LockedMicPhase.LISTENING) return
        val rec = DictationRecorder(maxDurationMs = LockedMic.MAX_RECORD_MS)
        if (MicBus.busyFor(MicBus.Owner.DICTATION)) {
            publish(state.failed("microphone already busy with video"))
            return
        }
        if (!rec.start()) {
            publish(state.failed("microphone unavailable (permission or hardware)"))
            return
        }
        Log.i(LockedMic.TAG, "micro ouvert")
        voice.stop()
        MicBus.acquire(MicBus.Owner.DICTATION)
        recorder = rec
        holdWakeLock()
        publish(state.listen(System.currentTimeMillis()))
        autoSend = scope.launch {
            delay(LockedMic.MAX_RECORD_MS + AUTO_SEND_MARGIN_MS)
            send()
        }
    }

    /**
     * Ferme le micro et fait le trajet complet. Synchrone pour l'état (le micro est rendu tout
     * de suite), asynchrone pour le réseau et la voix.
     */
    @Synchronized
    private fun send() {
        val rec = recorder ?: return
        recorder = null
        autoSend?.cancel()
        autoSend = null
        val pcm = rec.stopAndCollect()
        Log.i(LockedMic.TAG, "micro fermé : ${pcm.size} octets PCM")
        MicBus.release(MicBus.Owner.DICTATION)
        releaseWakeLock()
        publish(state.transcribing())

        scope.launch {
            val settings = loadSettings()
            val chain = LockedMicAnswer(
                speech = speechClient(settings),
                llm = llmClient(settings),
                speak = { answer -> voice.say(settings, answer) },
                settings = { settings },
            )
            val outcome = chain.run(pcm)
            outcome.fold(
                onSuccess = { Log.i(LockedMic.TAG, "réponse : ${it.answer.take(80)}") },
                onFailure = { Log.w(LockedMic.TAG, "échec : ${it.message}") },
            )
            publish(
                outcome.fold(
                    onSuccess = { state.answered(it.answer) },
                    onFailure = { state.failed(it.message ?: "failure without message") },
                ),
            )
        }
    }

    /**
     * App retirée des récentes : le service **reste armé**. Mesuré sur ce téléphone (HyperOS) :
     * le processus se fait tuer dès que l'app n'est plus visible, et un service arrêté ici
     * laissait l'écran verrouillé sans aucune notification — donc sans bouton. Il ne s'arrête
     * plus que sur le geste « Stop » ou à la fermeture explicite de l'activité.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(LockedMic.TAG, "tâche retirée : le service reste armé")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        recorder = null
        MicBus.release(MicBus.Owner.DICTATION)
        releaseWakeLock()
        publish(state.off())
        super.onDestroy()
    }

    /** Publie l'état : pont (tuile) puis notification, dans cet ordre. */
    private fun publish(next: LockedMicState) {
        state = next
        LockedMicBridge.publish(next)
        LockedMicNotifications.show(this, next)
    }

    /** Réveil partiel **pendant la capture seulement** : l'écran est éteint, la CPU dort. */
    private fun holdWakeLock() {
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                ?.apply { setReferenceCounted(false) }
        }
        wake?.acquire(LockedMic.MAX_RECORD_MS + AUTO_SEND_MARGIN_MS + WAKE_MARGIN_MS)
    }

    private fun releaseWakeLock() {
        wake?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wake = null
    }

    /**
     * Réglages lus hors du thread principal (Keystore : quelques dizaines de ms) **et amorcés
     * par l'APK** (`loadedSettings`, qui scelle les clés du build dans le store) : sans cet
     * amorçage un téléphone neuf répondrait « aucun moteur de dictée ».
     */
    private suspend fun loadSettings(): Settings =
        withContext(Dispatchers.IO) { loadedSettings(store) }

    private companion object {
        /** Dossier de voix distinct : n'écrase pas le MP3 des bandeaux de dictée. */
        const val VOICE_DIR = "locked-voice"

        /** Marge du minuteur d'envoi automatique sur le plafond du micro. */
        const val AUTO_SEND_MARGIN_MS = 150L

        /** Marge du réveil partiel au-delà du plafond (réseau compris). */
        const val WAKE_MARGIN_MS = 120_000L

        const val WAKE_TAG = "iSee:microVerrouille"
    }
}