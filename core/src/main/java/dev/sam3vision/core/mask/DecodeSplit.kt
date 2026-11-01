package dev.sam3vision.core.mask

/**
 * Décomposition du décodage des masques d'une frame, en millisecondes (`-1` = non mesuré,
 * jamais un zéro inventé). Les quatre postes couvrent le trajet **base64 → tampons** :
 * `b64` (base64 → octets, [base64Bytes]), `img` (octets → niveaux : PNG, zlib `M1`, runs
 * RLE ou remplissage `poly`), `up` (grille réduite → taille de la frame, [upscaleToFrame]),
 * `paint` (coloriage par table + écriture dans le bitmap ping-pong, `MaskPainter`).
 *
 * C'est la décomposition du poste `mask` (`receivedToOverlaysMs`) : `b64 + img + up + paint`
 * le retrouve à ~1 ms près, comme `capsplit` retrouve `capt` (`TimingsText.capsplit`).
 * `total` est le temps mural mesuré par l'appelant (réponse → tampons prêts), `masks` le
 * nombre de masques décodés avec succès — un masque illisible est sauté, pas compté.
 */
data class DecodeSplit(
    val b64Ms: Long = -1,
    val imgMs: Long = -1,
    val upMs: Long = -1,
    val paintMs: Long = -1,
    val totalMs: Long = -1,
    val masks: Int = 0,
) {
    /** Au moins un poste mesuré : il y a une décomposition à afficher. */
    val known: Boolean
        get() = b64Ms >= 0L || imgMs >= 0L || upMs >= 0L || paintMs >= 0L || totalMs >= 0L

    /** Même décomposition, temps mural renseigné par l'appelant. */
    fun withTotal(totalMs: Long): DecodeSplit = copy(totalMs = totalMs)

    companion object {
        /** Aucune mesure : la sonde était inactive (`null` sur tout le chemin). */
        val EMPTY = DecodeSplit()
    }
}

/** Poste mesuré par la sonde de décodage : un seul accumulateur par poste. */
enum class DecodeStage { B64, IMG, UP, PAINT }

/**
 * Sonde de décodage d'une frame : accumule les nanosecondes par poste, tous masques
 * confondus, puis rend un [DecodeSplit] en millisecondes. **Une sonde par frame** : deux
 * frames peuvent se décoder en concurrence (double vol), un accumulateur partagé
 * mélangerait leurs chiffres.
 *
 * Coût nul quand inactive : le chemin de décodage prend `probe: DecodeProbe? = null`, et
 * [timed] n'est qu'un appel direct au bloc quand la sonde est `null` — ni lecture
 * d'horloge, ni allocation. L'appelant actif passe une sonde neuve par frame (un seul
 * petit objet) et la jette après [snapshot].
 */
class DecodeProbe(private val clock: () -> Long = System::nanoTime) {

    private val acc = LongArray(DecodeStage.entries.size)

    /** Lecture d'horloge (nanosecondes monotones) : les deux bornes d'un [timed]. */
    fun now(): Long = clock()

    /** Ajoute [ns] au poste : l'autre moitié de [timed], exposée pour les tests. */
    fun add(stage: DecodeStage, ns: Long) {
        acc[stage.ordinal] += ns
    }

    /** Nanosecondes accumulées d'un poste depuis [reset]. */
    fun nanos(stage: DecodeStage): Long = acc[stage.ordinal]

    /** Remet les quatre accumulateurs à zéro : nouvelle frame. */
    fun reset() {
        acc.fill(0L)
    }

    /** Instantané en millisecondes arrondies ; [totalMs] vient de l'horloge de l'appelant. */
    fun snapshot(totalMs: Long, masks: Int): DecodeSplit =
        DecodeSplit(
            b64Ms = toMs(acc[DecodeStage.B64.ordinal]),
            imgMs = toMs(acc[DecodeStage.IMG.ordinal]),
            upMs = toMs(acc[DecodeStage.UP.ordinal]),
            paintMs = toMs(acc[DecodeStage.PAINT.ordinal]),
            totalMs = totalMs,
            masks = masks,
        )

    /** Millisecondes arrondies ; un poste jamais sollicité reste à `0`, pas `-1`. */
    private fun toMs(ns: Long): Long = (ns + NS_PER_MS / 2) / NS_PER_MS

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}

/**
 * Exécute [block] en imputant sa durée à [stage] — ou l'exécute nu si la sonde est `null`
 * (chemin inactif : un seul test de nullité, aucune lecture d'horloge). `inline` : aucun
 * objet lambda alloué sur le chemin nominal.
 */
inline fun <T> DecodeProbe?.timed(stage: DecodeStage, block: () -> T): T {
    if (this == null) return block()
    val start = now()
    try {
        return block()
    } finally {
        add(stage, now() - start)
    }
}
