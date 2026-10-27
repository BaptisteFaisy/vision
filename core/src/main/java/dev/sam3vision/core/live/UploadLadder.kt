package dev.sam3vision.core.live

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Un palier d'encodage : le **bord** (côté long, en px) et la **qualité** JPEG.
 *
 * Le bord est celui de l'image **envoyée** : le serveur ne fait que le *capper*
 * (`resize_max_edge` n'agrandit jamais), donc baisser le bord dégrade réellement l'image
 * donnée à l'encodeur vision — ce n'est pas qu'un gain de transport.
 */
data class EncodePlan(val edgePx: Int, val jpegQuality: Int) {

    /** Libellé court, celui de la ligne d'état (« 512 px q65 »). */
    val label: String get() = "$edgePx px q$jpegQuality"
}

/**
 * Poids montant : l'**échelle d'encodage** (8 paliers) et le modèle qui la chiffre (u23).
 *
 * Sur un lien lent, la frame coûte `octets × 8 / débit` : c'est le poste le plus lourd de
 * la part réseau. L'échelle part du comportement actuel (palier 0 : 512 px q65) et
 * descend au plancher (320 px q45), soit **28,1 → 10,5 Ko sur le fil (−63 %)** — de quoi
 * retirer ~143 ms à 1 Mbit/s, et **rien du tout** au-delà de 5 Mbit/s : c'est
 * [UploadRegulator] qui le constate et s'arrête.
 *
 * Modèle (deux paramètres mesurés, aucun chiffre inventé) :
 * ```
 * octets(pixels, q) = base(q) × (pixels / 105 472)^0,81      (105 472 px = 512 × 206)
 * octets_sur_le_fil = octets × 4/3 + 1 000                   (base64 + enveloppe JSON)
 * ms_remontée       = octets_sur_le_fil × 8 / débit_kbit_s
 * ```
 * `base(q)` est la table ImageIO du JDK (JPEG, `street.png` 2048×824) **recalée terrain**
 * (×0,88 : libjpeg-turbo produit ~12 % de moins que le JDK sur la même scène).
 * Vérifications : 512 px q65 → 20 802 octets (mesure terrain 20,7 Ko, +0,5 %) ;
 * 384 px q45 → 9 870 (table 9 944, −0,7 %).
 */
object UploadLadder {

    /** Bord du palier 0 : 512 px, le comportement actuel. */
    const val BASE_EDGE = 512

    /** Qualité du palier 0 : q65, la qualité actuelle. */
    const val BASE_QUALITY = 65

    /** Pixels de la frame de référence (512 × 206), celle de la vague 1. */
    const val BASE_PIXELS = 105_472

    /** Forme de la frame de référence (206 / 512) : le modèle ne connaît que le côté long. */
    const val REFERENCE_ASPECT = 206.0 / 512.0

    /** Exposant de la loi de puissance `octets ∝ pixels^0,81`, ajusté sur la table mesurée. */
    const val PIXEL_POWER = 0.81

    /** Enveloppe JSON de la requête `/live`, en octets (u16). */
    const val ENVELOPE_BYTES = 1_000

    /**
     * Les 8 paliers, du plus lourd (0) au plus léger (7). Les **trois premiers ne changent
     * que la qualité** : ils restent applicables à la chaîne NV21 directe (u02), où le bord
     * est fixé par CameraX. Ensuite le bord baisse.
     */
    val Tiers: List<EncodePlan> = listOf(
        EncodePlan(512, 65), // 0 : le comportement actuel
        EncodePlan(512, 55), // 1 : qualité seule
        EncodePlan(512, 45), // 2 : qualité seule
        EncodePlan(448, 55), // 3 : le bord baisse
        EncodePlan(448, 45), // 4
        EncodePlan(384, 55), // 5
        EncodePlan(384, 45), // 6
        EncodePlan(320, 45), // 7 : plancher
    )

    /** Palier par défaut : l'identique d'aujourd'hui, rien ne change au déploiement. */
    val Default: EncodePlan get() = Tiers.first()

    /** Plancher : le palier le plus léger (10,5 Ko sur le fil). */
    val Floor: EncodePlan get() = Tiers.last()

    /** Qualités de la table mesurée, croissantes. */
    private val QUALITIES = intArrayOf(25, 35, 45, 55, 65, 75, 85)

    /** Table ImageIO du JDK recalée terrain (×0,88), indexée comme [QUALITIES]. */
    private val BASE_BYTES = intArrayOf(10_871, 13_486, 15_729, 17_858, 20_802, 25_456, 34_510)

    /** Palier borné : un index hors échelle donne le palier le plus proche. */
    fun plan(tier: Int): EncodePlan = Tiers[tier.coerceIn(0, Tiers.lastIndex)]

    /** Octets JPEG de base pour [quality] : table mesurée, interpolée entre deux qualités. */
    fun baseBytes(quality: Int): Int {
        val q = quality.coerceIn(QUALITIES.first(), QUALITIES.last())
        var i = 0
        while (i < QUALITIES.size - 2 && QUALITIES[i + 1] < q) i += 1
        val span = (QUALITIES[i + 1] - QUALITIES[i]).toDouble()
        val ratio = (q - QUALITIES[i]) / span
        return (BASE_BYTES[i] + (BASE_BYTES[i + 1] - BASE_BYTES[i]) * ratio).roundToInt()
    }

    /** Octets JPEG estimés pour un bord et une qualité (loi de puissance, § modèle). */
    fun jpegBytes(edgePx: Int, quality: Int, aspect: Double = REFERENCE_ASPECT): Int {
        val pixels = edgePx.toDouble() * edgePx * aspect
        val factor = (pixels / BASE_PIXELS).pow(PIXEL_POWER)
        return (baseBytes(quality) * factor).roundToInt()
    }

    /** Octets sur le fil : base64 (+33 %) + enveloppe, ou binaire si le transport l'est (u16). */
    fun onWireBytes(jpegBytes: Int, binary: Boolean = false): Int =
        if (binary) jpegBytes + ENVELOPE_BYTES else jpegBytes * 4 / 3 + ENVELOPE_BYTES

    /** Octets sur le fil d'un palier, modèle. */
    fun onWireBytes(plan: EncodePlan): Int = onWireBytes(jpegBytes(plan.edgePx, plan.jpegQuality))

    /** Millisecondes de remontée : `octets × 8 / débit` (débit en kbit/s, 0 = inconnu). */
    fun uploadMs(onWireBytes: Int, kbitPerSecond: Int): Long =
        if (kbitPerSecond <= 0) 0L else onWireBytes.toLong() * 8L / kbitPerSecond
}

/** Ce que le régulateur retient de la frame qui vient de partir. */
data class UploadStatus(
    /** Index du palier courant dans [UploadLadder.Tiers]. */
    val tier: Int,
    /** Palier courant : c'est lui qui pilote l'encodage de la frame suivante. */
    val plan: EncodePlan,
    /** Part réseau lissée (EWMA), en ms. */
    val shareMs: Long,
    /** Débit montant estimé (kbit/s), `0` tant qu'aucun palier n'a bougé. */
    val estimatedKbitPerSecond: Int,
)

/**
 * Régulateur du poids montant : il **marche** sur [UploadLadder.Tiers] et s'arrête dès que
 * le poids n'est plus le goulot (u23).
 *
 * Règles, toutes vérifiées sur liens simulés ([UploadLadderTest]) :
 *
 *  1. Départ au **palier 0** (512 px q65) : si le lien va bien, **rien ne change** — le
 *     palier 0 est le comportement actuel, une fusion qui ignore ce code ne régresse pas.
 *  2. Part réseau observée (aller-retour client − durée serveur) **> 250 ms** : on sonde le
 *     palier suivant. La comparaison se fait sur des parts **lissées** (EWMA) après
 *     [FRAMES_PER_TIER] frames, sinon on décide sur du bruit.
 *  3. Un palier plus léger n'est gardé que s'il fait gagner **≥ 5 ms** : sinon on remonte,
 *     on gèle la descente quelques frames, et l'image n'a pas été dégradée pour rien.
 *  4. Part réseau bien sous la cible (< 200 ms) : on sonde un palier plus **lourd** et on ne
 *     le garde que s'il coûte moins de **15 ms**.
 *  5. Le gain n'est mesuré que sur des octets réels : au-dessus de 5 Mbit/s, le régulateur
 *     constate que rien ne coûte et **ne change aucun palier** (0 octet économisé).
 *
 * Sûre entre threads : appelée depuis un seul fil (celui des réponses du direct).
 */
class UploadRegulator(
    /** L'échelle parcourue (les 8 paliers par défaut). */
    private val tiers: List<EncodePlan> = UploadLadder.Tiers,
    /** Part réseau au-delà de laquelle on allège (250 ms : borne haute mesurée). */
    private val heavyShareMs: Double = HEAVY_SHARE_MS,
    /** Part réseau sous laquelle on réembourge (le lien a de la marge). */
    private val lightShareMs: Double = LIGHT_SHARE_MS,
    /** Gain minimal, en ms, pour garder un palier plus léger. */
    private val minGainMs: Double = MIN_GAIN_MS,
    /** Surcoût maximal, en ms, pour garder un palier plus lourd. */
    private val maxExtraMs: Double = MAX_EXTRA_MS,
    /** Frames minimum observées par palier avant de décider (deux : le bruit s'annule). */
    private val framesPerTier: Int = FRAMES_PER_TIER,
    /** Frames d'attente après un palier refusé (le lien a peut-être changé). */
    private val freezeFrames: Int = FREEZE_FRAMES,
) {

    /** Ce que le régulateur est en train de tester, s'il teste quelque chose. */
    private enum class Probe { None, Lighter, Heavier }

    private var index = 0
    private var started = false
    private var shareEwma = 0.0
    private var lastShare = 0.0
    private var frames = 0
    private var probe = Probe.None
    private var refTier = 0
    private var refShare = 0.0
    private var refRaw = 0.0
    private var refWire = 0
    private var frozen = 0
    private var wireBytes = 0
    private var kbitPerSecond = 0

    /** Palier courant : `plan.edgePx` et `plan.jpegQuality` pilotent l'encodage. */
    val plan: EncodePlan get() = tiers[index.coerceIn(0, tiers.lastIndex)]

    /** Index du palier courant. */
    val tier: Int get() = index

    /** Part réseau lissée (EWMA), en ms. */
    val shareMs: Long get() = shareEwma.roundToInt().toLong()

    /** Débit montant estimé (kbit/s) : `0` tant qu'aucun palier n'a bougé. */
    val estimatedKbitPerSecond: Int get() = kbitPerSecond

    /** Nombre de sondes déclenchées depuis le départ (chacune accepte ou refuse un palier). */
    var probes: Int = 0
        private set

    /**
     * Une frame vient de partir : [networkShareMs] est sa **part réseau** (aller-retour
     * client − durée serveur annoncée) et [onWireBytes] son poids réel sur le fil
     * (longueur base64 × 3/4). Renvoie le palier à utiliser pour la frame **suivante**.
     */
    fun onFrame(networkShareMs: Long, onWireBytes: Int): UploadStatus {
        val share = networkShareMs.coerceAtLeast(0L).toDouble()
        shareEwma = if (started) EWMA * share + (1 - EWMA) * shareEwma else share
        started = true
        lastShare = share
        wireBytes = onWireBytes.coerceAtLeast(0)
        frames += 1
        if (probe == Probe.None) {
            if (frozen > 0) frozen -= 1 else if (frames >= framesPerTier) maybeProbe()
        } else if (frames >= framesPerTier) {
            settle()
        }
        return UploadStatus(index, plan, shareMs, kbitPerSecond)
    }

    /** Nouvelle séance : retour au palier 0 (le comportement actuel), mesures oubliées. */
    fun reset() {
        index = 0
        started = false
        shareEwma = 0.0
        lastShare = 0.0
        frames = 0
        probe = Probe.None
        refTier = 0
        refShare = 0.0
        refRaw = 0.0
        refWire = 0
        frozen = 0
        wireBytes = 0
        kbitPerSecond = 0
        probes = 0
    }

    /** Le lien est-il assez chargé (ou assez libre) pour tenter un autre palier ? */
    private fun maybeProbe() {
        when {
            shareEwma > heavyShareMs && index < tiers.lastIndex -> start(Probe.Lighter, index + 1)
            shareEwma < lightShareMs && index > 0 -> start(Probe.Heavier, index - 1)
        }
    }

    private fun start(kind: Probe, target: Int) {
        probe = kind
        probes += 1
        refTier = index
        refShare = shareEwma
        refRaw = lastShare
        refWire = wireBytes
        index = target
        frames = 0
    }

    /** Après [framesPerTier] frames au nouveau palier : on le garde s'il gagne, sinon retour. */
    private fun settle() {
        val accepted = when (probe) {
            Probe.Lighter -> refShare - shareEwma >= minGainMs
            else -> shareEwma - refShare < maxExtraMs
        }
        if (accepted) estimate() else {
            index = refTier
            frozen = freezeFrames
        }
        probe = Probe.None
        frames = 0
    }

    /**
     * Débit montant estimé : les octets gagnés divisés par les ms gagnées entre deux paliers
     * **résolus** (mesures brutes, pas l'EWMA). Sert au statut et à la table, jamais à la
     * décision — qui est purement « gain mesuré ».
     */
    private fun estimate() {
        val deltaMs = abs(refRaw - lastShare)
        val deltaBytes = abs(refWire - wireBytes)
        if (deltaMs < MIN_ESTIMATE_MS || deltaBytes == 0) return
        kbitPerSecond = (deltaBytes * 8.0 / deltaMs).roundToInt()
    }

    companion object {
        /** Part réseau au-delà de laquelle on allège (mesuré : borne haute du téléphone). */
        const val HEAVY_SHARE_MS = 250.0

        /** Part réseau sous laquelle on réembourge. */
        const val LIGHT_SHARE_MS = 200.0

        /** Gain minimal pour garder un palier plus léger. */
        const val MIN_GAIN_MS = 5.0

        /** Surcoût maximal pour garder un palier plus lourd. */
        const val MAX_EXTRA_MS = 15.0

        /** Frames observées par palier avant décision (le bruit réseau s'annule). */
        const val FRAMES_PER_TIER = 2

        /** Frames d'attente après un palier refusé. */
        const val FREEZE_FRAMES = 6

        /** Poids de la mesure neuve dans l'EWMA. */
        private const val EWMA = 0.4

        /** En deçà, l'écart de part réseau tient du bruit : pas d'estimation de débit. */
        private const val MIN_ESTIMATE_MS = 3.0
    }
}