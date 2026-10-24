package com.localfind

import android.content.Context
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.embed.Embedder
import com.localfind.embed.ModelFiles
import com.localfind.embed.capabilitiesOf
import com.localfind.embed.loadOrder
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Opens an [Embedder] the way the app does: the load order of [accelerator] (NPU by default, the app's default
 * setting) on this device's installed models, first that loads wins.
 */
fun openEmbedder(context: Context, accelerator: HardwareAccelerator = HardwareAccelerator.NPU): Embedder {
    val models = ModelFiles.locate(context)
    return Embedder.open(context, models, accelerator.loadOrder(context, capabilitiesOf(context, models)))
}

/** The installed model for exactly [accelerator] (NPU model for NPU, universal for GPU/CPU), or null. */
fun modelFor(context: Context, accelerator: HardwareAccelerator): File? {
    val models = ModelFiles.locate(context)
    return if (accelerator == HardwareAccelerator.NPU) models.npu else models.universal
}

/**
 * Opens an [Embedder] on exactly [accelerator], without fallback, so a benchmark measures what it says. Skips the
 * test (assumption) when the model for that accelerator is not installed, e.g. GPU/CPU on an NPU-only deploy
 * (stage the universal model with `deploy-bundle.ps1 -WithUniversalModel`).
 */
fun openEmbedderOn(context: Context, accelerator: HardwareAccelerator): Embedder {
    val model = modelFor(context, accelerator)
    assumeTrue("No model installed for $accelerator", model != null)
    return Embedder(context, model!!, accelerator)
}
