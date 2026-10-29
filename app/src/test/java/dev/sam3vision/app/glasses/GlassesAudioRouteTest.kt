package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Choix de la sortie audio de la voix, sur JVM : aucun `AudioManager`, aucun
 * appareil, aucun stub.
 *
 * Les deux règles sont celles qui se paient sur le terrain : la paire de lunettes
 * connue du SDK passe **avant** une autre sortie Bluetooth (sinon des écouteurs
 * branchés prennent la voix), et un appareil qui n'est pas un puits de son n'est
 * **jamais** choisi (le téléphone de test se présente lui-même comme une sortie
 * SCO : s'y router fait disparaître le son sans erreur).
 *
 * Depuis que le banc (`bench/GlassesVoice`) **et** la production
 * ([GlassesVoiceRoute]) ne sont plus que des adaptateurs, ce fichier est le seul
 * endroit où la décision est prouvée : le code Android ne fait que remplir des
 * [AudioOutput] et appliquer le résultat.
 */
class GlassesAudioRouteTest {

    @Test
    fun `aucun candidat, aucune sortie choisie`() {
        assertNull(pickBluetoothOutput(emptyList(), preferredName = "Ray-Ban Meta"))
    }

    @Test
    fun `une paire nommee passe avant une meilleure par le type`() {
        val picked = pickBluetoothOutput(
            listOf(
                output(name = "Linkster TW1", type = TYPE_BLE, rank = 0),
                output(name = "Ray-Ban Meta", type = TYPE_A2DP, rank = 2),
            ),
            preferredName = "Ray-Ban Meta",
        )
        assertEquals("Ray-Ban Meta", picked?.name)
    }

    @Test
    fun `sans nom connu c est le meilleur type qui gagne`() {
        val picked = pickBluetoothOutput(
            listOf(
                output(name = "haut-parleur", type = TYPE_A2DP, rank = 2),
                output(name = "Linkster TW1", type = TYPE_BLE, rank = 0),
            ),
            preferredName = null,
        )
        assertEquals("Linkster TW1", picked?.name)
    }

    @Test
    fun `un appareil qui n est pas un puits de son est ignore`() {
        val picked = pickBluetoothOutput(
            listOf(
                // Ce que le téléphone de test annonce : type SCO, nom = son propre modèle.
                output(name = "2312DRA50G", type = TYPE_SCO, rank = 4, isSink = false),
                output(name = "Ray-Ban Meta", type = TYPE_A2DP, rank = 2),
            ),
            preferredName = null,
        )
        assertEquals("Ray-Ban Meta", picked?.name)
    }

    @Test
    fun `un nom connu qui n est pas un puits de son ne prend pas la voix`() {
        val picked = pickBluetoothOutput(
            listOf(
                // Le nom du SDK est là, mais cet appareil ne peut pas recevoir de son :
                // le préférer ferait un silence, ce que le journal ne saurait pas dire.
                output(name = "Ray-Ban Meta", type = TYPE_SCO, rank = 0, isSink = false),
                output(name = "JBL Flip 6", type = TYPE_A2DP, rank = 2),
            ),
            preferredName = "Ray-Ban Meta",
        )
        assertEquals("JBL Flip 6", picked?.name)
    }

    @Test
    fun `le nom est reconnu sans la casse ni le suffixe de la pile Bluetooth`() {
        val picked = pickBluetoothOutput(
            listOf(output(name = "Ray-Ban Meta Hands-Free", type = TYPE_SCO, rank = 4)),
            preferredName = "ray-ban meta",
        )
        assertEquals("Ray-Ban Meta Hands-Free", picked?.name)
    }

    @Test
    fun `un nom prefere vide ne filtre rien`() {
        val picked = pickBluetoothOutput(
            listOf(output(name = "JBL Flip 6", type = TYPE_A2DP, rank = 2)),
            preferredName = "   ",
        )
        assertEquals("JBL Flip 6", picked?.name)
    }

    private fun output(name: String, type: Int, rank: Int, isSink: Boolean = true) =
        AudioOutput(name = name, type = type, isSink = isSink, rank = rank)

    private companion object {
        const val TYPE_SCO = 7
        const val TYPE_A2DP = 8
        const val TYPE_BLE = 26
    }
}
