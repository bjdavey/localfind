package com.localfind.core.settings

/** Default dark mode state (false = light mode, true = dark mode). */
const val DEFAULT_DARK_MODE = false

/** Default theme mode (SYSTEM = follow system setting). */
val DEFAULT_THEME_MODE = ThemeMode.SYSTEM

/**
 * User preference for application visual theme.
 *
 * - [SYSTEM]: Automatically follow host device's dark/light system setting.
 * - [LIGHT]: Force light theme across all screens.
 * - [DARK]: Force dark theme across all screens.
 */
enum class ThemeMode(val title: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * Hardware accelerator choice for multimodal vision indexing (images and video keyframes).
 *
 * - [NPU]: Default on supported chipsets (Qualcomm Hexagon HTP, MediaTek NeuroPilot, Google Tensor).
 *   Runs on dedicated AI tensor cores without switching on screen/battery state so all embeddings
 *   remain in a consistent vector distribution. Automatically falls back to [SMART] on unsupported devices.
 * - [SMART]: Dynamically routes between GPU and CPU based on device state.
 *   Uses GPU when the screen is OFF and not in Battery Saver mode for maximum speed.
 *   Switches to CPU when the screen is ON or Battery Saver is ON to keep the UI smooth and cool.
 * - [GPU]: Always uses mobile GPU compute shaders for maximum indexing speed.
 * - [CPU]: Always uses CPU (XNNPack) to keep the device cool, save power, and avoid UI frame contention.
 */
enum class HardwareAccelerator(val title: String) {
    NPU("NPU (Best)"),
    SMART("Smart (Auto)"),
    GPU("GPU (Faster)"),
    CPU("CPU")
}

/** Default hardware accelerator for vision indexing (NPU on supported devices, falling back to Smart). */
val DEFAULT_HARDWARE_ACCELERATOR = HardwareAccelerator.NPU

/**
 * Pure policy that resolves the effective concrete hardware accelerator ([HardwareAccelerator.NPU],
 * [HardwareAccelerator.GPU], or [HardwareAccelerator.CPU]) from a user choice and device state.
 * Device state is passed as lambdas so only the facts a choice actually needs are queried.
 *
 * Policy:
 * - If [HardwareAccelerator.NPU] is chosen and the device supports NPU, returns [HardwareAccelerator.NPU]
 *   permanently without switching on screen/battery state.
 * - If [HardwareAccelerator.NPU] is chosen on an unsupported device, falls back to [HardwareAccelerator.SMART].
 * - If [HardwareAccelerator.GPU] or [HardwareAccelerator.CPU] is explicitly chosen, returns that choice.
 * - If [HardwareAccelerator.SMART], uses GPU only when the screen is off and Battery Saver is disabled.
 * - If the installed model is NPU-only (no [cpuGpuModelAvailable]) and the NPU can run it, every choice
 *   resolves to [HardwareAccelerator.NPU]: an NPU-compiled model's encoders are a single `DISPATCH_OP` that
 *   the GPU/CPU delegates cannot execute, so Smart/GPU/CPU would fail at the first embedding.
 */
fun HardwareAccelerator.resolve(
    npuAvailable: () -> Boolean,
    screenOn: () -> Boolean,
    powerSaveMode: () -> Boolean,
    cpuGpuModelAvailable: () -> Boolean = { true }
): HardwareAccelerator {
    if (this != HardwareAccelerator.NPU && !cpuGpuModelAvailable() && npuAvailable()) {
        return HardwareAccelerator.NPU
    }
    if (this == HardwareAccelerator.NPU) {
        return if (npuAvailable()) {
            HardwareAccelerator.NPU
        } else {
            HardwareAccelerator.SMART.resolve(npuAvailable, screenOn, powerSaveMode)
        }
    }
    if (this != HardwareAccelerator.SMART) return this
    return if (!screenOn() && !powerSaveMode()) HardwareAccelerator.GPU else HardwareAccelerator.CPU
}

/** Default minimum cosine similarity score required for search results (tuned for NPU embeddings). */
const val DEFAULT_SIMILARITY_THRESHOLD = 0.3f

/** Default maximum number of top nearest-neighbor search results returned. */
const val DEFAULT_TOP_K = 10

/** Default passage character length when chunking text and Markdown documents. */
const val DEFAULT_TEXT_CHUNK_SIZE = 500

/** Default character overlap between consecutive sliding text chunks. */
const val DEFAULT_TEXT_CHUNK_OVERLAP = 100

/** Default fixed audio chunk duration in seconds. */
const val DEFAULT_AUDIO_WINDOW_SECONDS = 10

/** Default video sliding window duration in seconds. */
const val DEFAULT_VIDEO_WINDOW_SECONDS = 5

/** Default number of frames sampled and embedded per video window. */
const val DEFAULT_VIDEO_FRAMES_PER_WINDOW = 2

/** Default maximum width or height in pixels for decoding images and video frames. */
const val DEFAULT_MAX_IMAGE_DIMENSION = 512

/** Default whether to decode and embed audio tracks in video files (false = vision-only fastest). */
const val DEFAULT_VIDEO_INCLUDE_AUDIO = false

/** Default whether to skip visually redundant consecutive video windows. */
const val DEFAULT_VIDEO_SKIP_SIMILAR = true

/** Default visual similarity threshold (0.0 .. 1.0) above which consecutive scenes are skipped. */
const val DEFAULT_VIDEO_SIMILARITY_THRESHOLD = 0.85f

/** Default whether folder import is enabled (hidden by default unless enabled from advanced settings). */
const val DEFAULT_ENABLE_FOLDER_IMPORT = false

/**
 * Configuration model holding all tunable variables for searching and indexing in LocalFind.
 *
 * All settings run 100% offline on-device and are persisted by the platform settings store.
 *
 * @property themeMode Visual theme mode preference ([ThemeMode.SYSTEM], [ThemeMode.LIGHT], or [ThemeMode.DARK]).
 * @property darkMode Whether dark theme is enabled across the application.
 * @property similarityThreshold Minimum cosine similarity cutoff score (0.0 .. 1.0). Matches below
 *                               this threshold are filtered out from search results.
 * @property topK Maximum number of nearest-neighbor results retrieved per search query.
 * @property textChunkSize Preferred character length per text/markdown chunk.
 * @property textChunkOverlap Sliding overlap characters between consecutive text chunks.
 * @property audioWindowSeconds Duration of each audio slice embedded by the multimodal model.
 * @property videoWindowSeconds Duration of each video window sliding across the media.
 * @property videoFramesPerWindow Number of visual frames extracted and embedded per video window.
 * @property maxImageDimension Maximum dimension (width/height px) for subsampling image/video inputs.
 * @property videoIncludeAudio Whether to decode and embed audio tracks in videos alongside visual keyframes.
 * @property videoSkipSimilarChunks Whether to skip consecutive video windows with visually redundant content.
 * @property videoSimilarityThreshold Similarity cutoff (0.5 .. 1.0) for detecting redundant video windows.
 * @property enableFolderImport Whether importing entire directories is exposed in the gallery UI.
 * @property accelerator Preferred hardware accelerator for vision indexing ([HardwareAccelerator.NPU], [HardwareAccelerator.GPU], [HardwareAccelerator.CPU] or the dynamic [HardwareAccelerator.SMART]).
 */
data class AppSettings(
    val themeMode: ThemeMode = DEFAULT_THEME_MODE,
    val darkMode: Boolean = DEFAULT_DARK_MODE,
    val similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    val topK: Int = DEFAULT_TOP_K,
    val textChunkSize: Int = DEFAULT_TEXT_CHUNK_SIZE,
    val textChunkOverlap: Int = DEFAULT_TEXT_CHUNK_OVERLAP,
    val audioWindowSeconds: Int = DEFAULT_AUDIO_WINDOW_SECONDS,
    val videoWindowSeconds: Int = DEFAULT_VIDEO_WINDOW_SECONDS,
    val videoFramesPerWindow: Int = DEFAULT_VIDEO_FRAMES_PER_WINDOW,
    val maxImageDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION,
    val videoIncludeAudio: Boolean = DEFAULT_VIDEO_INCLUDE_AUDIO,
    val videoSkipSimilarChunks: Boolean = DEFAULT_VIDEO_SKIP_SIMILAR,
    val videoSimilarityThreshold: Float = DEFAULT_VIDEO_SIMILARITY_THRESHOLD,
    val enableFolderImport: Boolean = DEFAULT_ENABLE_FOLDER_IMPORT,
    val accelerator: HardwareAccelerator = DEFAULT_HARDWARE_ACCELERATOR
) {
    /**
     * Returns a copy of [AppSettings] with all values clamped to valid and safe ranges.
     */
    fun sanitized(): AppSettings {
        val safeTextChunkSize = textChunkSize.coerceIn(100, 5000)
        return copy(
            similarityThreshold = similarityThreshold.coerceIn(0.0f, 1.0f),
            topK = topK.coerceIn(1, 100),
            textChunkSize = safeTextChunkSize,
            textChunkOverlap = textChunkOverlap.coerceIn(0, (safeTextChunkSize / 2).coerceAtLeast(0)),
            audioWindowSeconds = audioWindowSeconds.coerceIn(1, 60),
            videoWindowSeconds = videoWindowSeconds.coerceIn(1, 60),
            videoFramesPerWindow = videoFramesPerWindow.coerceIn(1, 10),
            maxImageDimension = maxImageDimension.coerceIn(128, 2048),
            videoSimilarityThreshold = videoSimilarityThreshold.coerceIn(0.5f, 1.0f)
        )
    }
}
