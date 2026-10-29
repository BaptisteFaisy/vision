package dev.sam3vision.app

import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LivePrompts
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePresetRequestTest {

    @Test
    fun `sans demande, les defauts rue`() {
        assertEquals(LivePrompts.default, HomePresetRequest.consume())
    }

    @Test
    fun `defaut = essentiels, usage unique`() {
        HomePresetRequest.pushDefault()
        assertEquals(HomeModes.DEFAULT.prompts, HomePresetRequest.consume())
        assertEquals(LivePrompts.default, HomePresetRequest.consume())
    }

    @Test
    fun `concepts imposes prioritaires et purgent le preset`() {
        HomePresetRequest.pushDefault()
        HomePresetRequest.pushPrompts(listOf("keys"))
        assertEquals(listOf("keys"), HomePresetRequest.consume())
        assertEquals(LivePrompts.default, HomePresetRequest.consume())
    }
}
