package dev.sam3vision.app.live

import android.util.Log
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.TimingsText

/**
 * Journal logcat de la latence perçue : une ligne par frame **complète** (capture →
 * pixel mesurés), tag unique [TAG] (`adb logcat -s ISEE_LAT`). Les deux écrans qui
 * parlent à SAM (Direct, iSee) appellent [frame] depuis leur `noteDrawn`, après la
 * composition : c'est la série qui prouve le 500 ms max / 300 ms en moyenne
 * (`scripts/parse_isee_lat.py` l'agrège hors ligne en p50/p95/max).
 *
 * La ligne est exactement [TimingsText.compact] (la même chaîne que l'écran) : une
 * seule source de mise en forme, et le parseur ne connaît qu'elle. Les frames
 * incomplètes (pas de poste capteur, écran sorti) ne sont jamais loggées : une
 * latence inventée vaudrait moins que rien.
 */
object LiveLatencyLog {

    const val TAG = "ISEE_LAT"

    /**
     * Ligne brute d'une frame dessinée, `null` si elle est incomplète. Pure (aucun
     * Android), testée en JVM — [frame] n'ajoute que l'écriture logcat.
     */
    fun line(t: FrameTimings): String? = if (t.complete) TimingsText.compact(t) else null

    /** Écrit la frame complète en logcat ; silencieuse sinon. Ne lève jamais. */
    fun frame(t: FrameTimings) {
        line(t)?.let { Log.i(TAG, it) }
    }
}
