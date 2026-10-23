package com.localfind.embed

import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.AudioPart
import com.google.mediapipe.tasks.core.Part
import com.localfind.core.settings.HardwareAccelerator

/**
 * What indexers and the searcher need from the on-device multimodal embedding model.
 *
 * Implemented by [Embedder] (one concrete model instance) and by the shared handle exposed by
 * [com.localfind.data.EngineManager], which always routes to the current instance. Components should
 * hold this interface rather than an [Embedder], because the engine may replace its instance
 * (e.g. when the hardware accelerator changes) while an indexing run or a search is in progress.
 */
interface EmbeddingModel {
    /** Concrete accelerator in use after resolving dynamic policies such as Smart mode. */
    val effectiveAccelerator: HardwareAccelerator

    fun embedText(text: String): FloatArray
    fun embedImage(bitmap: Bitmap): FloatArray
    fun embedAudio(samples: ShortArray): FloatArray

    /** Wraps 16 kHz mono PCM samples as a content part for [embedVideoWindow]. */
    fun audioPart(samples: ShortArray): AudioPart

    /** Embeds one video window as a single vector from interleaved time-marker, audio and frame parts. */
    fun embedVideoWindow(parts: List<Part>): FloatArray
}
