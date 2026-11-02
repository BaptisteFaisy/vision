package dev.sam3vision.core.speech

/**
 * Clips trottoir courts (pastille 500 ms, § 46) : sortis du registre
 * ([IseeClipsEn], plafond 500 lignes) — mêmes conventions (consts + carte,
 * miroir du catalogue `tools/isee-clips-en-catalog.json`, garde
 * `tools/check-isee-clips-en.py` qui lit les deux fichiers).
 */
object IseeClipsSidewalk {
    const val AHEAD_SHORT = "sidewalk_ahead_short"
    const val AHEAD_SHORT_TEXT = "Sidewalk."
    const val LEFT_SHORT = "sidewalk_left_short"
    const val LEFT_SHORT_TEXT = "Sidewalk left."
    const val RIGHT_SHORT = "sidewalk_right_short"
    const val RIGHT_SHORT_TEXT = "Sidewalk right."
    const val ACROSS_SHORT = "sidewalk_across_short"
    const val ACROSS_SHORT_TEXT = "Sidewalk across."

    /** Carte id → texte, fusionnée dans [IseeClipsEn.all]. */
    internal fun short(): Map<String, String> = mapOf(
        AHEAD_SHORT to AHEAD_SHORT_TEXT,
        LEFT_SHORT to LEFT_SHORT_TEXT,
        RIGHT_SHORT to RIGHT_SHORT_TEXT,
        ACROSS_SHORT to ACROSS_SHORT_TEXT,
    )
}
