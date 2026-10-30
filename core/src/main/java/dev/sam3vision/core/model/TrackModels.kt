package dev.sam3vision.core.model

/**
 * Un objet suivi par le tracker vidéo (`sam3-track`) : son [id] est **stable**
 * d'une frame à l'autre tant que la session vit — c'est ce qui permet de dire
 * « la même personne » plutôt que « une personne ».
 *
 * Le masque voyage au format annoncé par [maskEncoding] (`mask_b64` + `mask_scale` /
 * `mask_poly`), comme sur `/live` — `rle`/256 compris, décodé par le même codec.
 */
data class TrackedObject(
    val id: Int,
    val label: String,
    val score: Float,
    val box: List<Float>,
    val maskPngB64: String,
    /**
     * Représentation du masque de **cet objet** : l'encodage de l'objet prime, sinon celui
     * de la frame. Défaut `"png"` — un backend qui n'envoie pas ce champ garde le décodage
     * d'avant, exactement comme [SegObject].
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

/** Une frame du tracker : objets identifiés, à la taille annoncée par la session. */
data class TrackFrame(
    val width: Int,
    val height: Int,
    val objects: List<TrackedObject>,
)

/** Réponse de `POST /session` : identifiant de session + objets de la frame initiale. */
data class TrackSessionStart(
    val sessionId: String,
    val width: Int,
    val height: Int,
    val objects: List<TrackedObject>,
)

/** État du tracker : `GET /health`. */
data class TrackHealth(
    val ok: Boolean,
    val model: String,
    val gpu: String,
    val maxObjects: Int,
)
