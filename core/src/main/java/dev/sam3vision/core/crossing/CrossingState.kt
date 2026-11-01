package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.hazard.HazardConcepts
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject

/**
 * État d'un feu piéton vu par la caméra : [Red] (« attendez ») ou [Green]
 * (« traversez », annoncé seulement après confirmation, voir
 * [CrossingAnnouncer]). Binaire (ordre user du 31/10, fini le doute) : pas
 * de feu détecté, boîte invalide ou couleur indécise valent [Red] — seul
 * un vert confirmé autorise la traversée, tout le reste attend.
 */
enum class CrossingState { Red, Green }

/**
 * Concepts **minimaux** de la Traversée : le feu (en trois variantes), la
 * tête du feu, les véhicules (voiture, camion, moto — reel audit 31/10),
 * les personnes et le passage lui-même. Neuf concepts : le feu dit quand
 * traverser, les personnes sont annoncées par positions, et le passage vu
 * garde le mode auto actif (perdu plusieurs frames, retour au mode normal).
 *
 * Couverture « mêmes masques » : tout est dans [LivePrompts.default] sauf
 * « truck » et « motorcycle », envoyés en plus en traversée active — le
 * mode actif envoie aussi [LIGHTS] (câblage `:app`), sans nouveau concept
 * visuel à couvrir (mêmes masques que [LIGHT]).
 */
object CrossingPrompts {

    /**
     * Concept feu principal : le feu **piéton** seul
     * (`docs/MESURES-SAM3-FEUX.md` §2 : rappel 87,5 % sur 16 photos réelles,
     * contre 43,8 % pour `pedestrian signal` et 12,5 % pour un prompt
     * d'état). Les feux voiture ne sont jamais suivis (ordre user du
     * 31/10) : un vert voiture ne doit jamais devenir un ordre de
     * traverser — 31/10, cette exclusion est la seule garde (la porte
     * forme admet les disques, détection au plus large).
     * Variantes d'union : [LIGHT_SHORT], [LIGHT_SIGNAL].
     */
    const val LIGHT = "pedestrian traffic light"

    /**
     * Variantes courtes du feu piéton, envoyées avec [LIGHT] en mode
     * traversée actif : l'union des trois wordings ne peut qu'ajouter des
     * détections du même caisson (mêmes masques). Jamais de wording voiture
     * (`traffic light`, `traffic signal`, `stoplight`) : un vert voiture ne
     * doit jamais devenir un ordre de traverser (A2 bloquant).
     */
    const val LIGHT_SHORT = "pedestrian light"
    const val LIGHT_SIGNAL = "pedestrian signal"

    /**
     * Concept tête : la **petite boîte** du feu piéton (31/10, ordre user :
     * SAM cadre la tête, la couleur s'y lit en local). [LIGHT] trouve le
     * caisson (rappel 87,5 %) ; [HEAD] resserre sur la tête, et c'est sa
     * boîte qui part au classifieur entraîné ([selectLightBox] la préfère).
     * Un prompt d'objet, pas d'état : la couleur reste lue en local
     * (`TrainedLightColor` + `PedLightGate`), jamais devinée par SAM.
     */
    const val HEAD = "pedestrian signal head"

    /** Wordings feu envoyés en mode traversée actif, [LIGHT] d'abord. */
    val LIGHTS: List<String> = listOf(LIGHT, LIGHT_SHORT, LIGHT_SIGNAL)

    /** Concept personne, annoncée par positions en mode Traversée. */
    const val PERSON = "person"

    /** Concept passage, qui garde le mode auto actif tant qu'il est vu. */
    const val CROSSWALK = "crosswalk"

    /** Couverture documentaire du mode Traversée (l'envoi réel passe par TraversePipeline.promptsFor). */
    val PROMPTS: List<String> =
        LIGHTS + listOf(HEAD, "car", "truck", "motorcycle", PERSON, CROSSWALK)

    /**
     * Étiquettes (canoniques, voir [KnownSizes.canonical]) qui désignent un
     * feu **piéton** — et lui seul. `traffic light`, `stoplight`,
     * `traffic signal`, `feu` et `feu tricolore` sont des feux voiture (ou
     * ambigus) : exclus (ordre user du 31/10), même suivis à la main dans
     * le champ « Follow ». Pas de `pieton` seul : un passant étiqueté
     * « piéton » n'est pas un feu (voir [PersonLabels]). Les wordings
     * anglais sont [LIGHTS] (alignement structurel : tout wording envoyé
     * est reconnu).
     */
    private val LIGHT_LABELS = LIGHTS.toSet() + setOf(
        "feu pieton",
        "feux pieton",
        "feu pietons",
        "feux pietons",
        "feu pi",
        "signal pieton",
    )

