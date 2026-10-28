package dev.sam3vision.core.live

/**
 * Prompts du direct : concepts suivis par SAM 3.1, liste courte modifiable depuis l'UI.
 * [normalize] assainit avant l'envoi au backend (`/live` borne de toute façon à 12 objets).
 *
 * Navigation : aucune consigne ici, le post-traitement suffit. L'intention (« aller à X »)
 * est détectée en déterministe sur le texte dicté (NavigationIntentParser),
 * pas par un concept suivi ni par le LLM. Ces prompts sont des noms de concepts SAM 3.1
 * (2 max, 32 caractères max), pas des instructions : une phrase de navigation n'y aurait
 * aucun sens et volerait un slot de suivi.
 */
object LivePrompts {

    /**
     * Concepts par défaut : **2** (« person », « door »), les plus utiles au guidage
     * à l'intérieur, modifiables à chaud dans le direct (champ « Suivre »).
     *
     * Huit concepts avaient été envisagés : l'app déployée batche les prompts
     * (`/health` → `levers.batch_prompts = true`) et la mesure du 2026-10-26 sur
     * `/live` (5 tirs par point, même image, 4 objets max) donnait 2 concepts =
     * 118 ms médian, 8 concepts = 118 ms médian. Mais le tracker backend tronque à
     * `PROMPT_LIMIT = 2` : envoyer davantage afficherait des concepts jamais
     * détectés — d'où [MAX] = 2, décision du 26/10.
     */
    val default: List<String> = listOf("person", "door")

    /**
     * Nombre maximal de concepts : **2**, décision user du 26/10 (« 2 concepts maximum,
     * jamais plus »). Le backend tronque déjà à `PROMPT_LIMIT = 2` côté tracker ; le
     * client ne doit donc pas en envoyer davantage — sinon l'app affiche des concepts
     * qui ne sont jamais détectés.
     */
    const val MAX = 2

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
