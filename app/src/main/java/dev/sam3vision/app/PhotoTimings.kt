package dev.sam3vision.app

import android.os.SystemClock
import android.util.Log

/** Tag logcat unique des mesures photo : `adb logcat -s ISEE_PERF`. */
const val PERF_TAG = "ISEE_PERF"

/**
 * Chronologie d'une analyse photo : chargement local (`charge`), segmentation
 * (`sam`, via `onSamOutcome`), fin d'analyse (`analyse`, = LLM), masques
 * (`overlays`). Horloge et sortie injectables (tests JVM). Une ligne résumé par
 * analyse, en logcat et à l'écran.
 */
class PhotoTimings(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { line -> Log.d(PERF_TAG, line) },
) {

    private val marks = LinkedHashMap<String, Long>()
    private var startedAt = -1L

    /** Début d'analyse (clic reçu) : raz et top départ. */
    fun begin() {
        marks.clear()
        startedAt = clock()
        marks[CLIC] = startedAt
    }

    /** Jalon nommé. Inerte avant [begin]. */
    fun mark(name: String) {
        if (startedAt < 0L) return
        marks[name] = clock()
    }

    /** Durée d'un jalon depuis le début, -1 si non mesuré. */
    fun sinceStart(name: String): Long = marks[name]?.let { it - startedAt } ?: -1L

    /** `photo load=40 sam=60 analysis=800 overlays=48 total=1000` (deltas). */
    fun summary(): String = buildString {
        append("photo")
        var prev = startedAt
        for ((name, at) in marks) {
            if (name == CLIC) continue
            append(" $name=${at - prev}")
            prev = at
        }
        append(" total=${clock() - startedAt}")
    }

    /** Émet le résumé en logcat et le renvoie ("" si [begin] n'a pas eu lieu). */
    fun emit(): String {
        if (startedAt < 0L) return ""
        val line = summary()
        log(line)
        return line
    }

    private companion object {
        const val CLIC = "clic"
    }
}
