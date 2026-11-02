package dev.sam3vision.app.safety

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Plafond d'un journal (256 Kio) : au-delà, il tourne en `.1` (deux fichiers, jamais plus). */
internal const val JOURNAL_MAX_BYTES = 256L * 1024L

/** Entrées en attente d'écriture : au-delà, une entrée est perdue plutôt que de bloquer l'appelant. */
private const val QUEUE_CAPACITY = 32

/** Longueur maximale du message retenu (une ligne reste une ligne). */
private const val MESSAGE_MAX = 200

/** Frames de pile retenues par incident : assez pour nommer le site, borné pour rester lisible. */
internal const val JOURNAL_FRAMES = 3

/** Date des lignes (`yyyy-MM-dd HH:mm:ss.SSS`, jamais localisée : tri lexicographique). */
private val STAMP = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
}

/**
 * Habillage d'une ligne de journal : une ligne physique, jamais secrète.
 *
 * Format (champs stables, lisibles par `grep`) :
 * `2026-11-02 18:40:01.123 thread=main origin=probe-frame type=java.lang.IllegalStateException
 * msg=[...] frames=[A.b(A.kt:12) | C.d(C.kt:3)]`. Les valeurs d'API (`sk-…`, `ghp_…`,
 * `Bearer …`, `clé=valeur`) sont masquées par [redactSecrets] : un journal de crash ne doit
 * jamais recopier une clé sur disque. Pures, testées en JVM.
 */
internal object JournalLine {

    /** Ligne complète d'un incident ([millis] = horodatage, [thread] = fil fautif). */
    fun of(millis: Long, thread: String, origin: String, error: Throwable): String {
        val frames = framesOf(error)
        return "${stampOf(millis)} thread=${field(thread)} origin=${field(origin)} " +
            "type=${error.javaClass.name} msg=[${redactSecrets(error.message)}] frames=[$frames]"
    }

    /** Trois premières frames (`Classe.methode(Fichier.kt:ligne)`), jointes par ` | `. */
    fun framesOf(error: Throwable): String = error.stackTrace
        .take(JOURNAL_FRAMES)
        .joinToString(" | ") { frame ->
            "${frame.className.substringAfterLast('.')}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
        }

    /** Horodatage `yyyy-MM-dd HH:mm:ss.SSS` (UTC local du poste, monotone à l'écriture près). */
    fun stampOf(millis: Long): String = STAMP.get()!!.format(Date(millis))

    /** Nom de champ : ni espace ni saut de ligne (une ligne = un incident). */
    private fun field(raw: String): String = raw.replace('\n', ' ').replace(' ', '_').take(64)
}

/** Un masque : le motif du secret et ce qui le remplace (`***`, ou `groupe1***`). */
private class Mask(val pattern: Regex, val replacement: String)

/**
 * Secrets masqués : jetons d'API en clair (`sk-…`, `ghp_…`), entête `Bearer`, et valeurs des
 * champs qui en portent un (`key=…`, `token:…`, `?api_key=…`). Un journal de crash ne doit
 * jamais recopier une clé sur disque — même en debug.
 */
private val SECRET_PATTERNS = listOf(
    Mask(Regex("""sk-[A-Za-z0-9_\-]{6,}"""), "***"),
    Mask(Regex("""ghp_[A-Za-z0-9]{6,}"""), "***"),
    Mask(Regex("""(?i)(?<=bearer )[A-Za-z0-9._\-]{6,}"""), "***"),
    Mask(Regex("""(?i)((?:api[_-]?key|token|secret|password|passwd|key)\s*[=:]\s*)[^\s&,;"']{4,}"""), "${'$'}1***"),
    Mask(Regex("""(?i)([?&](?:key|token|api_key)=)[^&\s]{4,}"""), "${'$'}1***"),
)

/**
 * Message prêt à journaliser : une seule ligne, secrets masqués (`***`), borné à
 * [MESSAGE_MAX] caractères. Jamais d'exception (entrée `null` = chaîne vide) :
 * appelée sur le chemin d'un crash, elle ne doit pas en créer un.
 */
