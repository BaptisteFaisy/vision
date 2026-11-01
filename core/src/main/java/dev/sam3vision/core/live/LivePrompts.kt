package dev.sam3vision.core.live

/**
 * Prompts du direct : concepts suivis par SAM 3.1, liste courte modifiable depuis l'UI.
 * [normalize] assainit avant l'envoi au backend (`max_objects` borne la réponse).
 *
 * Navigation : aucune consigne ici, le post-traitement suffit. L'intention (« aller à X »)
 * est détectée en déterministe sur le texte dicté (NavigationIntentParser),
 * pas par un concept suivi ni par le LLM. Ces prompts sont des noms de concepts SAM 3.1
 * (18 max, 32 caractères max), pas des instructions : une phrase de navigation n'y aurait
 * aucun sens et volerait un slot de suivi.
 */
object LivePrompts {

    /**
     * Concepts par défaut : **18** concepts extérieurs — mobiles (« person »,
     * « car », « bus », « bicycle ») puis feux piétons et cheminement
     * (« pedestrian traffic light », « pedestrian signal head », « curb »,
     * « sidewalk », « crosswalk ») puis statiques (« pole », « stairs »,
     * « fence », « gate », « wall », ordre user du 31/10 : iSee détecte
     * les murs), puis les clés et les souvenirs du 31/10 (« keys »,
     * « souvenir shop », « beanie », « socks », boutique du square).
     *
     * **Ordre user du 01/11 : les souvenirs et les clés restent.** L'audit
     * foule les avait sortis au profit de « stroller », « child »,
     * « electric scooter » et « dog » ; Baptiste a demandé de les remettre.
     * La foule n'est donc plus suivie par le **défaut** mais par [outdoor],
     * le preset de marche urbaine (pastille « Extérieur »), qui porte les
     * trois concepts de corps qui manquaient. Modifiables à chaud dans le
     * direct (champ « Follow »), slot « curb » réservé.
     *
     * Liste **unique** normal/Traversée (ordre user du 30/10 : mêmes masques
     * dans les deux modes) : tout ce que la Traversée doit voir (voiture,
     * bus, vélo, feu, bordure, trottoir, passage, personnes) est déjà suivi
     * ici, la bascule ne change plus les prompts — sauf la traversée active,
     * qui ajoute camion et moto (reel audit 31/10, voir `VehicleConcepts`).
     * « door » et « window » ont cédé leurs slots aux véhicules, au feu et à
     * la bordure ; « barrier » est sorti le 31/10 (le preset extérieur le
     * reconnaît toujours) ; « electric scooter » est revenu le 01/11 pour la
     * foule, sans changer le veto de la Traversée (qui le classe véhicule) ;
     * « wall » y est entré le 31/10 (plafond 17 → 18, rien ne sort).
     *
     * Historique des plafonds : 2 (26/10, le tracker tronquait à
     * `PROMPT_LIMIT = 2`), 6 (28/10, rue + intérieur), 16 (28/10, ordre user :
     * tous les dangers extérieurs), 17 (31/10, ordre user : tête du feu
     * piéton en plus du caisson), 18 (31/10, ordre user : iSee détecte
     * les murs) — [MAX] et `PROMPT_LIMIT` montent ensemble.
     * Historique des défauts : 10 (28/10), 12 (29/10, ordre user : portes et
     * barrières reconnues en plus), 14 (30/10 : clôtures et portails reconnus
     * en plus), 16 (30/10 : unification traversée/normal ; 31/10 : camion,
     * moto et trottinette cèdent leurs slots aux souvenirs), 17 (31/10 :
     * tête du feu piéton en plus du caisson, « barrier » à « keys »),
     * 18 (31/10 : « wall » en plus, rien ne sort), puis 01/11 à plafond
     * constant : les trois souvenirs et « keys » sortent, la foule entre.
     * Sur `/live` les prompts sont batchés (un seul forward, mesure du 26/10 :
     * 2 ou 8 concepts = 118 ms médian) ; sur le tracker chaque concept coûte
     * une inférence par `/frame`, d'où un coût ×8 assumé sur ce chemin.
     */
    val default: List<String> = listOf(
        "person", "car", "bus", "bicycle", "pedestrian traffic light",
        "pedestrian signal head", "curb", "sidewalk", "crosswalk", "pole",
        "stairs", "keys", "fence", "gate", "souvenir shop", "beanie", "socks",
        "wall",
    )

