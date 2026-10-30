package dev.sam3vision.core.live

/**
 * Prompts du direct : concepts suivis par SAM 3.1, liste courte modifiable depuis l'UI.
 * [normalize] assainit avant l'envoi au backend (`max_objects` borne la réponse).
 *
 * Navigation : aucune consigne ici, le post-traitement suffit. L'intention (« aller à X »)
 * est détectée en déterministe sur le texte dicté (NavigationIntentParser),
 * pas par un concept suivi ni par le LLM. Ces prompts sont des noms de concepts SAM 3.1
 * (16 max, 32 caractères max), pas des instructions : une phrase de navigation n'y aurait
 * aucun sens et volerait un slot de suivi.
 */
object LivePrompts {

    /**
     * Concepts par défaut : **14** dangers extérieurs pour un piéton aveugle —
     * mobiles (« person », « car », « bicycle », « electric scooter ») puis
     * statiques (« pole », « sidewalk », « crosswalk », « wall », « window »,
     * « stairs », « door », « barrier », « fence », « gate »), modifiables à
     * chaud dans le direct (champ « Suivre »).
     *
     * Historique des plafonds : 2 (26/10, le tracker tronquait à
     * `PROMPT_LIMIT = 2`), 6 (28/10, rue + intérieur), 16 (28/10, ordre user :
     * tous les dangers extérieurs) — [MAX] et `PROMPT_LIMIT` montent ensemble.
     * Historique des défauts : 10 (28/10), 12 (29/10, ordre user : portes et
     * barrières reconnues en plus), 14 (30/10 : clôtures et portails reconnus
     * en plus). Sur `/live` les prompts sont batchés (un seul forward, mesure
     * du 26/10 : 2 ou 8 concepts = 118 ms médian) ; sur le tracker chaque
     * concept coûte une inférence par `/frame`, d'où un coût ×8 assumé sur ce
     * chemin.
     */
    val default: List<String> = listOf(
        "person", "car", "bicycle", "electric scooter", "pole",
        "sidewalk", "crosswalk", "wall", "window", "stairs",
        "door", "barrier", "fence", "gate",
    )

    /**
     * Preset extérieur : **12** concepts de cheminement urbain — la personne et
     * le mobilier qui borde le trottoir (poteaux, bornes, clôtures, barrières,
     * portails, panneaux, arbres), plus le trottoir lui-même, les encombrants
     * (poubelles), les animaux et le feu tricolore — ordre user du 29/10
     * (30/10 : « sidewalk » remplace « dumpster », benne la moins utile),
     * détecté en permanence hors mode traversée pour anticiper les passages
     * protégés. Bascule depuis la pastille « Extérieur » du direct, sans passer
     * par le champ « Suivre ». Tient sous [MAX] comme [default], qui reste les
     * 14 dangers (pastille « Rue »).
     */
    val outdoor: List<String> = listOf(
        "person", "pole", "bollard", "fence", "barrier", "gate", "sign", "tree",
        "trash can", "sidewalk", "animal", "traffic light",
    )

    /**
     * Concepts d'intérieur : **7** objets d'appartement pour la sonde iSee en
     * mode maison — la personne et ce qui structure une pièce (portes,
     * escaliers, murs, fenêtres) plus le mobilier courant (chaises, tables).
     * Bien sous [MAX] ; les pastilles du mode Maison ([HomeModes]) gardent
     * leurs petits objets (clés, médicaments…), ici c'est le décor qui est
     * suivi, pas la chasse au mot parlé.
     */
    val indoor: List<String> = listOf(
        "person", "door", "chair", "table", "stairs", "wall", "window",
    )

    /**
     * Liste du monde sensoriel : dehors = [default] (les 14 dangers de la
     * rue, clôtures et portails inclus), dedans = [indoor] (les 7 objets
     * d'intérieur). V1 : seuls les prompts basculent, la portée reste 4 m
     * dans les deux mondes. À ne pas confondre avec le preset [outdoor]
     * (12 concepts de cheminement, pastille « Extérieur » du direct) : la
     * bascule monde s'applique à la sonde iSee, pas aux pastilles.
     */
    fun forMode(outdoor: Boolean): List<String> = if (outdoor) default else indoor

    /**
     * Nombre maximal de concepts : **16**, décision user du 28/10 (dangers
     * extérieurs + marge pour le champ « Suivre »). Le backend tronque à
     * `PROMPT_LIMIT = 16` côté tracker ; le client ne doit pas en envoyer
     * davantage — sinon l'app affiche des concepts jamais détectés.
     */
    const val MAX = 16

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
