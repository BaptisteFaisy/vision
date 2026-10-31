package dev.sam3vision.core.motion

import dev.sam3vision.core.predict.Shift
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Rotation caméra entre deux instants (radians).
 *
 * Convention image (c'est `GyroSource`, côté app, qui convertit les deltas
 * capteur bruts vers elle) :
 * - `dYawRad > 0` = pan vers la gauche : la scène apparente part vers la **droite** ;
 * - `dPitchRad > 0` = tilt vers le haut : la scène apparente part vers le **bas**.
 *
 * Le signe est donc celui du déplacement image, pas celui de la rotation capteur :
 * `dx ≈ f·Δyaw` sans négation (voir [GyroCompensation.expectedShift]).
 */
data class GyroDelta(val dYawRad: Float, val dPitchRad: Float)

/**
 * [GyroDelta] horodaté : [ageMs] est le délai entre la fin de l'intervalle couvert
 * et maintenant. Un delta périmé ne compense plus rien (voir [GyroCompensation]).
 */
data class StampedDelta(val delta: GyroDelta, val ageMs: Long)

/** Déplacement image attendu (px), effet de la rotation seule sur un monde fixe. */
data class ExpectedShift(val dxPx: Float, val dyPx: Float)

/**
 * Résidu gyro-compensé (px) : le shift mesuré dont on a retiré la rotation caméra.
 * Proche de zéro = le monde est fixe, c'est le porteur qui tourne.
 */
data class GyroResidual(val dxPx: Float, val dyPx: Float) {
    /** Norme du résidu (px) : le chiffre à seuiller pour dire « expliqué ou non ». */
    val magnitudePx: Float get() = hypot(dxPx, dyPx)
}

/**
 * Déplacement gyro de repli pour les verdicts, en **pixels du plan de luminance**
 * (les unités de [MotionSample]) : l'appelant convertit le [GyroDelta] par
 * [GyroCompensation.expectedShift] avec la focale du plan luma
 * (`LensSpec.focalPx(lumaWidth, lumaHeight)`), puis met à l'échelle si besoin.
 * [confidence] reprend l'échelle de `Shift` (1 = rotation mesurée proprement).
 */
data class GyroHint(val dxPx: Float, val dyPx: Float, val confidence: Float) {

    /**
     * Fond de repli (rotation pure, pas de zoom) ou `null` si le hint est
     * inexploitable (valeur non finie, confiance sous [GyroCompensation.TRUST_MIN]).
     */
    fun toSample(): MotionSample? {
        if (!dxPx.isFinite() || !dyPx.isFinite()) return null
        if (!confidence.isFinite() || confidence < GyroCompensation.TRUST_MIN) return null
        return MotionSample(dxPx, dyPx, 1f, confidence)
    }
}

/**
 * Compensation gyro de la rotation caméra (PISTE 4) : le bloc-matching global sature
 * en pan rapide (recherche ±16 px, modèle sans rotation — voir [BlockMotionSource]),
 * alors que le vecteur de rotation du téléphone mesure le pan directement.
 *
 * Modèle sténopé au premier ordre : une rotation [GyroDelta] déplace l'image de
 * `dx ≈ f·Δyaw`, `dy ≈ f·Δpitch` (`f` = focale en pixels du plan analysé). C'est
 * `tan θ ≈ θ`, valide à 4 % près jusqu'à [MAX_DELTA_RAD] (~20°) ; au-delà, on ne
 * compense plus — incertain, pas de verdict.
 *
 * Garde-fous (tout échec rend `null`, jamais un résidu inventé) :
 * - rotation excessive (`|Δ| > [MAX_DELTA_RAD]`) : l'approximation sort de son domaine ;
 * - delta périmé (`ageMs > [MAX_AGE_MS]`) : il ne couvre plus l'intervalle inter-frames ;
 * - focale manquante (`focalPx ≤ 0`, non finie) : repli sans crash, comme `focalPx = 0f`
 *   de [dev.sam3vision.core.distance.LensSpec] qui coupe l'affichage des distances.
 *
 * Pur JVM, zéro allocation hors résultat : testable sans capteur.
 */
object GyroCompensation {

    /**
     * Rotation maximale compensée : 0,35 rad (~20°, soit 600°/s à 30 fps — un pan
     * très rapide). Au-delà, `tan θ ≈ θ` sort de 4 % et le flou de bougé rend le
     * shift mesuré douteux de toute façon : incertain.
     */
    const val MAX_DELTA_RAD = 0.35f

    /**
     * Âge maximal du delta : 250 ms (~8 frames à 30 fps). Un delta plus vieux ne
     * couvre plus l'intervalle entre les deux frames comparées : incertain.
     */
    const val MAX_AGE_MS = 250L

    /** Confiance minimale du hint gyro, reprise de `Shift.TRUST_MIN`. */
    const val TRUST_MIN = Shift.TRUST_MIN

    /**
     * Déplacement image attendu (px) pour [delta] et une focale [focalPx] (px du plan
     * analysé). `null` si la focale est inutilisable (≤ 0, non finie) ou le delta non
     * fini — repli sans crash, l'appelant garde le shift mesuré tel quel.
     */
    fun expectedShift(delta: GyroDelta, focalPx: Float): ExpectedShift? {
        if (!focalPx.isFinite() || focalPx <= 0f) return null
        if (!delta.dYawRad.isFinite() || !delta.dPitchRad.isFinite()) return null
        return ExpectedShift(focalPx * delta.dYawRad, focalPx * delta.dPitchRad)
    }

    /**
     * Résidu = shift mesuré − déplacement gyro attendu. `null` (incertain, pas de
     * verdict) si la rotation est excessive, le delta périmé, la focale manquante ou
     * une entrée non finie — voir le KDoc de l'objet.
     */
    fun residual(
        measuredDxPx: Float,
        measuredDyPx: Float,
        stamped: StampedDelta,
        focalPx: Float,
    ): GyroResidual? {
        if (!measuredDxPx.isFinite() || !measuredDyPx.isFinite()) return null
        if (stamped.ageMs < 0L || stamped.ageMs > MAX_AGE_MS) return null
        val delta = stamped.delta
        if (abs(delta.dYawRad) > MAX_DELTA_RAD || abs(delta.dPitchRad) > MAX_DELTA_RAD) return null
        val expected = expectedShift(delta, focalPx) ?: return null
        return GyroResidual(measuredDxPx - expected.dxPx, measuredDyPx - expected.dyPx)
    }

    /**
     * Delta angulaire signé le plus court de [fromRad] à [toRad], dans `]−π, π]` :
     * l'azimut capteur s'enroule à ±π, un pan qui traverse le nord donnerait sinon
     * un delta de ~2π. Pur et testable — utilisé par `GyroSource` (côté app).
     */
    fun shortestDelta(fromRad: Float, toRad: Float): Float {
        val twoPi = 2f * PI.toFloat()
        var delta = (toRad - fromRad) % twoPi
        if (delta <= -PI.toFloat()) delta += twoPi
        if (delta > PI.toFloat()) delta -= twoPi
        return delta
    }
}
