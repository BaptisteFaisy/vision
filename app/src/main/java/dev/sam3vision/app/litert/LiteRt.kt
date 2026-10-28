package dev.sam3vision.app.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

/**
 * CompiledModel GPU→CPU (cascade du runtime : le repli est automatique, pas
 * codé ici). `env` nul : pas de cache compilateur (ouverture unique par
 * écran, les modèles vivent dans `filesDir`, pas dans les assets).
 */
fun openCompiledModel(
    modelPath: String,
    vararg accelerators: Accelerator = arrayOf(Accelerator.GPU, Accelerator.CPU),
): CompiledModel =
    CompiledModel.create(
        modelPath,
        CompiledModel.Options(*accelerators),
        null,
    )