    /**
     * Étiquettes (canoniques) qui désignent la **tête** du feu, le cadrage
     * serré que la couleur préfère : le concept envoyé ([HEAD] et son
     * pluriel) plus les alias français du serveur (`server/backend/
     * concepts.py`, « boîtier… » et « petit… »).
     */
    private val HEAD_LABELS = setOf(
        "pedestrian signal head",
        "pedestrian signal heads",
        "boitier feu pieton",
        "boitiers feu pieton",
        "petit feu pieton",
        "petits feux pietons",
    )

    /**
     * Vrai si [label] (brut, tel que renvoyé par SAM) désigne un feu piéton :
     * phrase connue ([LIGHT_LABELS], dont l'abrégé oral `feu pi`) ou composé
     * piéton + lampe ([isPedestrianLamp]) — « feu piéton rouge » saisi à la
     * main reste un feu. Casse, accents et espaces neutralisés par
     * [KnownSizes.canonical]. `passage pieton` et `pedestrian crossing` n'ont
     * pas de lampe : jamais des feux.
     */
    fun isLightLabel(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (LIGHT_LABELS.contains(key)) return true
        return isPedestrianLamp(key.split(' '))
    }

    /** Composé piéton + lampe : les deux mots exigés, dans n'importe quel ordre. */
    private fun isPedestrianLamp(words: List<String>): Boolean {
        if (!hasPedestrianWord(words)) return false
        return words.any { it == "feu" || it == "feux" || it == "signal" || it == "signaux" || it == "light" || it == "lights" }
    }

    /**
     * Vrai si [label] désigne la tête du feu : un feu ([isLightLabel]) au
     * cadrage serré — étiquette connue ([HEAD_LABELS]) ou composé
     * piéton + tête (« tête de feu piéton », « boîte de signal piéton »
     * saisis à la main). Une tête est toujours un feu : la préférence de
     * [selectLightBox] ne peut jamais router un non-feu au classifieur.
     */
    fun isHeadLabel(label: String): Boolean {
        if (!isLightLabel(label)) return false
        val key = KnownSizes.canonical(label)
        if (HEAD_LABELS.contains(key)) return true
        val words = key.split(' ')
        if (!hasPedestrianWord(words)) return false
        return words.any { it == "head" || it == "heads" || it == "boitier" || it == "boitiers" || it == "boite" || it == "boites" || it == "tete" }
    }

    /** Mot piéton (FR/EN, canonique) : partagé par lampe et tête. */
    private fun hasPedestrianWord(words: List<String>): Boolean =
        words.any { it == "pieton" || it == "pietons" || it == "pedestrian" || it == "pedestrians" }

    /**
     * Boîte normalisée (`x1,y1,x2,y2` en 0..1) du feu piéton dont la couleur
     * sera lue, `null` si aucun n'est détecté ou si la boîte est invalide.
     * Les boîtes **tête** ([isHeadLabel], cadrage serré du 31/10) passent
     * devant : la lampe y remplit le crop 48 px et le classifieur entraîné
     * retrouve son domaine (`docs/MESURES-SAM3-FEUX.md` §6). Sans tête, le
     * caisson ([LIGHT]) prend le relais au meilleur score. Les éclats
     * (boîte sous le dixième de la plus grande de sa classe — un reflet
     * de 384 px ne bat jamais un caisson, §5a) sont écartés avant le tri
     * au score, **par classe** : une petite tête ne se fait jamais écarter
     * comme un éclat du caisson. Avant tout : le veto tricolore
     * ([CarLightVeto]) écarte les boîtes inscrites dans un caisson voiture.
     * La normalisation suit [Detection.normalizedBox] : la boîte se reporte
     * sur le Bitmap décodé côté `:app`, quelle que soit sa taille.
     */
    fun selectLightBox(objects: List<SegObject>, width: Int, height: Int): List<Float>? =
        selectLightBoxes(objects, width, height).firstOrNull()

    /**
     * Boîtes candidates ordonnées (tête puis caisson, 0 à 2) : [selectLightBox]
     * n'en garde que la première, mais une tête peut cadrer la mauvaise
     * lentille (01/11, `nl_panda_10` : tête à 0,48 sur la lentille éteinte,
     * caisson à 0,89 sur le boîtier allumé) — l'appelant relit le caisson
     * quand la tête est sans lampe (voir `:app`, `crossingLightOf`).
     */
    fun selectLightBoxes(objects: List<SegObject>, width: Int, height: Int): List<List<Float>> {
        if (width <= 0 || height <= 0) return emptyList()
        val carBoxes = CarLightVeto.carBoxes(objects)
        val lights = objects.filter { isLightLabel(it.label) }
            .filterNot { CarLightVeto.isVetoed(it.box, carBoxes) }
        if (lights.isEmpty()) return emptyList()
        val (heads, housings) = lights.partition { isHeadLabel(it.label) }
        return listOfNotNull(bestBox(heads, width, height), bestBox(housings, width, height))
    }

