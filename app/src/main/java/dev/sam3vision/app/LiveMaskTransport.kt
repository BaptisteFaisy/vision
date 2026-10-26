package dev.sam3vision.app

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
     * mauvais facteur — la grille 256 d'une frame de 512 vaut 0,5.
     */
    const val MASK_EDGE = 256

    /**
     * Fabrique unique du client `/live` **avec** les knobs ci-dessus : les deux écrans
     * qui parlent à SAM (Direct, mode vidéo iSee) passent par là, donc un format demandé
     * est toujours un format que le client sait relire.
     */
    fun clientFor(base: String, timeout: Long = LIVE_TIMEOUT_SECONDS, marks: WireMarks? = null): LiveClient =
        liveClient(base, timeout, marks, MASK_FORMAT, MASK_EDGE)
}