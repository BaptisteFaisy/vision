package dev.sam3vision.core.settings

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enveloppe AES-256/GCM du repli fichier, testée sans Android : format
 * `[IV 12][chiffré][tag 16]`, IV neuf à chaque écriture, tag vérifié, et `null`
 * (jamais d'exception) dès que le contenu ne se déchiffre pas.
 */
class SettingsCipherTest {

    private val cle = aesKey(ByteArray(32) { (it * 7 + 3).toByte() })
    private val autreCle = aesKey(ByteArray(32) { (it * 11 + 5).toByte() })
    private val secret = "llmApiKey=sk-live-0123456789abcdef".toByteArray()

    @Test
    fun `un clair se relit a l identique`() {
        val chiffre = AesGcm.encrypt(cle, secret)

        assertArrayEquals(secret, AesGcm.decrypt(cle, chiffre))
    }

    @Test
    fun `deux ecritures du meme contenu ont des IV et des chiffres differents`() {
        val premier = AesGcm.encrypt(cle, secret)
        val second = AesGcm.encrypt(cle, secret)

        assertNotEquals(
            "IV réutilisé : GCM perd confidentialité et authenticité",
            premier.copyOfRange(0, AesGcm.IV_SIZE).toList(),
            second.copyOfRange(0, AesGcm.IV_SIZE).toList(),
        )
        assertNotEquals("le chiffré doit changer aussi", premier.toList(), second.toList())
        assertArrayEquals("même clair après déchiffrement", secret, AesGcm.decrypt(cle, second))
    }

    @Test
    fun `le format est iv puis chiffre puis tag`() {
        val chiffre = AesGcm.encrypt(cle, secret)

        // 12 octets d'IV + clair + 16 octets de tag GCM.
        assertEquals(AesGcm.IV_SIZE + secret.size + 16, chiffre.size)
        assertEquals(AesGcm.IV_SIZE, AesGcm.encrypt(cle, ByteArray(0)).size - 16)
    }

    @Test
    fun `le clair n apparait pas dans les octets chiffres`() {
        val chiffre = AesGcm.encrypt(cle, secret)

        assertFalse(
            "le contenu doit être chiffré, pas simplement emballé",
            contient(chiffre, "sk-live-0123456789abcdef".toByteArray()),
        )
    }

    @Test
    fun `un octet modifie invalide le tag`() {
        val chiffre = AesGcm.encrypt(cle, secret)
        chiffre[chiffre.size - 1] = (chiffre[chiffre.size - 1].toInt() xor 0x01).toByte()

        assertNull("le tag doit être vérifié, pas seulement présent", AesGcm.decrypt(cle, chiffre))
    }

    @Test
    fun `un iv modifie invalide le tag`() {
        val chiffre = AesGcm.encrypt(cle, secret)
        chiffre[0] = (chiffre[0].toInt() xor 0xFF).toByte()

        assertNull(AesGcm.decrypt(cle, chiffre))
    }

    @Test
    fun `un fichier tronque rend null au lieu de lever`() {
        val chiffre = AesGcm.encrypt(cle, secret)

        assertNull("plus court que l'IV", AesGcm.decrypt(cle, chiffre.copyOfRange(0, 5)))
        assertNull("exactement l'IV, rien à déchiffrer", AesGcm.decrypt(cle, chiffre.copyOfRange(0, 12)))
        assertNull("tag absent", AesGcm.decrypt(cle, chiffre.copyOfRange(0, chiffre.size - 1)))
        assertNull("vide", AesGcm.decrypt(cle, ByteArray(0)))
    }

    @Test
    fun `une cle differente ne dechiffre pas et ne leve pas`() {
        val chiffre = AesGcm.encrypt(cle, secret)

        assertNull("rotation de clé : illisible, donc réglages par défaut", AesGcm.decrypt(autreCle, chiffre))
        assertArrayEquals(secret, AesGcm.decrypt(cle, chiffre))
    }

    @Test
    fun `une cle de 128 bits marche aussi, une cle vide est refusee`() {
        val court = SecretKeySpec(ByteArray(16) { 1 }, "AES")
        val chiffre = AesGcm.encrypt(court, secret)

        assertArrayEquals(secret, AesGcm.decrypt(court, chiffre))

        var refuse = false
        try {
            AesGcm.encrypt(SecretKeySpec(ByteArray(0), "AES"), secret)
        } catch (e: Exception) {
            refuse = true
        }
        assertTrue("une clé vide n'est pas une clé", refuse)
    }

    /** Recherche naïve : le test doit être lisible sans dépendance. */
    private fun contient(bloc: ByteArray, motif: ByteArray): Boolean {
        if (motif.isEmpty() || bloc.size < motif.size) return false
        return (0..bloc.size - motif.size).any { debut ->
            motif.indices.all { bloc[debut + it] == motif[it] }
        }
    }
}