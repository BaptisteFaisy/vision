package dev.sam3vision.core.live

import java.lang.reflect.Method
import java.util.Locale
import java.util.function.LongSupplier

/**
 * Résultat d'une mesure : coût unitaire et empreinte mémoire d'**une** opération.
 *
 * `bytesPerOp` vaut `-1` quand la JVM ne sait pas compter les octets alloués par thread
 * (API `com.sun.management`) : on l'affiche « non mesuré » plutôt qu'un zéro inventé.
 */
internal class BenchSample(
    val name: String,
    val microsPerOp: Double,
    val bytesPerOp: Long,
    val ops: Int,
    /** Somme des retours : empêche le JIT de supprimer la boucle mesurée. */
    val checksum: Long,
) {
    /** Octets par opération, ou « non mesuré ». */
    fun bytesText(): String = if (bytesPerOp < 0L) "non mesuré" else "$bytesPerOp o"

    /** Une ligne du tableau : nom, µs/op, octets/op. */
    fun line(): String = String.format(
        Locale.ROOT,
        "  %-34s %9.2f µs/op   %16s   (%d ops)",
        name,
        microsPerOp,
        bytesText(),
        ops,
    )
}

/**
 * Petit banc JVM pour le budget CPU du chemin frame du direct : capture → JPEG → base64 →
 * upload. Aucun Android, aucune horloge murale : `System.nanoTime()` et le compteur
 * d'octets alloués du thread courant.
 *
 * Règle de mesure (celle du contrat de la mission) : [CHAUFFE] passes jetées, puis
 * [ITERATIONS] passes chronométrées. Le total est divisé par le nombre de passes, donc
 * chaque chiffre est un **coût par frame**, comparable d'une version à l'autre.
 *
 * Le retour des opérations est **primitif** ([LongSupplier]) : mesurer avec un `() -> Any?`
 * boxerait un `Long` par passe et le banc mesurerait sa propre allocation. Il reste un
 * plancher de mesure : une opération qui n'alloue **rien** affiche ~7 octets par passe,
 * le `Long` que le `LongSupplier` rend à la boucle. « 7 o » veut donc dire « zéro
 * allocation de l'opération ».
 */
internal class FrameBench(
    private val warmup: Int = CHAUFFE,
    private val iterations: Int = ITERATIONS,
) {

    private val samples = ArrayList<BenchSample>()

    /** Mesure [op] et renvoie son échantillon (le tableau est aussi gardé pour [report]). */
    fun measure(name: String, op: LongSupplier): BenchSample {
        var i = 0
        while (i < warmup) {
            op.asLong
            i++
        }
        val bytesBefore = allocatedBytes()
        val startedAt = System.nanoTime()
        var sink = 0L
        i = 0
        while (i < iterations) {
            sink += op.asLong
            i++
        }
        val elapsedNs = System.nanoTime() - startedAt
        val bytesAfter = allocatedBytes()
        val perOp = if (bytesBefore < 0L || bytesAfter < 0L) -1L else (bytesAfter - bytesBefore) / iterations
        val sample = BenchSample(
            name = name,
            microsPerOp = elapsedNs.toDouble() / iterations / 1_000.0,
            bytesPerOp = perOp,
            ops = iterations,
            checksum = sink,
        )
        samples += sample
        return sample
    }

    /** Tableau lisible de toutes les mesures, prêt à être imprimé par le test. */
    fun report(title: String): String = buildString {
        appendLine(title)
        appendLine("  chauffe $warmup passes, puis $iterations passes chronométrées")
        appendLine("  " + "-".repeat(76))
        for (sample in samples) appendLine(sample.line())
        appendLine("  " + "-".repeat(76))
        appendLine(String.format(Locale.ROOT, "  empreinte des boucles (sink) : %d", checksumOfAll()))
    }

    /** µs/op d'une mesure nommée, `-1` si le nom n'existe pas (garde anti-régression). */
    fun micros(name: String): Double =
        samples.firstOrNull { it.name == name }?.microsPerOp ?: -1.0

    /** Toutes les mesures, pour les assertions du test. */
    fun all(): List<BenchSample> = samples

    /** Somme des empreintes : ne sert qu'à garder les `sink` vivants. */
    private fun checksumOfAll(): Long {
        var acc = 0L
        for (sample in samples) acc += sample.checksum
        return acc
    }

    private companion object {
        /** Passes de chauffe (compilation JIT, caches Branch Prediction). */
        const val CHAUFFE = 50

        /** Passes chronométrées : au-delà des 200 exigés par la mesure. */
        const val ITERATIONS = 250
    }
}

/*
 * Compteur d'octets alloués par thread : `com.sun.management.ThreadMXBean`.
 *
 * Résolu **par réflexion** : les tests unitaires d'un module Android compilent contre
 * `android.jar`, qui ne contient pas `java.lang.management`. Au *runtime*, les tests
 * tournent sur une vraie JVM (HotSpot), où l'API existe — d'où `Class.forName`.
 * Absente (autre JVM) : les octets par opération s'affichent « non mesuré ».
 */
private val allocationBean: Any? = runCatching {
    val factory = Class.forName("java.lang.management.ManagementFactory")
    factory.getMethod("getThreadMXBean").invoke(null)
}.getOrNull()

private val allocationMethod: Method? = runCatching {
    Class.forName("com.sun.management.ThreadMXBean")
        .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        .also { it.isAccessible = true }
}.getOrNull()

/** Octets alloués par le thread courant, `-1` si la JVM ne sait pas le dire. */
internal fun allocatedBytes(): Long {
    val bean = allocationBean ?: return -1L
    val method = allocationMethod ?: return -1L
    return runCatching { method.invoke(bean, currentThreadId()) as Long }.getOrDefault(-1L)
}

/*
 * Identifiant du thread courant, résolu lui aussi par réflexion : `Thread.getId()` est
 * déprécié depuis Java 19 au profit de `threadId()`, et `android.jar` ne connaît que le
 * premier. On prend celui qui existe, sans dépendre du niveau de langue du build.
 */
private fun currentThreadId(): Long {
    val thread = Thread.currentThread()
    for (name in THREAD_ID_NAMES) {
        val id = runCatching { Thread::class.java.getMethod(name).invoke(thread) as Long }.getOrNull()
        if (id != null) return id
    }
    return -1L
}

/** Accès à l'identifiant du thread : le moderne d'abord, l'historique en repli. */
private val THREAD_ID_NAMES = listOf("threadId", "getId")