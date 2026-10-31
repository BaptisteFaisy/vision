package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Le rendu des nombres ne doit **pas** dépendre de la locale de la machine.
 *
 * Régression mesurée le 26/10 : `formatLiveStatus` formatait « %.1f » sans locale,
 * donc « 1,4 ips » sur le poste (français) et « 1.4 ips » sur le runner GitHub
 * (anglais) — le test passait chez l'un, échouait chez l'autre. Le même défaut
 * existait dans `DistanceText.decimal()`, masqué par un `replace('.', ',')` qui ne
 * tient que si la locale fractionnaire est le point.
 */
class LocaleIndependenceTest {

    private lateinit var initial: Locale

    @Before
    fun before() {
        initial = Locale.getDefault()
    }

    @After
    fun after() {
        Locale.setDefault(initial)
    }

    @Test
    fun `la ligne du direct garde le point quelle que soit la locale`() {
        val locales = listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.ROOT)
        for (locale in locales) {
            Locale.setDefault(locale)
            val line = formatLiveStatus(true, 1.4f, 780, "A100-SXM4-40GB", 8)
            assertTrue("locale $locale : « $line »", line.contains("1.4 fps"))
            assertTrue("locale $locale : « $line »", line.contains("780 ms"))
            assertTrue("locale $locale : « $line »", line.contains("8 objects"))
        }
    }

    @Test
    fun `decimal garde le point quelle que soit la locale`() {
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.ROOT)) {
            Locale.setDefault(locale)
            assertEquals("locale $locale", "1,4", DistanceText.decimal(1.4f, 1, Locale.FRANCE))
            assertEquals("locale $locale", "0,0", DistanceText.decimal(0f, 1, Locale.FRANCE))
        }
    }
}