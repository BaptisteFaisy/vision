package dev.sam3vision.core.local

import java.util.Locale
import dev.sam3vision.core.mask.MaskMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulation du calque local, **horloge virtuelle** : aucune mesure d'appareil, aucune
 * GPU — étiquette « calculé par simulation », jamais « mesuré ».
 *
 * Modèle, explicite parce qu'un chiffre sans modèle n'est pas une preuve :
 * - caméra à 30 ips, une frame toutes les 33 ms ;
 * - serveur : un keyframe toutes les `latence` ms (41/245/422/655 ms, les quatre paliers
 *   de la lane) ; l'arrivée du keyframe rafraîchit l'image affichée ;
 * - « sans calque » : l'image affichée est le dernier keyframe serveur ;
 * - « avec calque » : entre deux keyframes, chaque frame caméra est propagée par
 *   [LocalOverlayEngine] — estimateur parfait dans la colonne de tête, [NoMotion] dans la
 *   colonne « réel », qui est l'état de cet arbre tant que la lane A (M52) n'est pas posée ;
 * - `ips avec calque` compte les **rafraîchissements de l'image affichée** (keyframes
 *   serveur + propagations locales, un par instant) : il dépasse donc légèrement la
 *   cadence caméra quand un keyframe tombe entre deux frames ;
 * - âge moyen = temps moyen écoulé depuis la production de l'image affichée, mesuré au
 *   milliseconde sur la fenêtre (la dernière période est tronquée par la fin de la
 *   simulation : d'où la marge de 10 ms sur 422 ms, et non de 5).
 *
 * Les valeurs de référence du rapport `u13` (2,20 ips et 190 ms sans calque, 27,8 ips et
 * 30 ms avec calque à 422 ms) viennent d'un **autre arbre** et ne sont pas recopiées ici :
 * ce test reproduit la simulation, pas ses chiffres.
 */
class LocalOverlaySimTest {

    private val luma = ByteArray(LumaPlane.SIZE) { 64 }

    /** Latences serveur de la lane : 41 ms (B200), 245 (H200), 422 (A100 eu-west), 655 (téléphone). */
    private val latences = listOf(41L, 245L, 422L, 655L)

    /** Estimateur parfait : la propagation ne coûte rien et ne se trompe jamais. */
    private val parfait = MotionSource { _, _ -> MaskMotion(1.0, 0.5, 1.0, 0.95) }

    @Test
    fun `sans calque la cadence affichee est celle du serveur`() {
        val ligne = simuler(422L, NoMotion)

        assertEquals("1 000 / 422", 2.37, ligne.keyframesFps, 0.05)
        assertEquals("aucun gain : le calque est inerte", ligne.keyframesFps, ligne.displayedFps, 0.4)
        assertEquals("422 / 2 : l'âge moyen d'un keyframe", 211.0, ligne.ageMs, 10.0)
    }

    @Test
    fun `avec un estimateur parfait la cadence atteint 30 ips et l age tombe sous la demi-frame`() {
        val ligne = simuler(422L, parfait)

        assertTrue("la caméra devient le rythme de l'écran : ${ligne.displayedFps}", ligne.displayedFps >= 30.0)
        assertTrue("un keyframe peut s'ajouter entre deux frames : ${ligne.displayedFps}", ligne.displayedFps <= 34.0)
        assertTrue("l'âge tombe à la demi-période caméra : ${ligne.ageMs}", ligne.ageMs <= 17.5)
    }

    @Test
    fun `sans estimateur le calque n apporte aucun rafraichissement`() {
        val inertes = latences.map { simuler(it, NoMotion) }
        val parfaits = latences.map { simuler(it, parfait) }

        inertes.zip(parfaits).forEach { (inerte, parfait) ->
            assertEquals("aucun gain sans estimateur", inerte.keyframesFps, inerte.displayedFps, 0.4)
            assertTrue("le calque parfait fait mieux partout", parfait.displayedFps > inerte.displayedFps)
            assertTrue("et l'âge est toujours meilleur", parfait.ageMs < inerte.ageMs)
        }
        imprimer(latences, inertes, parfaits)
    }

    /** Une ligne de simulation : cadence serveur, cadence affichée, âge moyen de l'image. */
    private data class Ligne(val keyframesFps: Double, val displayedFps: Double, val ageMs: Double)

    /**
     * Rejoue la scène au milliseconde. Le premier keyframe arrive à `latence` (premier
     * aller-retour), puis toutes les `latence` ms ; les frames caméra tombent toutes les
     * [CAMERA_PERIOD_MS] ms à partir de là.
     */
    private fun simuler(latenceMs: Long, source: MotionSource): Ligne {
        val calque = LocalOverlayEngine()
        calque.setMotion(source)
        var keyframeAt = latenceMs
        var nextKeyframeAt = latenceMs * 2
        var imageAt = latenceMs
        var refreshes = 0
        var ageTotal = 0L
        var samples = 0
        var t = latenceMs
        while (t < DUREE_MS) {
            if (t >= nextKeyframeAt) {
                keyframeAt = t
                nextKeyframeAt += latenceMs
                calque.onKeyframe(luma)
                if (imageAt != t) refreshes += 1
                imageAt = t
            }
            if ((t - latenceMs) % CAMERA_PERIOD_MS == 0L) {
                val tick = calque.tick(t, luma, t - keyframeAt, 0.9f)
                if (tick.paint && imageAt != t) refreshes += 1
                if (tick.paint) imageAt = t
            }
            ageTotal += t - imageAt
            samples += 1
            t += SAMPLE_MS
        }
        val duree = (DUREE_MS - latenceMs).toDouble()
        return Ligne(
            keyframesFps = 1_000.0 / latenceMs,
            displayedFps = refreshes * 1_000.0 / duree,
            ageMs = ageTotal.toDouble() / samples,
        )
    }

    /** Table de simulation recopiée dans le compte rendu (System-out JUnit). */
    private fun imprimer(latences: List<Long>, inertes: List<Ligne>, parfaits: List<Ligne>) {
        val texte = buildString {
            appendLine(TABLE_HEADER)
            latences.forEachIndexed { index, latence ->
                append(formater(latence, inertes[index], parfaits[index]))
            }
        }
        println(texte)
        assertEquals("une ligne par latence, plus l'en-tête", latences.size + 1, texte.lines().size)
    }

    private fun formater(latenceMs: Long, sans: Ligne, avec: Ligne): String = String.format(
        Locale.FRANCE,
        "%d ms | %.2f | %.2f ips | %.2f ips | %.0f ms | %.0f ms | calculé par simulation%n",
        latenceMs,
        sans.keyframesFps,
        sans.displayedFps,
        avec.displayedFps,
        sans.ageMs,
        avec.ageMs,
    )

    private companion object {
        /** En-tête de la table, imposé par le contrat de fusion (`docs/L13-FUSION.md` §5). */
        const val TABLE_HEADER =
            "latence | keyframes/s | ips sans calque | ips avec calque | âge sans | âge avec | étiquette"

        /** Durée simulée : assez longue pour que la queue d'un keyframe à 655 ms ne pèse pas. */
        const val DUREE_MS = 4_000L

        /** Période caméra de la lane : 33 ms (30 ips). */
        const val CAMERA_PERIOD_MS = 33L

        /** Pas de mesure de l'âge : le milliseconde. */
        const val SAMPLE_MS = 1L
    }
}