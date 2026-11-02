package dev.sam3vision.app.safety

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Nom du journal d'incidents, sous `filesDir` (lu par `adb shell run-as dev.sam3vision.app cat …`). */
internal const val CRASH_LOG_NAME = "crash.log"

/** Délai laissé au journal pour finir son écriture avant de laisser mourir le process. */
internal const val CRASH_FLUSH_MS = 250L

/**
 * Filet d'exception de l'app : plus aucune exception ne tue le process **sans trace**.
 *
 * Trois rôles, aucun coût au démarrage (l'installation ne lit ni n'écrit rien : elle pose un
 * fichier et deux références) :
 *
 * 1. **Coroutines** — [handler] est un `CoroutineExceptionHandler` réutilisable. Posé sur le
 *    contexte d'un scope racine (voir [scope]), il reçoit ce qui serait parti au handler de fil
 *    (donc à `KillApplicationHandler`, donc à la mort du process) : l'incident est journalisé,
 *    l'app survit. L'annulation n'est pas un incident (elle se propage).
 * 2. **Fils nus** — [install] chaîne le handler système (jamais ne le remplace) : l'incident
 *    est tracé **puis** laissé au système. On ne ressuscite pas un process dont on ne connaît
 *    pas l'état (un `main` à moitié démonté donne des bugs pires qu'un crash tracé).
 * 3. **Journal** — [CrashJournal] borné (rotation à 256 Kio, deux fichiers, écriture hors fil,
 *    secrets masqués) : la seule trace disponible après coup, celle qui manquait au crash du
 *    01/11 (retrouvé uniquement dans le dropbox système).
 *
 * Tout est gardé : [note] ne lève jamais, même si le disque est plein ou si le fichier a été
 * supprimé sous nos pieds.
 */
object CrashGuard {
    /** Étiquette logcat (`adb logcat -s sam3vision-crash:*`). */
    const val TAG = "sam3vision-crash"

    /** Journal des incidents (écriture hors fil appelant, borné). */
    private val journal = CrashJournal()

    /** Incidents vus depuis le démarrage (diagnostic, jamais remis à zéro en prod). */
    private val seen = AtomicLong(0)

    /** Une seule installation par process (un second appel est un no-op). */
    private val installed = AtomicBoolean(false)

    /**
     * Handler de coroutine réutilisable : journalise l'échec d'un `launch` racine au lieu de
     * laisser le process mourir. À poser sur le contexte d'un scope ([scope]) — un `try` local
     * ne protège que la ligne où il est écrit.
     */
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { context, error ->
        if (error !is CancellationException) {
            note("coroutine:${context[CoroutineName]?.name ?: "?"}", error)
        }
    }

    /**
     * Scope racine protégé : superviseur (l'échec d'un enfant n'annule pas ses frères), nom
     * lisible dans le journal, et [handler]. À préférer à `CoroutineScope(SupervisorJob() + …)`
     * pour tout scope qui vit plus longtemps qu'un écran.
     */
    fun scope(name: String, dispatcher: CoroutineDispatcher = Dispatchers.IO): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineName(name) + handler)

    /**
     * Installe le filet : journal sous `filesDir`, handler de fil chaîné au handler système.
     * Appelé une fois depuis `SamApp.onCreate` ; ne lit ni n'écrit rien (démarrage inchangé).
     */
    fun install(filesDir: File) {
        if (!installed.compareAndSet(false, true)) return
        attach(File(filesDir, CRASH_LOG_NAME))
        Thread.setDefaultUncaughtExceptionHandler(
            uncaughtHandler(Thread.getDefaultUncaughtExceptionHandler()),
        )
    }

    /**
     * Handler de fil : journalise, laisse le journal finir, **puis** délègue au handler
     * précédent (le système sur Android). Pur au sens du test : [previous] est injecté.
     */
    internal fun uncaughtHandler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            note("thread:${thread.name}", error)
            journal.drain(CRASH_FLUSH_MS)
            previous?.uncaughtException(thread, error)
        }

    /**
     * Journalise une [error] vue en [origin] : une ligne logcat (toujours, même sans journal)
     * et une ligne disque (bornée, sans secret). Ne lève jamais, ne bloque pas l'appelant.
     */
    fun note(origin: String, error: Throwable) {
        seen.incrementAndGet()
        runCatching {
            Log.e(TAG, "[$origin] ${error.javaClass.name}: ${redactSecrets(error.message)}")
        }
        runCatching {
            journal.enqueue(
                JournalLine.of(
                    millis = System.currentTimeMillis(),
                    thread = Thread.currentThread().name,
                    origin = origin,
                    error = error,
                ),
            )
        }
    }

    /** Incidents journalisés depuis le démarrage. */
    fun incidents(): Long = seen.get()

    /**
     * Pointe le journal sur [file] sans toucher au handler de fil : réservé aux tests JVM
     * (l'installation réelle passe par [install], une seule fois par process).
     */
    internal fun attach(file: File) {
        journal.open(file)
    }

    /** Attend que le journal ait fini d'écrire (chemin de crash, tests) ; vrai si vidé. */
    internal fun flush(timeoutMs: Long = 2_000L): Boolean = journal.drain(timeoutMs)
}
