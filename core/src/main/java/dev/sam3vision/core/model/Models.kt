package dev.sam3vision.core.model

/**
 * Un objet segmenté par SAM : label, score de confiance, boîte `[x1,y1,x2,y2]` et masque
 * **au format annoncé par [maskEncoding]** — `mask_b64` porte les octets (`png`/`bits`/`rle`)
 * ou rien du tout pour `poly`, dont les anneaux sont dans [maskPoly].
 */
data class SegObject(
    val label: String,
    val score: Float,
    val box: List<Float>,
    val maskPngB64: String,
    /**
     * Représentation du masque de **cet objet** : l'encodage de l'objet prime, sinon celui
     * de la frame (`LiveFrame.maskEncoding`). Défaut `"png"` — un backend qui n'envoie que
     * le champ de frame, ou pas de champ du tout, garde le décodage d'avant.
     */
    val maskEncoding: String = MASK_ENCODING_PNG,
    /**
     * Ratio **grille du masque / frame** (`mask_scale`) : `0,5` pour une grille réduite de
     * moitié (256 px de côté pour une frame de 512). Défaut `1` : masque à la taille de la
     * frame, donc aucun agrandissement.
     */
    val maskScale: Float = 1f,
    /**
     * Anneaux du format `poly` (`mask_poly`), plats : `x0, y0, x1, y1, …`, refermés
     * implicitement. Vide hors `poly`.
     */
    val maskPoly: List<List<Int>> = emptyList(),
)

/** Résultat brut d'un appel /segment. */
data class SegmentResult(
    val width: Int,
    val height: Int,
    val objects: List<SegObject>,
)

/** Analyse finale : description libre + tags + objets segmentés (vide si segmentation désactivée). */
data class Analysis(
    val description: String,
    val tags: List<String>,
    val objects: List<SegObject>,
)

/** Un tour de conversation sur la photo courante. */
data class ChatTurn(val role: Role, val content: String)

enum class Role { USER, ASSISTANT }

/**
 * Encodages de masque que le backend sait servir (`segmentation.MASK_ENCODINGS`) :
 * `"bits"` = 1 bit/pixel + zlib (format `M1`, le plus léger), `"png"` = gris 8 bits
 * (contrat historique), `"none"` = pas de masque (le client dessine la boîte).
 * Le codec masques y ajoute les formats compacts : `"rle"` (runs LEB128 sans perte) et
 * `"poly"` (anneaux entiers, remplissage even-odd).
 * Un encodage inconnu ou absent tombe sur `"png"`.
 */
const val MASK_ENCODING_BITS = "bits"
const val MASK_ENCODING_PNG = "png"
const val MASK_ENCODING_NONE = "none"
const val MASK_ENCODING_RLE = "rle"
const val MASK_ENCODING_POLY = "poly"

/** Résultat brut d'un appel /live : une frame du direct, avec sa durée serveur. */
data class LiveFrame(
    val width: Int,
    val height: Int,
    val elapsedMs: Long,
    val objects: List<SegObject>,
    /**
     * Représentation des masques de cette réponse (`"bits"`, `"png"`, `"none"`) : le client
     * décode en conséquence. Défaut `"png"` — le backend déployé, qui ne renvoie pas encore
     * ce champ, garde exactement le comportement d'avant.
     */
    val maskEncoding: String = MASK_ENCODING_PNG,
    /**
     * Postes serveur en millisecondes (`decode`, `queue`, `preprocess`, `vision`, `detr`,
     * `masks`, `total`) quand le backend est instrumenté (`FAST_TIMING=1`) ; **vide** sinon,
     * ce qui laisse `elapsedMs` seul. Le contrat `elapsed_ms` ne change pas.
     */
    val timingsMs: Map<String, Double> = emptyMap(),
)

/** État du backend live : `GET /health`. */
data class LiveHealth(
    val ok: Boolean,
    val model: String,
    val device: String,
    val gpu: String,
)
