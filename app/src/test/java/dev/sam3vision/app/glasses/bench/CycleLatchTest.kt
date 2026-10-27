package dev.sam3vision.app.glasses.bench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrou « une seule fois par cycle » ([CycleLatch]) : la garde qui empêche de
 * relancer l'UI d'enregistrement, de recréer une session ou de ré-ajouter une
 * caméra à chaque émission d'état du SDK.
 *
 * Pur JVM : le verrou ne connaît ni `Context` ni coroutine, c'est précisément
 * pour être prouvé ici qu'il vit hors de `GlassesRuntime`.
 */
class CycleLatchTest {

    @Test
    fun `le premier armement passe et les suivants sont refuses`() {
        val latch = CycleLatch()
        assertTrue("première demande", latch.arm())
        assertFalse("deuxième demande", latch.arm())
        assertFalse("troisième demande", latch.arm())
        assertTrue("le verrou reste armé", latch.isArmed)
    }

    @Test
    fun `desarmer autorise une nouvelle tentative`() {
        val latch = CycleLatch()
        assertTrue(latch.arm())
        latch.disarm()
        assertFalse("disarm libère le verrou", latch.isArmed)
        assertTrue("la tentative suivante passe", latch.arm())
    }

    @Test
    fun `desarmer un verrou jamais arme ne casse rien`() {
        val latch = CycleLatch()
        latch.disarm()
        assertTrue("un verrou libre s'arme", latch.arm())
    }

    @Test
    fun `les trois verrous du cycle sont independants`() {
        val registration = CycleLatch()
        val session = CycleLatch()
        val camera = CycleLatch()
        assertTrue(registration.arm())
        assertFalse(registration.arm())
        assertTrue("l'enregistrement n'arme pas la session", session.arm())
        assertTrue("la session n'arme pas la caméra", camera.arm())
        assertFalse(session.arm())
        assertFalse(camera.arm())
    }

    /**
     * Scénario réel de `GlassesRegistrationPhase.onSessionFailure` : une création
     * de session refusée réarme la demande, le prochain état `REGISTERED` réessaie,
     * puis la session ouverte ne se recrée plus.
     */
    @Test
    fun `une session refusee puis acceptee ne se recree pas`() {
        val session = CycleLatch()
        assertTrue("première demande de session", session.arm())
        session.disarm() // échec : on réarme pour l'état REGISTERED suivant
        assertTrue("nouvelle tentative", session.arm())
        assertFalse("session ouverte : plus de création", session.arm())
    }
}
