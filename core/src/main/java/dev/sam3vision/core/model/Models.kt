package dev.sam3vision.core.model

/** Un objet segmenté par SAM : label, score de confiance, boîte [x1,y1,x2,y2], masque PNG gris en base64. */
data class SegObject(
    val label: String,
    val score: Float,
    val box: List<Float>,
    val maskPngB64: String,
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
 * Un encodage inconnu ou absent tombe sur `"png"`.
 */
const val MASK_ENCODING_BITS = "bits"
const val MASK_ENCODING_PNG = "png"
const val MASK_ENCODING_NONE = "none"

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
