package dev.sam3vision.core.live

import dev.sam3vision.core.model.SeenTrack
import dev.sam3vision.core.model.TimelineSummary
import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Locale

/**
 * Mise en forme du journal caméra pour le LLM et pour l'UI.
 *
 * Les temps sont **relatifs** (« il y a 3 s ») : le modèle n'a pas l'horloge,
 * et l'utilisateur non plus. Les positions suivent le cadran horaire
 * (« à 9 heures », « à midi », « à 3 heures »), exactement comme la voix.
 *
 * Le contexte envoyé au LLM suit la **langue de la voix** ([SpeechLanguage]) : un
 * journal français dans une consigne anglaise fait dériver la réponse. Les étiquettes
 * de l'interface ([chipLabel]) restent en français.
 */
object TimelineText {

    /** Contexte complet transmis au LLM : la fenêtre globale + le point focal demandé. */
    fun context(
        global: TimelineSummary,
        focus: TimelineSummary? = null,
        focusLabel: String? = null,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String {
        val parts = mutableListOf<String>()
        parts += describe(global, titles(language).first, language)
        if (focus != null && !focus.empty) {
            parts += describe(focus, focusLabel ?: titles(language).second, language)
        }
        return parts.joinToString("\n\n")
    }

    /** Les deux titres du contexte, par langue : journal global, puis fenêtre demandée. */
    private fun titles(language: SpeechLanguage): Pair<String, String> = when (language) {
        SpeechLanguage.Fr -> "Ce que la caméra a vu récemment" to "Au moment demandé"
        SpeechLanguage.En -> "What the camera saw recently" to "At the requested moment"
    }

    /** Une ligne d'en-tête + une ligne par objet suivi. */
    fun describe(
        summary: TimelineSummary,
        title: String = "Journal caméra",
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String {
        val header = when (language) {
            SpeechLanguage.Fr -> "$title (de ${ago(summary.toMs, summary.fromMs)} à maintenant, " +
                "${summary.tracks.size} objet(s)) :"
            SpeechLanguage.En -> "$title (from ${ago(summary.toMs, summary.fromMs)} to now, " +
                "${summary.tracks.size} object(s)):"
        }
        if (summary.empty) {
            return header + when (language) {
                SpeechLanguage.Fr -> "\n- rien de visible"
                SpeechLanguage.En -> "\n- nothing visible"
            }
        }
        return (listOf(header) + summary.tracks.map { trackLine(summary.toMs, it, language) })
            .joinToString("\n")
    }

    /** Une ligne par objet : position, dernière vue, apparition, occurrences. */
    fun trackLine(nowMs: Long, track: SeenTrack, language: SpeechLanguage = SpeechLanguage.Fr): String =
        when (language) {
            SpeechLanguage.Fr -> buildString {
                append("- « ").append(track.label).append(" » (suivi n°").append(track.id).append(") : ")
                append(position(track))
                append(", vue la dernière fois ").append(ago(nowMs, track.lastAtMs))
                append(", apparue ").append(ago(nowMs, track.firstAtMs))
                append(", présente sur ").append(track.frames).append(" image(s)")
            }
            SpeechLanguage.En -> buildString {
                append("- \"").append(track.label).append("\" (track #").append(track.id).append("): ")
                append(position(track, language))
                append(", last seen ").append(ago(nowMs, track.lastAtMs, language))
                append(", first seen ").append(ago(nowMs, track.firstAtMs, language))
                append(", in ").append(track.frames).append(" frame(s)")
            }
        }

    /** Étiquette compacte pour l'UI : « personne · vu il y a 3 s ». */
    fun chipLabel(nowMs: Long, track: SeenTrack): String =
        "${track.label} · ${ago(nowMs, track.lastAtMs)}"

    /** Repère de position au cadran, comme la voix : « à 9 heures », « à midi ». */
    fun position(track: SeenTrack, language: SpeechLanguage = SpeechLanguage.Fr): String =
        PositionText.clockLabel(track.centerX, language)

    /** Durée relative : « il y a 3 s », « il y a 1 min 05 s », « à l'instant ». */
    fun ago(nowMs: Long, atMs: Long, language: SpeechLanguage = SpeechLanguage.Fr): String {
        val delta = (nowMs - atMs).coerceAtLeast(0L)
        val seconds = delta / 1_000L
        val minutes = seconds / 60L
        val rest = seconds % 60L
        return when (language) {
            SpeechLanguage.Fr -> when {
                delta < 1_000L -> "à l'instant"
                seconds < 60L -> "il y a $seconds s"
                else -> "il y a $minutes min ${String.format(Locale.FRANCE, "%02d", rest)} s"
            }
            SpeechLanguage.En -> when {
                delta < 1_000L -> "just now"
                seconds < 60L -> "$seconds s ago"
                else -> "$minutes min ${String.format(Locale.US, "%02d", rest)} s ago"
            }
        }
    }
}
