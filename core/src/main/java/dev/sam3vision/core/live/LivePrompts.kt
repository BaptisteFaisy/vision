package dev.sam3vision.core.live

/**
 * Prompts du direct : concepts suivis par SAM 3.1, liste courte modifiable depuis l'UI.
 * [normalize] assainit avant l'envoi au backend (`/live` borne de toute façon à 12 objets).
 *
 * Navigation : aucune consigne ici, le post-traitement suffit. L'intention (« aller à X »)
 * est détectée en déterministe sur le texte dicté (NavigationIntentParser),
 * pas par un concept suivi ni par le LLM. Ces prompts sont des noms de concepts SAM 3.1
 * (6 max, 32 caractères max), pas des instructions : une phrase de navigation n'y aurait
 * aucun sens et volerait un slot de suivi.
 */
object LivePrompts {

    /**
     * Concepts par défaut : **6** — « person », « door » (guidage intérieur) plus
     * « bicycle », « car », « truck », « electric scooter » (rue), modifiables à
     * chaud dans le direct (champ « Suivre »).
     *
     * Le plafond était à 2 (décision du 26/10) parce que le tracker backend
     * tronquait à `PROMPT_LIMIT = 2`. Décision user du 28/10 : suivre aussi les
     * véhicules — [MAX] et `PROMPT_LIMIT` passent à 6 ensemble. Sur `/live` les
     * prompts sont batchés (un seul forward, mesure du 26/10 : 2 ou 8 concepts =
     * 118 ms médian) ; sur le tracker chaque concept coûte une inférence par
     * `/frame`, d'où un coût ×3 assumé sur ce chemin.
     */
    val default: List<String> = listOf("person", "door", "bicycle", "car", "truck", "electric scooter")

    /**
     * Nombre maximal de concepts : **6**, décision user du 28/10 (rue + intérieur).
     * Le backend tronque à `PROMPT_LIMIT = 6` côté tracker ; le client ne doit pas
     * en envoyer davantage — sinon l'app affiche des concepts jamais détectés.
     */
    const val MAX = 6

    /** Longueur maximale d'un prompt : au-delà, ce n'est plus un concept. */
    const val MAX_LENGTH = 32

    /**
     * Nettoie une liste de prompts : espaces retirés, vides et doublons éliminés,
     * prompts trop longs rejetés, liste bornée à [MAX]. Une liste qui devient vide
     * retombe sur [default].
     */
    fun normalize(prompts: List<String>): List<String> {
        val cleaned = prompts.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= MAX_LENGTH }
            .distinct()
            .take(MAX)
            .toList()
        return cleaned.ifEmpty { default }
    }

    /**
     * Champ « Suivre » saisi par l'utilisateur : découpé sur les virgules,
     * points-virgules et retours ligne, nettoyé des vides — vide = [default].
     * La session borne et normalise ensuite (voir [normalize]).
     */
    fun field(text: String): List<String> =
        text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { default }
}
