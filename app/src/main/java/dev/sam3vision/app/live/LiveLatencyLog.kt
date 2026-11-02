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
 * seule source de mise en forme, et le parseur ne connaît qu'elle. Une seconde
 * ligne ([TimingsText.servers]) détaille les étages serveur (`timings_ms`) pour
 * le diagnostic — le parseur l'ignore (pas de `latency N`). Les frames
 * incomplètes (pas de poste capteur, écran sorti) ne sont jamais loggées : une
 * latence inventée vaudrait moins que rien.
 *
 * Les **échecs** de frame passent par [failure] : une ligne par seconde au plus
 * (compteur d'échecs consécutifs joint), là où le `Log.w` nu en écrivait une par
 * frame et noyait logcat (audit C4).
 */
object LiveLatencyLog {

    const val TAG = "ISEE_LAT"

    /** Tag des lignes d'échec du direct : celui que `scripts/lat_stats.py` filtre déjà. */
    const val FAIL_TAG = "sam3vision"

    /** Fenêtre du throttle d'échec : une ligne par seconde, pas une par frame. */
    const val FAILURE_EVERY_MS = 1_000L

    /** Dernière ligne d'échec écrite (ms) et nombre de lignes écrasées depuis. */
    private var lastFailureMs: Long? = null
    private var suppressedFailures = 0

    /**
     * Ligne brute d'une frame dessinée, `null` si elle est incomplète. Pure (aucun
     * Android), testée en JVM — [frame] n'ajoute que l'écriture logcat.
     */
    fun line(t: FrameTimings): String? = if (t.complete) TimingsText.compact(t) else null

    /**
     * Ligne serveur d'une frame dessinée, `null` si incomplète ou sans détail
     * serveur (`FAST_TIMING=0`). Pure, testée en JVM.
     */
    fun serverLine(t: FrameTimings): String? =
        if (t.complete && t.server.isNotEmpty()) TimingsText.servers(t.server) else null

    /** Écrit la frame complète en logcat (perçu + détail serveur) ; silencieuse sinon. Ne lève jamais. */
    fun frame(t: FrameTimings) {
        line(t)?.let { Log.i(TAG, it) }
        serverLine(t)?.let { Log.i(TAG, it) }
    }

    /** Annonce le transport WS de la session ([TimingsText.leg]) ; ne lève jamais. */
    fun leg(binary: Boolean) {
        Log.i(TAG, TimingsText.leg(binary))
    }

    /**
     * Ligne brute d'une frame jetée : « latency — · cause sature ». Pure (aucun
     * Android), testée en JVM — [drop] n'ajoute que l'écriture logcat. Le parseur
     * la compte dans ses jetées (`parse_isee_lat.py`, lignes `latency` sans
     * mesure) et ignore le token `cause` ; miroir de `LinkDrop` côté lunettes.
     */
    fun dropLine(cause: DropCause): String = "latency — · cause ${cause.code}"

    /** Écrit la frame jetée en logcat (comptée par le parseur, hors série) ; ne lève jamais. */
    fun drop(cause: DropCause) {
        Log.i(TAG, dropLine(cause))
    }

    /**
     * Une ligne d'échec est-elle due à [nowMs] ? Vraie la première fois, puis au plus
     * une par [everyMs]. Pure (aucun Android), testée en JVM — [failure] n'ajoute que
     * l'écriture logcat.
     */
    fun failureDue(nowMs: Long, lastAtMs: Long?, everyMs: Long = FAILURE_EVERY_MS): Boolean =
        lastAtMs == null || nowMs - lastAtMs >= everyMs || nowMs < lastAtMs

    /**
     * Ligne brute d'un échec de frame : cause lisible (classe, message, cause imbriquée)
     * et compte d'échecs consécutifs. Pure, testée en JVM.
     */
    fun failureLine(error: Throwable, failures: Int): String {
        val cause = buildString {
            append(error::class.simpleName)
            error.message?.let { append(' ').append(it) }
            error.cause?.let { nested ->
                append(" + ").append(nested::class.simpleName).append(' ').append(nested.message)
            }
        }
        return "live KO: $cause" + if (failures > 1) " (x$failures)" else ""
    }

    /**
     * Écrit un échec de frame, **au plus une ligne par seconde** : sans ce throttle un
     * lien coupé écrivait un `Log.w` par frame (~2/s, non borné) et noyait logcat.
     * Rend `true` si la ligne est passée ; les écrasées sont comptées et rappelées
     * dans la suivante. Ne lève jamais.
     */
    fun failure(error: Throwable, failures: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(this) {
            if (!failureDue(nowMs, lastFailureMs)) {
                suppressedFailures += 1
                return false
            }
            lastFailureMs = nowMs
            val merged = suppressedFailures
            suppressedFailures = 0
            val suffix = if (merged > 0) " (+$merged écrasés)" else ""
            Log.w(FAIL_TAG, failureLine(error, failures) + suffix)
            return true
        }
    }

    /** Oublie l'état du throttle et les compteurs d'échec (nouvelle séance, tests). */
    fun resetFailures() {
        synchronized(this) {
            lastFailureMs = null
            suppressedFailures = 0
        }
    }
}

/**
 * Cause d'abandon d'une frame Direct/sonde, pour le journal ([LiveLatencyLog.drop]).
 * Le [code] est un mot unique, stable : le parseur et les recettes le relisent tel quel.
 */
enum class DropCause(val code: String) {
    /** Réserve pleine : une frame plus fraîche a remplacé celle-ci. */
    SATURATED("sature"),

    /** Délai réseau dépassé : la réponse n'arrivera pas, la frame est perdue. */
    TIMEOUT("timeout"),

    /** App en arrière-plan : la frame est jetée, la carte reste éteinte. */
    BACKGROUND("ecran"),
}
