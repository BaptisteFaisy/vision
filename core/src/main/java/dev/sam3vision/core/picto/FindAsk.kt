package dev.sam3vision.core.picto

import dev.sam3vision.core.distance.KnownSizes

/** Ce que l'écoute « Quoi chercher ? » doit faire au prochain sondage du micro. */
enum class FindAskStep {
    /** Rien de décidé : l'écoute continue. */
    LISTEN,

    /** Parole finie (ou plafond atteint) : couper et transcrire. */
    SEND,

    /** Personne n'a parlé : jeter l'enregistrement, aucun appel réseau. */
    DISCARD,
}

/**
 * Politique de l'écoute **sans doigt** (balayage vers la droite, demande user
 * du 02/11) : iSee demande « Quoi chercher ? », ouvre le micro, et le referme
 * dès que la parole est finie. Personne ne relâche de doigt ici : c'est le
 * silence de l'aperçu (`PcmPreview.speaking`, 550 ms d'élagage) qui tient lieu
 * de relâchement. [MAX_LISTEN_MS] borne l'attente et [NO_SPEECH_MS] coupe une
 * écoute où rien n'est venu — jamais de transcription d'un silence.
 *
 * La décision est pure ([step]) : l'attente réelle (sondage, horloge) vit dans
 * l'appelant, la règle vit ici. Testée en JVM.
 */
object FindAskPolicy {

    /** Période de sondage de l'aperçu micro. */
    const val POLL_MS = 100L

    /** Écoute maximale après la question : au-delà, on envoie ce qu'on a. */
    const val MAX_LISTEN_MS = 8_000L

    /** Aucune parole après ce délai : l'écoute est jetée sans transcription. */
    const val NO_SPEECH_MS = 5_000L

    /** Étape à appliquer, sachant si de la parole a été vue et si elle dure encore. */
    fun step(heardSpeech: Boolean, speaking: Boolean, elapsedMs: Long): FindAskStep = when {
        // La parole a été vue puis s'est tue : c'est la fin de la réponse.
        heardSpeech && !speaking -> FindAskStep.SEND
        elapsedMs >= MAX_LISTEN_MS -> if (heardSpeech) FindAskStep.SEND else FindAskStep.DISCARD
        !heardSpeech && elapsedMs >= NO_SPEECH_MS -> FindAskStep.DISCARD
        else -> FindAskStep.LISTEN
    }
}

/**
 * Objet cherché dans une réponse parlée à « Quoi chercher ? » : « mes clés » ⇒
 * « cles », « the medicine box please » ⇒ « medicine box ».
 *
 * Les déterminants de tête et le « please » final tombent — la chasse
 * (`pictoMatchesHunt`) et le concept SAM ne les reconnaissent pas —, le reste
 * garde son ordre. Accents, casse et ponctuation sont neutralisés par
 * [KnownSizes.canonical] : c'est la forme sur laquelle travaillent la chasse
 * et le pont FR-EN (`FindIndoorTarget`, « cles » vise les détections « keys »).
 * `null` quand il ne reste rien (réponse vide ou faite de déterminants). Pur et
 * testé en JVM.
 */
object FindLabelFromSpeech {

    /** Déterminants de tête, dans les deux langues parlées par iSee. */
    private val LEADING = setOf(
        "my", "the", "a", "an",
        "mes", "mon", "ma", "le", "la", "les", "un", "une", "des", "du", "de", "l",
    )

    /** Politesses finales : « mes clés s'il te plaît ». */
    private val TRAILING = setOf("please", "svp", "stp")

    /** Objet cherché contenu dans [text], `null` si la réponse ne nomme rien. */
    fun of(text: String): String? {
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
        val rest = words.dropWhile { it in LEADING }.dropLastWhile { it in TRAILING }
        return rest.joinToString(" ").ifEmpty { null }
    }
}
