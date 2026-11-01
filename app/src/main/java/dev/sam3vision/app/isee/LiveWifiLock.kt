package dev.sam3vision.app.isee

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Verrou Wi-Fi haute-perf des sessions live : le Wi-Fi en économie d'énergie
 * (doze, batching radio) ajoute 10-30 ms par aller-retour — le tenir éveillé
 * pendant le direct efface l'essentiel. Acquis à la première frame envoyée
 * ([LiveProbe.dispatch]), rendu par [LiveProbe.stop], l'entonnoir unique de
 * fin de direct (caméra coupée, arrière-plan, `onCleared`) : pas d'autre
 * chemin de sortie, donc pas de fuite. Jamais tenu hors direct (batterie).
 *
 * Permissions : `createWifiLock` n'exige que `WAKE_LOCK` (normale, sans
 * dialogue — déjà déclarée au manifeste pour le micro verrouillé). Ni
 * `ACCESS_WIFI_STATE` ni `CHANGE_WIFI_STATE` : on ne lit ni ne change l'état
 * du Wi-Fi, on tient juste la radio éveillée. Manifeste inchangé.
 *
 * Sécurité : passé [WIFI_LOCK_TIMEOUT_MS] sans `release`, la libération est
 * forcée (même si `stop` manquait) ; tant que les frames coulent, la tenue
 * est renouvelée tous les [WIFI_LOCK_REFRESH_MS]. Radio absente ou verrou
 * refusé : repli silencieux, le direct continue sans lui.
 */
internal const val WIFI_LOCK_TAG = "iSee:live"
internal const val WIFI_LOCK_TIMEOUT_MS = 20 * 60 * 1000L
internal const val WIFI_LOCK_REFRESH_MS = 10 * 60 * 1000L
private const val WIFI_LOCK_RETRY_MS = 60 * 1000L

/** Poignée de verrou Wi-Fi : la prod tient la radio, les tests un faux. */
interface WifiLockHandle {
    fun acquire()
    fun release()
}

/** Fabrique de poignée : la prod ouvre le `WifiManager`, les tests un faux. */
fun interface WifiLockFactory {
    fun create(context: Context?): WifiLockHandle?
}

/** Fabrique Android : `null` sans contexte ou sans `WifiManager` (repli). */
internal fun androidWifiLockFactory(): WifiLockFactory = WifiLockFactory { context ->
    runCatching {
        val manager = context?.applicationContext?.getSystemService(WifiManager::class.java)
            ?: return@WifiLockFactory null
        AndroidWifiLock(manager)
    }.getOrNull()
}

/** Poignée réelle : `WIFI_MODE_FULL_LOW_LATENCY` non compté (minSdk 29). */
internal class AndroidWifiLock(manager: WifiManager) : WifiLockHandle {
    private val lock = runCatching {
        manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)
            .apply { setReferenceCounted(false) }
    }.getOrNull()

    override fun acquire() {
        val held = lock ?: error("verrou Wi-Fi indisponible")
        if (!held.isHeld) held.acquire()
    }

    override fun release() {
        val held = lock ?: return
        if (held.isHeld) held.release()
    }
}

/**
 * Planificateur de la libération forcée : `Handler` principal en prod
 * (aucun fil créé), piloté à la main en test. Rend une annulation.
 */
internal fun interface WifiLockScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** Planificateur réel : le fil principal, sans fil ni réveil supplémentaires. */
internal fun mainWifiLockScheduler(): WifiLockScheduler = WifiLockScheduler { delayMs, task ->
    val handler = Handler(Looper.getMainLooper())
    val runnable = Runnable { task() }
    handler.postDelayed(runnable, delayMs)
    return@WifiLockScheduler { handler.removeCallbacks(runnable) }
}

/**
 * Tient la radio éveillée pendant le direct : [hold] à chaque frame envoyée
 * (acquiert une fois, renouvelle passé [WIFI_LOCK_REFRESH_MS]), [release] à
 * la fin du direct. Idempotent, thread-sûr (`hold` vient du fil caméra,
 * `release` du fil principal), ne jette jamais.
 */
internal class LiveWifiLock(
    private val context: Context?,
    private val factory: WifiLockFactory = androidWifiLockFactory(),
    private val scheduler: WifiLockScheduler = mainWifiLockScheduler(),
    /** Horloge monotone en ms ; injectable en test. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val log: (String) -> Unit = { line -> Log.i("sam3vision", line) },
    private val warn: (String) -> Unit = { line -> Log.w("sam3vision", line) },
) {
    private var handle: WifiLockHandle? = null
    private var held = false
    private var acquiredAt = 0L
    private var failedAt: Long? = null
    private var cancelArmed: (() -> Unit)? = null

    /** Vrai quand la radio est tenue (tests : prouve l'acquisition). */
    val isHeld: Boolean get() = synchronized(this) { held }

    fun hold() {
        synchronized(this) {
            if (held) {
                if (clock() - acquiredAt < WIFI_LOCK_REFRESH_MS) return
                releaseLocked("renouvellement")
            }
            val failed = failedAt
            if (failed != null && clock() - failed < WIFI_LOCK_RETRY_MS) return
            val current = handle ?: runCatching { factory.create(context) }.getOrNull()?.also { handle = it }
            if (current == null) {
                failedAt = clock()
                warn("wifilock: échec (WifiManager indisponible, repli sans verrou)")
                return
            }
            runCatching { current.acquire() }
                .onSuccess {
                    held = true
                    acquiredAt = clock()
                    failedAt = null
                    armTimeoutLocked()
                    log("wifilock: acquis (direct, ${WIFI_LOCK_TIMEOUT_MS / 60_000} min max)")
                }
                .onFailure {
                    handle = null
                    failedAt = clock()
                    warn("wifilock: échec (${it.javaClass.simpleName}, repli sans verrou)")
                }
        }
    }

    /** Rend la radio : idempotent (double `stop` sans crash ni double log). */
    fun release() {
        synchronized(this) {
            if (!held) return
            releaseLocked("relâché")
        }
    }

    private fun releaseLocked(motif: String) {
        held = false
        runCatching { cancelArmed?.invoke() }
        cancelArmed = null
        val current = handle
        handle = null
        if (current == null) return
        runCatching { current.release() }
            .onSuccess { log("wifilock: $motif") }
            .onFailure { warn("wifilock: échec libération (${it.javaClass.simpleName})") }
    }

    /** Libération forcée passée [WIFI_LOCK_TIMEOUT_MS] sans `release`. */
    private fun armTimeoutLocked() {
        runCatching { cancelArmed?.invoke() }
        cancelArmed = runCatching {
            scheduler.schedule(WIFI_LOCK_TIMEOUT_MS) {
                synchronized(this) {
                    if (held && clock() - acquiredAt >= WIFI_LOCK_TIMEOUT_MS) {
                        releaseLocked("libération forcée (timeout, stop manqué ?)")
                    }
                }
            }
        }.getOrNull()
    }
}