internal fun redactSecrets(raw: String?): String {
    val text = raw?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
    if (text.isEmpty()) return ""
    val masked = SECRET_PATTERNS.fold(text) { acc, mask -> mask.pattern.replace(acc, mask.replacement) }
    return if (masked.length <= MESSAGE_MAX) masked else masked.take(MESSAGE_MAX) + "…"
}

/**
 * Journal d'incidents borné, écrit hors du fil appelant (JVM pure : aucun Android, testable).
 *
 * Une entrée = une ligne ([JournalLine]). L'écriture passe par une file bornée
 * ([QUEUE_CAPACITY]) servie par un fil démon unique : un incident sur le fil principal, dans
 * une coroutine ou sur le fil d'un crash ne bloque jamais sur disque et ne peut jamais lever
 * (tout est gardé, y compris la rotation). Passé [JOURNAL_MAX_BYTES], le journal tourne en
 * `.1` : le disque est borné à deux fichiers, quel que soit le nombre d'incidents.
 *
 * [drain] attend la fin des écritures (chemin d'un crash fatal, tests) ; hors de là, personne
 * n'attend : perdre la dernière ligne est préférable à suspendre l'app.
 */
internal class CrashJournal(private val maxBytes: Long = JOURNAL_MAX_BYTES) {

    /** Fichier courant (`null` avant [open] : les incidents ne sont que logcat). */
    @Volatile
    private var file: File? = null

    /** Entrées pas encore écrites (décrémenté par le fil d'écriture). */
    private val pending = AtomicInteger(0)

    /** Incidents écrits depuis l'ouverture (diagnostic). */
    private val written = AtomicLong(0)

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

    @Volatile
    private var worker: Thread? = null

    /** Pointe le journal sur [target] (créé au premier incident) : ne lit ni n'écrit rien. */
    fun open(target: File) {
        file = target
    }

    /** Nombre d'incidents effectivement écrits sur disque. */
    fun written(): Long = written.get()

    /**
     * Met [line] en file sans bloquer : le fil d'écriture démarre au premier incident.
     * File pleine = entrée perdue (comptée comme écrite pour ne pas fausser [drain]).
     */
    fun enqueue(line: String) {
        pending.incrementAndGet()
        startWorker()
        if (!queue.offer(line)) pending.decrementAndGet()
    }

    /** Vrai si tout a été écrit avant [timeoutMs] (attente active courte, jamais infinie). */
    fun drain(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pending.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(DRAIN_POLL_MS)
        }
        return pending.get() == 0
    }

    /** Démarre le fil d'écriture (une fois, démon : il ne retient pas la JVM). */
    private fun startWorker() {
        if (worker != null) return
        synchronized(this) {
            if (worker != null) return
            worker = Thread(::pump, "crash-journal").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Boucle du fil d'écriture : rien ne sort d'ici (un journal ne tue jamais l'app). */
    private fun pump() {
        while (true) {
            val line = runCatching { queue.poll(1, TimeUnit.SECONDS) }.getOrNull()
            if (line == null) continue
            runCatching { write(line) }
            pending.decrementAndGet()
        }
    }

    /** Écrit une ligne, en tournant le journal s'il est plein (jamais au-delà de deux fichiers). */
    private fun write(line: String) {
        val target = file ?: return
        synchronized(this) {
            if (target.length() >= maxBytes) rotate(target)
            target.appendText(line + "\n")
        }
        written.incrementAndGet()
    }

    /** `crash.log` → `crash.log.1` (l'ancien `.1` est remplacé) ; à défaut, troncature. */
    private fun rotate(target: File) {
        val previous = File(target.path + ".1")
        runCatching { if (previous.exists()) previous.delete() }
        runCatching { target.renameTo(previous) }
        if (target.exists()) runCatching { target.writeText("") }
    }

    private companion object {
        /** Pas d'attente de [drain] (assez court pour un chemin de crash). */
        const val DRAIN_POLL_MS = 5L
    }
}
