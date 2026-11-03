package dev.sam3vision.app.voicenav

import android.util.Log
import dev.sam3vision.app.safety.CrashGuard

/**
 * Compte-rendu partagé de la tenue du premier plan, pour les trois services longs
 * (guidage vocal, suivi parent, session lunettes) : chaque incident du runner part en
 * logcat **et** au journal persistant ([CrashGuard]), au lieu du seul logcat volatile.
 *
 * C'est la leçon du crash du 01/11 (`ForegroundServiceDidNotStartInTimeException`,
 * retrouvé uniquement dans le dropbox système) : un refus de premier plan qui ne laisse
 * aucune trace persistante est une panne silencieuse. Ici, même un refus muet (aucun
 * incident noté par le runner) laisse sa ligne au journal.
 */
fun reportForegroundHold(
    tag: String,
    origin: String,
    held: Boolean,
    label: String,
    incidents: List<String>,
) {
    linesOf(held, label, incidents).forEach {
        Log.w(tag, it)
        CrashGuard.noteEvent(origin, it)
    }
}

/** Lignes du compte-rendu : un refus muet reste tracé, une tenue propre reste silencieuse. */
private fun linesOf(held: Boolean, label: String, incidents: List<String>): List<String> {
    if (incidents.isNotEmpty()) return incidents.map { "$label : $it" }
    if (!held) return listOf(label)
    return emptyList()
}
