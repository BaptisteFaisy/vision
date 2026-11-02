package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.picto.PictoPlaceMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IseeWorldTapToggleTest {

    private var now = 0L
    private var mode = PictoPlaceMode.DEHORS
    private val applied = mutableListOf<Pair<PictoPlaceMode, String>>()

    private val toggle = IseeWorldTapToggle(
        placeMode = { mode },
        apply = { next, text -> applied += next to text; mode = next },
        clock = { now },
    )

    private fun tap(moment: Long): (suspend () -> Unit)? {
        now = moment
        return toggle.onTap()
    }

    private fun tapFire(moment: Long) = tap(moment) ?: error("pas de rafale à $moment")

    @Test
    fun `quatre taps dehors rendent l'action dedans qui dit indoor mode`() = runBlocking {
        assertNull(tap(0))
        assertNull(tap(700))
        assertNull(tap(1400))
        tapFire(2100)()
        assertEquals(listOf(PictoPlaceMode.DEDANS to "indoor mode"), applied)
    }

    @Test
    fun `quatre taps dedans rendent l'action dehors qui dit outdoor mode`() = runBlocking {
        mode = PictoPlaceMode.DEDANS
        tap(0)
        tap(700)
        tap(1400)
        tapFire(2100)()
        assertEquals(listOf(PictoPlaceMode.DEHORS to "outdoor mode"), applied)
    }

    @Test
    fun `trois taps ne rendent rien`() {
        assertNull(tap(0))
        assertNull(tap(700))
        assertNull(tap(1400))
        assertEquals(emptyList<Pair<PictoPlaceMode, String>>(), applied)
    }

    @Test
    fun `quatrieme tap hors fenetre ne rend rien`() {
        tap(0)
        tap(100)
        tap(200)
        assertNull(tap(3100))
        assertEquals(emptyList<Pair<PictoPlaceMode, String>>(), applied)
    }

    @Test
    fun `reset casse la rafale`() {
        tap(0)
        tap(100)
        tap(200)
        toggle.reset()
        assertNull(tap(300))
        assertEquals(emptyList<Pair<PictoPlaceMode, String>>(), applied)
    }

    @Test
    fun `huit taps rendent deux actions, aller-retour`() = runBlocking {
        val dits = mutableListOf<String?>()
        var moment = 0L
        repeat(8) {
            val action = tap(moment)
            if (action == null) dits += null else {
                action()
                dits += applied.last().second
            }
            moment += 500
        }
        assertEquals(listOf(null, null, null, "indoor mode", null, null, null, "outdoor mode"), dits)
        assertEquals(
            listOf(PictoPlaceMode.DEDANS to "indoor mode", PictoPlaceMode.DEHORS to "outdoor mode"),
            applied,
        )
    }
}
