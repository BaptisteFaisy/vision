package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Micro de dictée (P1-1) : `start()` ne lève plus — `startRecording()` jetait
 * `IllegalStateException` quand le micro venait d'être volé (appel, assistant, autre app),
 * le device n'était pas libéré et la dictée suivante refusait de démarrer sans motif. Ici,
 * en JVM, le tampon natif est absent (`getMinBufferSize` rend 0, valeurs par défaut
 * d'`android.jar`) : c'est un refus propre, visible dans l'état — jamais une exception, jamais
 * un silence sans cause.
 */
class DictationRecorderGuardTest {

    @Test
    fun `start ne leve jamais et signale l'echec dans l'etat`() {
        val recorder = DictationRecorder()

        assertFalse("micro indisponible en JVM", recorder.start())
        assertNotNull("l'échec est visible dans l'état", recorder.lastFailure)
        assertTrue("motif : ${recorder.lastFailure}", recorder.lastFailure!!.startsWith("taille-tampon"))

        // Deuxième essai : toujours sans exception, et l'état est réévalué.
        assertFalse(recorder.start())
        assertNotNull(recorder.lastFailure)
    }

    @Test
    fun `stopAndCollect rend vide sans capture et reste sans exception`() {
        val recorder = DictationRecorder()
        assertFalse(recorder.start())
        assertTrue(recorder.stopAndCollect().isEmpty())
        assertTrue(recorder.stopAndCollect().isEmpty())
    }

    @Test
    fun `un micro occupe est nomme, pas confondu avec une panne reseau`() {
        assertEquals("occupe-ou-ferme", micFailureHint(IllegalStateException("startRecording() on an uninitialized AudioRecord")))
        assertEquals("permission", micFailureHint(SecurityException("RECORD_AUDIO refusée")))
        assertEquals("materiel", micFailureHint(IllegalArgumentException("sample rate")))
        assertEquals("OutOfMemoryError", micFailureHint(OutOfMemoryError()))
    }
}
