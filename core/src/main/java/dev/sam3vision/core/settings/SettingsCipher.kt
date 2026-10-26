package dev.sam3vision.core.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ce que [PrivateFileSettingsStore] attend d'un chiffreur : des octets clairs entrent, des
 * octets autoportants sortent. `decrypt` ne lève **jamais** — un contenu illisible
 * (mauvaise clé, fichier tronqué, octets modifiés) rend `null`, et l'app repart sur
 * des réglages vides plutôt que de planter au démarrage.
 */
interface SettingsCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray?
}

/**
 * Enveloppe AES-256/GCM pure JVM : `[IV 12 octets][ciphertext][tag 16 octets]`.
 *
 * Séparée de la gestion de la clé (Keystore Android, ou clé de test) pour que le
 * format, l'unicité de l'IV et la vérification du tag soient testables sans
 * téléphone. Un IV de 12 octets est tiré dans `SecureRandom` **à chaque**
 * `encrypt` : deux écritures du même contenu ne produisent jamais le même fichier,
 * et un IV réutilisé en GCM casserait la confidentialité comme l'authentification.
 */
internal object AesGcm {
    const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Un IV frais par appel — jamais de `cipher` recyclé, jamais d'IV fixe. */
    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE).also { SecureRandomHolder.INSTANCE.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    /** `null` si trop court, si le tag ne valide pas, ou si la clé est différente. */
    fun decrypt(key: SecretKey, data: ByteArray): ByteArray? = try {
        if (data.size <= IV_SIZE) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE))
            cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
        }
    } catch (e: Exception) {
        // Tag invalide, clé tournée, octets tronqués : ici on ne lève jamais, le store
        // retombe sur Settings.Default. Un réglage perdu se ressaisit, un crash non.
        null
    }
}

/** Tirage d'IV partagé : `SecureRandom` est coûteux à créer, on le garde vivant. */
private object SecureRandomHolder {
    val INSTANCE: java.security.SecureRandom = java.security.SecureRandom()
}

/**
 * Chiffreur du repli fichier : clé AES-256 **non exportable** dans l'Android
 * Keystore (alias dédié), enveloppe [AesGcm]. La clé ne quitte jamais le matériel :
 * copier le fichier `.enc` ne donne rien sans ce téléphone.
 */
internal class KeystoreSettingsCipher : SettingsCipher {
    override fun encrypt(plain: ByteArray): ByteArray = AesGcm.encrypt(secretKey(), plain)

    override fun decrypt(data: ByteArray): ByteArray? = AesGcm.decrypt(secretKey(), data)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        return generatedKey()
    }

    private fun generatedKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "sam3vision_settings_v1"
    }
}

/**
 * Clé de 256 bits hors Keystore, pour les tests JVM et pour un futur repli «
 * ordinateur de développement » : même enveloppe [AesGcm], même format de fichier.
 */
internal fun aesKey(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")
