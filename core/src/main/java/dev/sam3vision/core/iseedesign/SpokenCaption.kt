package dev.sam3vision.core.iseedesign

/** Position réelle dans le média : indépendante de la vitesse de lecture locale. */
data class SpeechPosition(val positionMs: Long, val durationMs: Long)

/** Un court passage et le nombre de mots déjà prononcés dans ce passage. */
data class SpokenCaption(val text: String, val shown: Int)

private val SPACE = Regex("\\s+")
private const val CAPTION_WORDS = 5
private const val CAPTION_CHARS = 32

/** Le passage contenant le mot courant, jamais le début tronqué d'une longue réponse. */
fun spokenCaption(text: String, position: SpeechPosition?, live: Boolean = false): SpokenCaption? {
    val words = text.trim().split(SPACE).filter(String::isNotBlank)
    if (words.isEmpty() || (!live && position == null)) return null
    val shown = if (live) words.size else wordsSpoken(words.joinToString(" "), position!!.positionMs, position.durationMs)
    val current = (shown - 1).coerceIn(0, words.lastIndex)
    val group = captionGroups(words).first { current in it }
    return SpokenCaption(words.slice(group).joinToString(" "), (shown - group.first).coerceIn(0, group.count()))
}

/** Découpage par mots ET par longueur : deux lignes lisibles, même pour une longue réponse. */
private fun captionGroups(words: List<String>): List<IntRange> {
    val groups = mutableListOf<IntRange>()
    var start = 0
    var chars = 0
    words.forEachIndexed { index, word ->
        val next = chars + (if (index > start) 1 else 0) + word.length
        if (index > start && (index - start >= CAPTION_WORDS || next > CAPTION_CHARS)) {
            groups += start until index
            start = index
            chars = 0
        }
        chars += (if (index > start) 1 else 0) + word.length
    }
    groups += start..words.lastIndex
    return groups
}
