package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.PictoPlaceMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IseeModeChipsTest {

    @Test
    fun `bascule lieu rejoue la phrase canonique`() = runTest {
        val runs = mutableListOf<String>()
        IseeModeChips(this, { PictoPlaceMode.DEDANS }, { runs.add(it); true }, {}).togglePlace()
        runCurrent()
        assertEquals(listOf("mode dehors"), runs)
        IseeModeChips(this, { PictoPlaceMode.DEHORS }, { runs.add(it); true }, {}).togglePlace()
        runCurrent()
        assertEquals(listOf("mode dehors", "mode dedans"), runs)
    }

    @Test
    fun `bascule traversee delegue au chemin vocal`() = runTest {
        var calls = 0
        IseeModeChips(this, { PictoPlaceMode.DEHORS }, { true }, { calls++ }).toggleCrossing()
        assertEquals(1, calls)
    }
}
