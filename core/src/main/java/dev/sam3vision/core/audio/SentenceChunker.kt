package dev.sam3vision.core.audio

/**
 * Découpe un texte en morceaux d'au plus [MAX_CHARS] caractères, en coupant aux
 * frontières de phrases : ElevenLabs refuse au-delà de 5 000 caractères par appel
 * et une phrase coupée en deux se lit mal. Une phrase démesurée est tout de même
 * éclatée aux espaces pour ne jamais bloquer la lecture.
 */
object SentenceChunker {
    const val MAX_CHARS = 1_200

    /**
     * Une phrase finit par `.` `!` `?` `…` ou un guillemet fermant, suivi d'une
     * espace. Le groupe capturant garde la ponctuation dans le morceau.
     */
    private val BOUNDARY = Regex("([.!?…\"]\\s+)")

    fun chunk(text: String, maxChars: Int = MAX_CHARS): List<String> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()
        if (clean.length <= maxChars) return listOf(clean)
        return pack(splitSentences(clean, maxChars), maxChars)
    }

    /** Phrases complètes, puis éclatement aux espaces de celles trop longues. */
    private fun splitSentences(text: String, maxChars: Int): List<String> =
        SENTENCE.split(text)
            .flatMap { piece ->
                val trimmed = piece.trim()
                when {
                    trimmed.length > maxChars -> hardSplit(trimmed, maxChars)
                    else -> listOf(trimmed)
                }
            }
            .filter { it.isNotBlank() }

    /** Coupe à blanc le plus près de [maxChars] sans casser un mot. */
    internal fun hardSplit(text: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > maxChars) {
            var cut = rest.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    /** Recolle les phrases courtes jusqu'à [maxChars] sans dépasser. */
    private fun pack(sentences: List<String>, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        sentences.forEach { sentence ->
            val extra = if (current.isEmpty()) sentence.length else 1 + sentence.length
            if (current.isNotEmpty() && current.length + extra > maxChars) {
                out += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.filter { it.isNotBlank() }
    }

    /** Garde le séparateur : « a.b » n'est pas une frontière, « a. b » si. */
    private val SENTENCE = Regex("(?<=[.!?…\"])(?=\\s+)")
}