    /**
     * Preset extérieur : **15** concepts de cheminement urbain — la personne et
     * le mobilier qui borde le trottoir (poteaux, lampadaires, bornes, clôtures,
     * barrières, portails, panneaux, arbres), plus le trottoir lui-même et le
     * passage piéton, les encombrants (poubelles), les animaux et le feu piéton
     * — ordre user du 29/10 (31/10 : piétons seuls, plus de feux voiture, tête
     * du feu suivie) (30/10 : « sidewalk » remplace « dumpster ») (reel audit
     * 31/10 : « crosswalk » et « street lamp » en plus, « bollard » et « sign »
     * déjà suivis, non dupliqués), détecté en permanence hors mode traversée
     * pour anticiper les passages protégés. Bascule depuis la pastille
     * « Extérieur » du direct, sans passer par le champ « Follow ». Les trois
     * concepts de foule ajoutés le 01/11 (« stroller », « child »,
     * « electric scooter ») y vivent : la marche urbaine voit la poussette et
     * l'enfant, le défaut garde la boutique. 18 concepts, au plafond, comme
     * [default] (pastille « Rue »).
     */
    val outdoor: List<String> = listOf(
        "person", "pole", "street lamp", "bollard", "fence", "barrier", "gate",
        "sign", "tree", "trash can", "sidewalk", "crosswalk", "animal",
        "pedestrian traffic light", "pedestrian signal head",
        "stroller", "child", "electric scooter",
    )

    /**
     * Concepts d'intérieur : **9** objets d'appartement pour la sonde iSee en
     * mode maison — la personne et ce qui structure une pièce (portes,
     * escaliers, murs, fenêtres) plus le mobilier courant (chaises, tables),
     * le passage piéton (reel audit 31/10) et la poussette (audit foule du
     * 01/11 : la personne qui se déplace chez elle avec une poussette ou un
     * déambulateur — c'est un corps qui bloque, pas du décor ; le déambulateur
     * n'a pas de concept propre, la poussette est le plus proche voisin).
     * Bien sous [MAX] ; les pastilles du mode Maison ([HomeModes]) gardent
     * leurs petits objets (clés, médicaments…) : ici le décor est suivi, pas
     * le mot parlé.
     */
    val indoor: List<String> = listOf(
        "person", "stroller", "door", "chair", "table", "stairs", "wall", "window",
        "crosswalk",
    )

    /**
     * Liste du monde sensoriel : dehors = [default] (les 18 concepts
     * extérieurs, foule incluse), dedans = [indoor] (les 9 objets
     * d'intérieur). V1 : seuls les prompts basculent, la portée reste 4 m
     * dans les deux mondes. À ne pas confondre avec le preset [outdoor]
     * (13 concepts de cheminement, pastille « Extérieur » du direct) : la
     * bascule monde s'applique à la sonde iSee, pas aux pastilles.
     */
    fun forMode(outdoor: Boolean): List<String> = if (outdoor) default else indoor

    /**
     * Concepts suivis explicitement : [effective] moins la liste du monde
     * (comparaison insensible à la casse) — les pastilles des suivis seules
     * s'affichent, vide = tout s'affiche. Pur, testé en JVM.
     */
    fun followedOf(effective: List<String>, outdoorMode: Boolean): List<String> {
        val base = forMode(outdoorMode).map { it.trim().lowercase() }.toSet()
        return normalize(effective).filter { it.trim().lowercase() !in base }
    }

    /**
     * Nombre maximal de concepts : **18** (17 du 31/10 plus les murs,
     * ordre user du 31/10 : iSee détecte les murs). Le backend suit :
     * `MAX_PROMPTS = 18` sur `/live`, `PROMPT_LIMIT = 18` côté tracker ;
     * le client ne doit pas en envoyer davantage — sinon l'app affiche
     * des concepts jamais détectés.
     */
    const val MAX = 18

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
     * Ordre « Find the X » : [label] rejoint [prompts], même au plafond —
     * le dernier concept cède alors son slot à l'ordre explicite (les
     * concepts de tête, piétons et traversée, ne bougent jamais). Déjà
     * présent (comparaison insensible à la casse), vide ou trop long :
     * liste inchangée. Pur, testé en JVM.
     */
    fun withConcept(prompts: List<String>, label: String): List<String> {
        val clean = label.trim()
        if (clean.isEmpty() || clean.length > MAX_LENGTH) return prompts
        if (prompts.any { it.trim().equals(clean, ignoreCase = true) }) return prompts
        val base = if (prompts.size >= MAX) prompts.dropLast(1) else prompts
        return normalize(base + clean)
    }

    /**
     * Champ « Follow » saisi par l'utilisateur : découpé sur les virgules,
     * points-virgules et retours ligne, nettoyé des vides — vide = [default].
     * La session borne et normalise ensuite (voir [normalize]).
     */
    fun field(text: String): List<String> =
        text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { default }
}
