package dev.sam3vision.app

import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.core.net.LIVE_TIMEOUT_SECONDS
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WireMarks
import dev.sam3vision.core.net.liveClient

/**
 * Réglages du **transport des masques** du direct, isolés ici : c'est le seul endroit à
 * changer pour revenir au corps historique du contrat (`MASK_FORMAT = null`, PNG pleine
 * résolution demandé par `mask_encoding` seul).
 *
 * Le gain vient de la **grille** ([MASK_EDGE]), pas du format : un masque de 512 × 206 en RLE
 * 256 px pèse quelques centaines d'octets contre plusieurs milliers en PNG pleine résolution,
 * et le client décode désormais les deux formats compacts.
 */
object LiveMaskTransport {

    /**
     * Format demandé au serveur (`mask_format`) : `rle`, **sans perte** (runs LEB128) et
     * relu sans décodeur d'image. `poly` est réservé aux masques non bruités et rend des
     * anneaux, donc une perte de forme à la tolérance près : `rle` reste le défaut.
     */
    const val MASK_FORMAT = "rle"

    /**
     * Côté long de la grille réduite (`mask_edge`) : 256 px, la valeur du contrat. Le serveur
     * l'annonce dans `mask_scale` (ratio grille/frame), sans quoi le client agrandirait au
     * mauvais facteur — la grille 256 d'une frame de 512 vaut 0,5. Une grille 384
     * aiderait les verticaux fins (poteaux) mais coûte × 2,25 cellules : passage
     * conditionné à la mesure RLE sur corpus (octets/latence), voir unresolved.
     */
    const val MASK_EDGE = 256

    /**
     * Fabrique unique du client `/live` **avec** les knobs ci-dessus : les deux écrans
     * qui parlent à SAM (Direct, mode vidéo iSee) passent par là, donc un format demandé
     * est toujours un format que le client sait relire. [withDepth] demande en plus la
     * médiane de proximité par objet (`false` = corps historique, octet pour octet).
     * [masks] à `false` ne demande que boîtes et scores (`mask_encoding: none`) : pour
     * les usages qui ne dessinent jamais de masque (pastilles, guidage) — aucun des
     * deux écrans actuels, qui affichent tous deux les masques (`LiveScreen`,
     * `IseeVisionCard`, `GlassesMaskLayer`), donc opt-in explicite.
     * [binaryWs] force le transport WS (`false` = JSON texte, jambe base64 du A/B
     * `docs/LATENCE-M1-HARNESS.md`) ; par défaut le build décide (`live.ws.text`) et
     * le binaire M1 reste le transport (même défaut que la fabrique).
     */
    fun clientFor(
        base: String,
        timeout: Long = LIVE_TIMEOUT_SECONDS,
        marks: WireMarks? = null,
        withDepth: Boolean = false,
        masks: Boolean = true,
        binaryWs: Boolean = !buildFlagOn(BuildConfig.WS_TEXT, false),
    ): LiveClient = liveClient(
        base,
        timeout,
        marks,
        MASK_FORMAT,
        MASK_EDGE,
        withDepth,
        masks,
        binaryWs,
        onBinaryChange = LiveLatencyLog::leg,
    )
}