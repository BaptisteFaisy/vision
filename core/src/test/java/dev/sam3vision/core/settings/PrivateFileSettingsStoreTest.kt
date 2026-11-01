package dev.sam3vision.core.settings

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Le repli chiffré, testé sans Android : un fichier qui tient, des réglages qui ne
 * se perdent pas, et surtout du secret **chiffré** sur un fichier **0600**. Une clé
 * écrite en clair là ne s'efface qu'en changeant de téléphone.
 */
class PrivateFileSettingsStoreTest {

    private val dossier = Files.createTempDirectory("isee-settings-store").toFile()
    private val fichier = File(dossier, "settings.enc")
    private val cle = ByteArray(32) { (it * 3 + 1).toByte() }
    private val chiffre = TestCipher(cle)
    private val saisi = Settings.Default.copy(
        llmBaseUrl = "https://api.test/v1",
        llmApiKey = "sk-saisie-dans-l-app",
        ttsApiKey = "cle-voix-saisie",
        asrApiKey = "cle-dictee-saisie",
        samBaseUrl = "https://sam.test/live",
    )

    private fun store(c: SettingsCipher = chiffre) = PrivateFileSettingsStore(fichier, c)

    @Test
    fun `un reglage avec secret se relit a l identique`() = runBlocking {
        store().save(saisi)

        assertEquals(saisi, store().load())
    }