    /**
     * Repli haut-de-poteau : sans boîte de feu, le carré ancré en haut du
     * meilleur poteau ([HazardConcepts.isPole], même tri au score et
     * anti-éclats que [bestBox]), `null` sans poteau ou sans carré valide.
     * Le feu piéton est sur poteau (ordre user du 31/10) : quand SAM rate
     * le boîtier mais voit le fût, la lampe se lit en haut du poteau. Le
     * carré ([poleTopSquare]) est vetoé comme un feu ([CarLightVeto]) :
     * un haut de poteau dans un caisson voiture ne part pas au classifieur.
     * L'appelant vérifie ensuite la lampe ([PedLightGate.hasLampSignal]) :
     * un poteau sans feu reste `null` côté boîte (branche sans-feu intacte).
     */
    fun selectPoleTopBox(objects: List<SegObject>, width: Int, height: Int): List<Float>? {
        if (width <= 0 || height <= 0) return null
        val poles = objects.filter { HazardConcepts.isPole(it.label) }
        val pick = bestPick(poles) ?: return null
        val square = poleTopSquare(pick.box, width, height)
        if (CarLightVeto.isVetoed(square, CarLightVeto.carBoxes(objects))) return null
        return Detection(pick.label, pick.score, square, width, height).normalizedBox()
            ?.takeIf { box -> box.size == 4 && box[2] > box[0] && box[3] > box[1] }
    }

    /**
     * Carré de lecture en haut du poteau [poleBox] (pixels) : côté trois
     * largeurs de fût ([POLE_TOP_WIDTH_FACTOR], plancher [POLE_TOP_MIN_PX]),
     * centré sur le fût, haut aligné sur le haut du poteau, clampé au cadre.
     * Serré comme la tête : la lampe (plus large que le fût) y chevauche la
     * médiane, ce que la garde bas de la porte exige (`PedLightGate`) — une
     * moitié haute de poteau serait rejetée (vert confiné en haut).
     */
    private fun poleTopSquare(poleBox: List<Float>, width: Int, height: Int): List<Float> {
        val x1 = poleBox[0]
        val y1 = poleBox[1]
        val side = maxOf((poleBox[2] - x1) * POLE_TOP_WIDTH_FACTOR, POLE_TOP_MIN_PX)
        val cx = (x1 + poleBox[2]) / 2f
        val left = (cx - side / 2f).coerceIn(0f, maxOf(0f, width - side))
        val top = y1.coerceIn(0f, maxOf(0f, height - side))
        return listOf(left, top, minOf(left + side, width.toFloat()), minOf(top + side, height.toFloat()))
    }

    /** Largeurs de fût du carré haut-de-poteau (la lampe déborde du fût). */
    private const val POLE_TOP_WIDTH_FACTOR = 3f

    /** Côté minimal du carré haut-de-poteau, en pixels (pas un point). */
    private const val POLE_TOP_MIN_PX = 24f

    /**
     * Meilleure boîte d'une classe (tête ou caisson) : éclats écartés sous
     * le dixième de la plus grande, tri au score, normalisée. `null` sans
     * candidat valide — l'appelant bascule sur l'autre classe.
     */
    private fun bestBox(objects: List<SegObject>, width: Int, height: Int): List<Float>? {
        return bestPick(objects)
            ?.let { Detection(it.label, it.score, it.box, width, height).normalizedBox() }
            ?.takeIf { box -> box.size == 4 && box[2] > box[0] && box[3] > box[1] }
    }

    /**
     * Meilleur objet d'une classe : éclats écartés sous le dixième de la
     * plus grande aire, tri au score. `null` sans candidat — partagé par
     * [bestBox] (feu) et [selectPoleTopBox] (poteau), mêmes règles.
     */
    private fun bestPick(objects: List<SegObject>): SegObject? {
        val areas = objects.map { boxArea(it.box) }
        val biggest = areas.maxOrNull() ?: return null
        if (biggest <= 0f) return null
        return objects.asSequence()
            .filterIndexed { index, _ -> areas[index] >= biggest / SLIVER_RATIO }
            .maxByOrNull { it.score }
    }

    /** Éclats ignorés sous le dixième de la plus grande boîte feu (§5a). */
    private const val SLIVER_RATIO = 10f

    /** Aire pixels d'une boîte SAM, `0` si illisible (jamais négative). */
    private fun boxArea(box: List<Float>): Float {
        if (box.size < 4) return 0f
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return 0f
        return w * h
    }
}
