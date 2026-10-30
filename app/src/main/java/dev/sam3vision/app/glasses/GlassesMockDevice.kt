package dev.sam3vision.app.glasses

import android.content.Context
import com.meta.wearable.dat.inputs.types.CapturePressType

/**
 * Lunettes simulées : la seule chose dont `main` a besoin pour parler à un appareil
 * simulé. L'implémentation (Mock Device Kit) vit dans `app/src/debug/`, donc
 * `mwdat-mockdevice` n'est plus embarqué dans l'APK de release.
 */
internal interface GlassesMockDevice {
    fun start(): Boolean
    fun press(type: CapturePressType): Boolean
    /** Injecte « Hey Meta, ouvre iSee » ; `false` si le kit ne sait pas injecter. */
    fun simulateVoiceLaunch(): Boolean
    fun stop()
}

/** Classe debug ; en release elle n'existe pas, `main` ne peut donc pas la lier. */
private const val DEBUG_MOCK_CLASS = "dev.sam3vision.app.glasses.GlassesMock"

/**
 * Fabrique : charge la classe debug par réflexion, au premier usage seulement. En
 * release son absence est muette : l'appelant garde son message d'échec habituel, et
 * les boutons de simulation n'y sont de toute façon pas affichés (build debug).
 */
internal fun mockDevice(context: Context): GlassesMockDevice = object : GlassesMockDevice {
    private var delegate: GlassesMockDevice? = null

    private fun loaded(): GlassesMockDevice? = delegate ?: runCatching {
        Class.forName(DEBUG_MOCK_CLASS)
            .getConstructor(Context::class.java)
            .newInstance(context) as GlassesMockDevice
    }.getOrNull()?.also { delegate = it }

    override fun start(): Boolean = loaded()?.start() ?: false
    override fun press(type: CapturePressType): Boolean = loaded()?.press(type) ?: false
    override fun simulateVoiceLaunch(): Boolean = loaded()?.simulateVoiceLaunch() ?: false
    override fun stop() = loaded()?.stop() ?: Unit
}