    @Test
    fun `aucun fichier pose sur le disque ne contient une cle en clair`() = runBlocking {
        store().save(saisi)
        val octets = fichier.readBytes().toList()

        assertFalse("clé LLM en clair sur le disque", contient(octets, saisi.llmApiKey))
        assertFalse("clé voix en clair sur le disque", contient(octets, saisi.ttsApiKey))
        assertFalse("clé dictée en clair sur le disque", contient(octets, saisi.asrApiKey))
        assertNotEquals(saisi.llmApiKey, String(fichier.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `le fichier est lisible et inscriptible par le seul proprietaire`() = runBlocking {
        assumePosix()
        store().save(saisi)

        assertEquals(OWNER_ONLY, permissions(fichier))
    }

    @Test
    fun `les permissions tiennent sur une reecriture`() = runBlocking {
        assumePosix()
        store().save(saisi)
        fichier.setReadable(true, false) // quelqu'un (ou un outil) a élargi le mode
        store().save(saisi.copy(llmApiKey = "autre-cle"))

        assertEquals(OWNER_ONLY, permissions(fichier))
    }

    @Test
    fun `aucun fichier temporaire ne traine apres une ecriture`() = runBlocking {
        store().save(saisi)

        val residus = dossier.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("résidus : ${residus.map { it.name }}", residus.isEmpty())
    }

    @Test
    fun `deux ecritures du meme reglage ne donnent pas le meme fichier`() = runBlocking {
        store().save(saisi)
        val premier = fichier.readBytes().toList()

        store().save(saisi)
        val second = fichier.readBytes().toList()

        assertNotEquals("IV fixe : deux écritures identiques se ressemblent", premier, second)
        assertEquals(saisi, store().load())
    }

    @Test
    fun `pas de fichier donne les reglages par defaut`() = runBlocking {
        assertEquals(Settings.Default, store().load())
    }

    @Test
    fun `un fichier vide donne les reglages par defaut`() = runBlocking {
        fichier.writeBytes(ByteArray(0))

        assertEquals(Settings.Default, store().load())
    }

    @Test
    fun `un fichier corrompu donne les reglages par defaut sans planter`() = runBlocking {
        store().save(saisi)
        fichier.writeBytes(ByteArray(64) { (it * 31).toByte() })

        assertEquals(Settings.Default, store().load())
    }

    @Test
    fun `un fichier tronque en plein tag donne les reglages par defaut`() = runBlocking {
        store().save(saisi)
        fichier.writeBytes(fichier.readBytes().dropLast(10).toByteArray())

        assertEquals(Settings.Default, store().load())
    }

    @Test
    fun `une cle tournee repart sur des reglages vides sans planter`() = runBlocking {
        store().save(saisi)

        assertEquals(Settings.Default, store(TestCipher(ByteArray(32) { 9 })).load())
    }

    @Test
    fun `un repertoire a la place du fichier ne fait pas planter le load`() = runBlocking {
        assertTrue(dossier.delete())
        assertTrue(File(dossier.path).mkdirs())
        File(dossier, "settings.enc").mkdirs()

        assertEquals(Settings.Default, store().load())
    }

    @Test
    fun `un fichier illisible rend les reglages par defaut au lieu de lever`() = runBlocking {
        store().save(saisi)
        fichier.setReadable(false, false)
        fichier.setWritable(false, false)
        assumeTrue("chmod illisible effectif exigé", !fichier.canRead())

        try {
            assertEquals(Settings.Default, store().load())
        } finally {
            fichier.setReadable(true, true)
            fichier.setWritable(true, true)
        }
    }

    @Test
    fun `une cle vide reste vide au rechargement`() = runBlocking {
        store().save(Settings.Default)

        assertEquals("", store().load().llmApiKey)
        assertEquals(Settings.Default, store().load())
    }

    @Test
    fun `un ancien format se relit et garde ce qu il contient`() = runBlocking {
        // Migration : fichier écrit par une version qui ne connaissait ni TTS, ni ASR,
        // ni région, ni langue — la clé LLM de l'époque doit survivre, le reste prend son défaut.
        val herite = "llmBaseUrl=https://api.ancienne.version/v1\n" +
            "llmApiKey=cle-de-l-epoque\nllmModel=ancien-modele\nsamBaseUrl=\n"
        fichier.writeBytes(chiffre.encrypt(herite.toByteArray(Charsets.UTF_8)))

        val relu = store().load()

        assertEquals("cle-de-l-epoque", relu.llmApiKey)
        assertEquals("https://api.ancienne.version/v1", relu.llmBaseUrl)
        assertEquals("ancien-modele", relu.llmModel)
        assertEquals("", relu.ttsApiKey)
        assertEquals("", relu.asrApiKey)
        assertEquals(SamRegion.Eu, relu.samRegion)
        assertEquals(Settings.DEFAULT_TTS_VOICE_ID, relu.ttsVoiceId)
    }

    @Test
    fun `apres migration l enregistrement moderne est complet`() = runBlocking {
        assumePosix()
        val herite = "llmApiKey=cle-de-l-epoque\n"
        fichier.writeBytes(chiffre.encrypt(herite.toByteArray(Charsets.UTF_8)))

        store().save(store().load().copy(ttsApiKey = "cle-voix"))

        val relu = store().load()
        assertEquals("cle-de-l-epoque", relu.llmApiKey)
        assertEquals("cle-voix", relu.ttsApiKey)
        assertEquals(OWNER_ONLY, permissions(fichier))
    }

    @Test
    fun `l ecriture cree les dossiers manquants`() = runBlocking {
        assumePosix()
        val profond = File(dossier, "a/b/c/settings.enc")

        PrivateFileSettingsStore(profond, chiffre).save(saisi)

        assertEquals(saisi, PrivateFileSettingsStore(profond, chiffre).load())
        assertEquals(OWNER_ONLY, permissions(profond))
    }

    private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    /** Les permissions POSIX n'existent pas sous Windows : ces tests s'y ignorent. */
    private fun assumePosix() {
        assumeTrue(
            "permissions POSIX exigées",
            runCatching { Files.getPosixFilePermissions(dossier.toPath()) }.isSuccess,
        )
    }

    private fun permissions(target: File): Set<PosixFilePermission> =
        Files.getPosixFilePermissions(target.toPath())

    private fun contient(bloc: List<Byte>, motif: String): Boolean {
        val a = bloc
        val b = motif.toByteArray(Charsets.UTF_8).toList()
        if (b.isEmpty() || a.size < b.size) return false
        return (0..a.size - b.size).any { debut -> b.indices.all { a[debut + it] == b[it] } }
    }
}

/** Chiffreur de test : même enveloppe [AesGcm] que sur le téléphone, clé hors Keystore. */
internal class TestCipher(key: ByteArray) : SettingsCipher {
    private val clef = aesKey(key)

    override fun encrypt(plain: ByteArray): ByteArray = AesGcm.encrypt(clef, plain)

    override fun decrypt(data: ByteArray): ByteArray? = AesGcm.decrypt(clef, data)
}