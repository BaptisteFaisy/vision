package dev.sam3vision.core.nav

/**
 * Destination dictée ou saisie, avant géocodage. [normalize] est pure (JVM seule,
 * sans Android ni coroutine) : espaces rognés et réduits, ponctuation orpheline
 * des bords retirée, chaîne vide ou blanche refusée par [Result].
 *
 * La ponctuation **intérieure** est conservée : elle porte le sens des noms de
 * lieux (« Saint-Germain-en-Laye », « l'Haÿ-les-Roses », « 12, rue de la Paix »).
 *
 * Un nom de lieu connu est remplacé par son adresse ([KnownPlaces.resolve]) :
 * « the Sacré Coeur » part vers « 35 Rue du Chevalier de la Barre », jamais
 * vers un homonyme lointain du géocodeur.
 */
object DestinationQuery {

    /** Ponctuation parasite : uniquement rognée aux bords, jamais à l'intérieur. */
    private const val EDGE_PUNCT = ".,;:!?\"'«»()[]{}<>…–—/\\|@#$%*~`^+=-"

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Normalise [raw] : trim, blancs internes réduits à une espace, ponctuation
     * des bords retirée, puis résolution du lieu connu ([KnownPlaces]). Échec
     * [DestinationError.BlankQuery] si rien n'est dit (vide, blanc, ou que de
     * la ponctuation). Ne lève jamais.
     */
    fun normalize(raw: String): Result<String> {
        if (raw.isBlank()) return Result.failure(DestinationError.BlankQuery())
        val collapsed = WHITESPACE_RUN.replace(raw.trim(), " ")
        val cleaned = collapsed.trim(::isEdgePunct).trim()
        if (cleaned.isBlank()) return Result.failure(DestinationError.BlankQuery())
        return Result.success(KnownPlaces.resolve(cleaned))
    }

    private fun isEdgePunct(c: Char): Boolean = c in EDGE_PUNCT
}

/**
 * Erreurs de normalisation, message directement affichable dans l'UI.
 * Jamais levées : renvoyées par [Result.failure].
 */
sealed class DestinationError(message: String) : Exception(message) {

    /** Rien à géocoder : vide, blanc, ou ponctuation seule. */
    class BlankQuery :
        DestinationError("empty destination: dictate or type an address")
}
