package dev.sam3vision.core.settings

/**
 * Région de la GPU SAM 3.1 interrogée par l'app : « mode US » = carte aux États-Unis.
 *
 * Ce réglage ne fait qu'**élire** l'endpoint appelé : il n'allume aucune carte. La règle
 * Modal reste « une seule GPU à la fois », toutes régions confondues — l'app US doit être
 * déployée et réveillée à la demande (`~/bin/sam3-gpu-us on --reason …`).
 */
enum class SamRegion(
    /** Libellé des Réglages : tient sur une ligne, en pleine largeur. */
    val label: String,
    /** Code court (« EU », « US ») pour les pastilles et les journaux. */
    val code: String,
    /** Endpoint de l'app déployée dans cette région (surchargé par `local.properties`). */
    val presetBaseUrl: String,
) {
    Eu(
        label = "GPU en Europe (eu)",
        code = "EU",
        presetBaseUrl = "https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run",
    ),
    Us(
        label = "GPU aux États-Unis (us)",
        code = "US",
        presetBaseUrl = "https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run",
    );

    companion object {
        /** Nom persisté (`sam_region`) → région ; valeur inconnue ou absente ⇒ [Eu]. */
        fun fromName(name: String?): SamRegion =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Eu

        /**
         * Vrai si [url] n'est que le preset d'une région : c'est une valeur injectée par le
         * build ou héritée d'une ancienne version, pas un choix manuel — elle ne doit donc
         * pas figer la région.
         */
        fun isPreset(url: String): Boolean {
            val clean = url.trim().trimEnd('/')
            return clean.isNotEmpty() && entries.any { it.presetBaseUrl.trimEnd('/') == clean }
        }
    }
}

/**
 * Endpoint SAM réellement appelé, dans cet ordre : saisie manuelle (hors preset) →
 * preset de la région choisie → URL injectée au build pour cette région.
 */
fun Settings.resolveSamEndpoint(buildEu: String = "", buildUs: String = ""): String {
    val manual = samBaseUrl.trim()
    if (manual.isNotEmpty() && !SamRegion.isPreset(manual)) return manual
    val injected = if (samRegion == SamRegion.Us) buildUs else buildEu
    return injected.trim().ifBlank { samRegion.presetBaseUrl }
}
