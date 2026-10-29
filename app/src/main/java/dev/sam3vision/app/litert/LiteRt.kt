package dev.sam3vision.app.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

/**
 * CompiledModel GPU→CPU (cascade du runtime : le repli est automatique, pas
 * codé ici). `env` nul : pas de cache compilateur (ouverture unique par
 * écran, les modèles vivent dans `filesDir`, pas dans les assets).
 */
fun openCompiledModel(modelPath: String): CompiledModel =
    CompiledModel.create(
        modelPath,
        CompiledModel.Options(Accelerator.GPU, Accelerator.CPU),
        null,
    )
