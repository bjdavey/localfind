# Architecture

Technical decisions for the Android MVP. Scope is defined in [PRD.md](PRD.md).

## 1. Overview

```text
           ┌──────────── Compose UI (Library / Search / Viewer) ────────────┐
           │                                                                │
  Pick files (SAF)                                                  Query text
           │                                                                │
           ▼                                                                ▼
     Indexer ──> Extract chunks ──> EmbeddingGemma 2 (MediaPipe) <──── Search
                                              │                             │
                                              ▼                             │
                                SqliteVectorStore (MediaPipe) ──────────────┘
                                                                 cosine top-K
```

One model embeds **every** chunk type (text, image, audio) into one shared 768-dim vector space.
The text query uses the same model, so a text query can match an image, an audio clip, or a video frame.

## 2. Tech Stack

| Area | Choice |
| :--- | :--- |
| Language / UI | Kotlin, Jetpack Compose, single Activity |
| Min / target SDK | 31 / 37 |
| Embedding model | EmbeddingGemma 2, **740M multimodal** (text + image + audio) |
| Inference | MediaPipe Tasks on Android (`UniversalEmbedder`, LiteRT under the hood) |
| Hardware Acceleration | Multi-tier vision acceleration: Hardware NPU (Qualcomm Hexagon HTP, MediaTek, Tensor) with automatic GPU (OpenCL/Vulkan) & CPU (XNNPack) fallback |
| Storage | `SqliteVectorStore` (MediaPipe, SQLite-backed) |
| Vector search | `SqliteVectorStore` cosine top-K search |
| PDF text | PdfBox-Android |
| PDF viewing | AndroidX `PdfViewerFragment` (Jetpack PDF) with an Android `PdfRenderer` fallback view |
| Image / thumbnails | Coil (`AsyncImage`), `ImageDecoder` |
| Audio / video | Media3 (ExoPlayer, UI `PlayerView`), `MediaMetadataRetriever`, `MediaExtractor` / `MediaCodec` |
| Icons / Symbols | Google Material Symbols (Vector Drawables in `res/drawable/` via Compose `painterResource`) |
| Concurrency | Kotlin coroutines, AndroidX WorkManager |

## 3. Model Delivery & Chipset Support

- **100% Offline & Standalone:** Inference runs purely on-device. LocalFind does **not** depend on Google AI Edge Gallery or any external APK. The universal CPU/GPU runtime ships in the base app; the vendor NPU runtimes and the model are delivered by Google Play for the specific device (see 3.1). Production bundles target `arm64-v8a` (with `x86_64` supported for local Android Emulator builds).
- **Universal Base Model:** `embeddinggemma-2-740m.litertlm` (~485 MB) operates universally on any Android device across CPU (XNNPack) and GPU (OpenCL / Vulkan).
- **Pre-compiled Hardware NPU Models:** Hosted in the official Hugging Face repository [`litert-community/embeddinggemma-2-740m-litert-lm`](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main):
  - Qualcomm Snapdragon 8 Gen 3: `embeddinggemma-2-740m_Qualcomm_SM8650.litertlm` (Hexagon HTP v75)
  - Qualcomm Snapdragon 8 Gen 2: `embeddinggemma-2-740m_Qualcomm_SM8550.litertlm` (Hexagon HTP v73)
  - Qualcomm Snapdragon 8 Elite: `embeddinggemma-2-740m_Qualcomm_SM8750.litertlm` (Hexagon HTP v79)
  - Qualcomm Snapdragon 8 Elite Gen 5: `embeddinggemma-2-740m_Qualcomm_SM8850.litertlm` (Hexagon HTP v81)
  - MediaTek Dimensity 9400: `embeddinggemma-2-740m_MediaTek_MT6991.litertlm`
  - MediaTek Dimensity Next-Gen: `embeddinggemma-2-740m_MediaTek_MT6993.litertlm`
  - Google Tensor G5: `embeddinggemma-2-740m_Google_Tensor_G5.litertlm`
  - Google Tensor G6: `embeddinggemma-2-740m_Google_Tensor_G6.litertlm`
- **Dynamic SoC Resolution (`SocDetector`):** LocalFind dynamically queries device platform properties (`Build.SOC_MANUFACTURER`, `Build.SOC_MODEL`, `Build.HARDWARE`) at runtime. It derives the exact matching chipset model file name without hardcoding phone names or chipsets; `ModelFiles` then resolves and stages that file.

### 3.1 Hardware Support Matrix & Execution Status

Running on hardware NPUs requires both the **pre-compiled model file** (delivered via Google Play AI Pack `:ai_pack:embedding_model` with `#group_<Vendor>_<Model>` device targeting) and the vendor's **native driver binaries (`.so`)** (delivered via Play Feature Delivery `:litert_npu_runtime_libraries:*`):

How the native libraries are packaged: the `libLiteRtDispatch_*.so` dispatch libraries come from the LiteRT 2.3.0 Maven artifacts (`litert-npu-runtime-*`), pulled in by each Feature Delivery module. The vendor SDK libraries (Qualcomm QAIRT `libQnn*`, MediaTek NeuroPilot) are downloaded into those modules by `fetch_qualcomm_library.sh` / `fetch_mediatek_library.sh`. Each phone installs only the modules matching its chipset. (Decision: keep Feature Delivery instead of putting every vendor's libraries into the base APK.)

| Platform / Chipset | Marketing Name | Hardware Target | Delivery Module(s) | Native Binary Requirement |
| :--- | :--- | :--- | :--- | :--- |
| **Qualcomm SM8550** | Snapdragon 8 Gen 2 (S23 series, OnePlus 11) | Hexagon HTP v73 NPU | `qualcomm_runtime_common` + `htp` + `v73` | Maven `litert-npu-runtime-qualcomm` (`libLiteRtDispatch_Qualcomm.so`) + QAIRT `libQnnSystem.so`, `libQnnHtp.so`, `libQnnHtpV73Stub.so`, `libQnnHtpV73Skel.so` (`fetch_qualcomm_library.sh`). |
| **Qualcomm SM8650** | Snapdragon 8 Gen 3 (S24 Ultra, Fold 6, OnePlus 12) | **Hexagon HTP v75 NPU** | `qualcomm_runtime_common` + `htp` + `v75` | Verified 1.7x speedup over GPU. Bundled in `qualcomm_runtime_v75` and installed automatically via `deploy-bundle.ps1` / `deploy-bundle.sh`. |
| **Qualcomm SM8750** | Snapdragon 8 Elite / Gen 4 (S25 series, OnePlus 13) | Hexagon HTP v79 NPU | `qualcomm_runtime_common` + `htp` + `v79` | Maven `litert-npu-runtime-qualcomm` + QAIRT `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so` (`fetch_qualcomm_library.sh`). |
| **Qualcomm SM8850** | Snapdragon 8 Elite Gen 5 (Galaxy S26 series, OnePlus 14, Xiaomi 16) | Hexagon HTP v81 NPU | `qualcomm_runtime_common` + `htp` + `v81` | Maven `litert-npu-runtime-qualcomm` + QAIRT `libQnnHtpV81Stub.so`, `libQnnHtpV81Skel.so` (`fetch_qualcomm_library.sh`). |
| **MediaTek MT6991** | Dimensity 9400 (Vivo X200, Find X8) | MediaTek APU v8 | `mediatek_runtime_common` + `v8` | Maven `litert-npu-runtime-mediatek` (`libLiteRtDispatch_MediaTek.so`) + NeuroPilot `libneuronusdk_adapter.mtk.so` (`fetch_mediatek_library.sh`). |
| **MediaTek MT6993** | Dimensity 9500 (Vivo X300, Find X9) | MediaTek APU v9 | `mediatek_runtime_common` + `v9` | Maven `litert-npu-runtime-mediatek` (`libLiteRtDispatch_MediaTek.so`) + NeuroPilot `libneuronusdk_adapter.so` (`fetch_mediatek_library.sh`). |
| **Google Tensor G5/G6** | Pixel 10 / Pixel 11 | Google EdgeTPU | `google_tensor_runtime` | Maven `litert-npu-runtime-google-tensor` (`libLiteRtDispatch_GoogleTensor.so`). |
| **Universal (All Phones)** | Any Qualcomm, MediaTek, Tensor, Exynos | **GPU (OpenCL / Vulkan)** | Base `:app` | Uses system GPU driver (`libOpenCL.so` or Vulkan driver). |
| **Universal (All Phones)** | Any ARM64 device | **CPU (XNNPack FP16)** | Base `:app` | XNNPack is included in the MediaPipe / LiteRT-LM runtime. |

- **Default NPU Policy & Modality Delegates:**
  - `HardwareAccelerator.NPU` is the default setting on supported devices and never auto-switches on screen/battery state so all indexed vectors stay in a consistent vector space.
  - **Modality Delegates on NPU & GPU:**
    - **Vision:** `setVisionDelegate(Delegate.NPU / Delegate.GPU)` executes image and video frame visual encoding on hardware NPU (e.g. Qualcomm Hexagon HTP) or GPU (OpenCL).
    - **Text (PDF & Documents):** `setTextDelegate(Delegate.NPU / Delegate.GPU)` executes text embedding for PDF page passages, text files, and search queries on hardware NPU or GPU. Text and vision always share one accelerator (no mixed modes).
    - **Audio:** `setAudioDelegate(Delegate.CPU)` remains on CPU as LiteRT audio mel-spectrogram STFT frontends require CPU execution. Even NPU models keep a plain XNNPack audio encoder.
  - **Models per accelerator:** a chipset NPU model runs ONLY on the NPU: its text/vision encoders are an NPU-only `DISPATCH_OP` (verified on SM8650: it loads on CPU/GPU, then the first embedding fails). GPU and CPU need the universal model.
  - **Capabilities:** `ModelFiles.locate` finds the installed models once (startup, "Retry Loading", after a download); `Capabilities(npu, cpuGpu)` = NPU runtime + NPU model / universal model. Settings disables what the device cannot run: NPU without NPU support (choice falls back to **`SMART`**: `GPU` when screen is off and Battery Saver is off, `CPU` when screen is on), Smart/GPU/CPU with only an NPU model (every choice runs on NPU).
- **Deployment & Multi-Device Testing Strategy:**
  - **Google Play:** Google Play AI Pack (`:ai_pack:embedding_model`, `install-time` delivery: the model is present at first launch and is copied once from assets into `filesDir` under its real name, chipset NPU name on NPU device groups, which costs one extra copy of the model on disk) + Play Feature Delivery (`:litert_npu_runtime_libraries:*`, `install-time` split APKs conditioned on `device_targeting_configuration.xml`, the same groups as the model).
  - **Recovery download:** NPU devices get only their NPU model. If it cannot be loaded (and no universal model is installed), the model error offers "Download compatible model": the on-demand AI pack `:ai_pack:universal_model` (universal model, ~485 MB, untargeted) is fetched by Play (`com.google.android.play:ai-delivery`, `embed/RecoveryModel.kt`) and used in place. The app itself makes no network calls and has no network permission; it only works for Play installs (or bundletool `--local-testing`).
  - **Local Development & Multi-Device Testing (`deploy-bundle.ps1` / `deploy-bundle.sh`):** Automates Google's official `bundletool` workflow. Detects the connected device SoC via ADB, builds the App Bundle (`:app:bundleDebug`), extracts the matching split APKs for the device's targeting group (Qualcomm SM8550/SM8650/SM8750/SM8850, MediaTek MT6991/MT6993, Google Tensor G5/G6), installs base and NPU splits, and launches the app.
  - **Fallback Model Staging:** When models are not installed via Play AI Pack, `ModelFiles` automatically stages them to `context.filesDir` from `/data/local/tmp/` (also `/sdcard/Download` and `/sdcard` for development). The deploy scripts stage only the chipset model on NPU devices, like Play; `-WithUniversalModel` also stages the universal one, `-WithRecoveryPack` tests the recovery download with `--local-testing`.

## 4. File Access: Link, Don't Copy

- Files are picked with the system picker (`ACTION_OPEN_DOCUMENT`, multi-select) or folder picker (`ACTION_OPEN_DOCUMENT_TREE`).
- The app calls `takePersistableUriPermission` and stores **only the URI** plus index data.
- No storage permission is needed, and picked files are not duplicated.
- **Exception, content shared from other apps** (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`): shared files and notes are copied into app storage (`filesDir/shared_imports/`) because the sender's URI grant is temporary. Removing such a file from the library deletes the copy.
- If a file is moved or deleted, the app detects it when the user opens it, retries it or searches inside it, and offers to remove it from the library.

## 5. Indexing

Each file is split into **chunks**. Each chunk has one embedding and one **location** that is used to open the result.

| Type | Chunk | Location |
| :--- | :--- | :--- |
| Text (`.txt`, `.md`) | Passage (~paragraphs, a few hundred tokens) | Character offset |
| PDF | Text of one page (long pages are split) | Page number |
| Image | Whole image | - |
| Audio | Fixed time window (e.g. 10 s) | Start time (ms) |
| Video | Sliding window (sampled frames + optional 16 kHz audio) | Start time (ms) |

- Video indexing uses sliding time windows with hardware-downsampled sync keyframes (`256 px`), luminance-based visual redundancy skipping (`85%` similarity threshold), adaptive thermal pacing (`PowerManager`), and optional concurrent 16 kHz audio decoding.
- Indexing runs in a background WorkManager worker (`IndexingWorker`, foreground notification via `IndexingNotifications`), surviving app process death and automatically resuming unindexed files from the last completed chunk (`IndexingResumeManager`). The queue is controlled through `IndexingQueue` (enqueue, pause, resume, cancel, cancel one file); the user can pause, resume and cancel from the app. `POST_NOTIFICATIONS` is not requested, so on Android 13+ the foreground notification and its action buttons are hidden and the in-app banner is the only control.
- Progress is tracked per file with live percentage and spinner indicators on gallery tiles and in the top indexing banner.
- A file found still marked INDEXING after a restart means the process died while indexing it. Each such interruption is counted (`IndexingResumeManager`); after 3 (`IndexingAttempts`) the file is marked failed instead of being retried, so a file that crashes the indexer cannot crash the app on every start.

## 6. Data Model (SqliteVectorStore)

Data is stored locally using MediaPipe's `SqliteVectorStore` (`RetrievalRecord`):

- **id**: Chunk ID `"<fileUri>_<chunkIndex>"` (`ChunkMetadata.chunkId`)
- **content**: Passage text / snippet (`TextPart`)
- **embeddings**: 768-dimensional float vector (`float[768]`)
- **metadata**: Key-value map built by `ChunkMetadata` (keys and `FileKind` ids are persisted, so they must not change):
  - `fileId` and `uri`: both hold the source file's URI (search reads `uri` first, deletion matches on `fileId`)
  - `name`: File name
  - `type`: Category id of the `FileKind` enum (`text`, `pdf`, `image`, `audio`, `video`)
  - `location`: Location offset (char offset, page number, or ms timestamp)

- Removing a file deletes all chunks matching the file's `fileId` via `EmbeddingStore.deleteByFileUri`.
- **Embedding space:** `FileStore` records per file an `EmbeddingSpace` id (activation precision + model file size + whether text / vision ran on the NPU; GPU and CPU count as one class). A file whose id differs from the loaded embedder's is offered for re-analysis through a banner in the library. Files indexed before the id existed have none and are treated as compatible.

## 7. Search

1. Embed the query text using `Embedder`.
2. Query `SqliteVectorStore.search(queryEmbedding, topK)` to find nearest neighbors via cosine similarity (supporting optional `fileId` or modality `type` pre-filtering, applied before the nearest-neighbor retrieval so unrelated modalities never use up the top-K budget).
   The mapping and ranking of hits (`ResultRanking`) and the vector math (`VectorMath`) are pure code in `core/search`. **Search by image** embeds a query image with the same model and runs the same retrieval.
3. Filter results that meet or exceed the similarity threshold (default `0.30`, tuned for NPU embeddings; configurable in Settings).
4. Return the top-K chunks sorted by descending similarity score.

## 8. Opening Results

| Type | Opens in |
| :--- | :--- |
| Text | In-app text viewer, scrolled to the passage |
| PDF | In-app PDF viewer (Jetpack PDF, `PdfRenderer` fallback) at the page |
| Image | In-app image viewer (Coil) |
| Audio / Video | In-app Media3 ExoPlayer (`PlayerView`), `seekTo(location)` |

## 9. Settings & Tuning Variables

All variables are persisted locally using native Android `SharedPreferences` (`SettingsStore` in `data/`; the settings model and defaults are `AppSettings` in `core/settings/`):

| Area | Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| Search | Similarity Threshold | `0.30` | Minimum cosine similarity score required for matches |
| Search | Top-K | `10` | Maximum different files shown, and maximum grouped matches per file |
| Indexing | Text Chunk Size | `500` chars | Passage character length for text, Markdown, and PDF |
| Indexing | Text Chunk Overlap | `100` chars | Sliding overlap characters between consecutive passages |
| Indexing | Audio Window Duration | `10` s | Duration of each fixed audio chunk |
| Indexing | Video Window Duration | `5` s | Duration of each video sliding window |
| Indexing | Video Frames / Window | `2` frames | Sampled JPEG frames per video window |
| Indexing | Video Include Audio | `false` | Whether to decode and embed audio tracks alongside video keyframes |
| Indexing | Video Skip Similar Chunks | `true` | Skip redundant consecutive video windows (`>= 85%` luminance similarity) |
| Indexing | Max Image Dimension | `512` px | Maximum dimension for image decoding (video frames use `256` px hardware downsampling) |
| Acceleration | Hardware Accelerator | `NPU` | Hardware accelerator policy: `NPU` (Default on supported SoCs with NPU vision + NPU text), `SMART` (Auto fallback on unsupported devices), `GPU` (Faster), `CPU` (Cool & Smooth UI) |

## 10. Offline Guarantee

- No `INTERNET` permission. Any merged permission from libraries is removed with `tools:node="remove"`.
- No analytics, crash reporting, or cloud SDKs. MediaPipe bundles Google's data-transport (usage logging) library; its backend discovery service is removed in `AndroidManifest.xml`, so logged events are dropped locally and nothing is queued for upload.

## 11. Project Layout

Single Gradle module (`:app`) plus the Play delivery modules. Code is split into a **platform-independent
`core`** and Android adapters around it:

```text
android/app/src/main/
├── res/drawable/   # Google Material Symbols Vector Drawables (ic_*.xml)
└── java/.../localfind/
    ├── MainActivity.kt            # Activity + wiring of the screens
    ├── core/                      # Pure Kotlin: no android.*, androidx.*, MediaPipe or PDFBox imports
    │   ├── model/       # ImportedFile, Folder, IndexingStatus, ModelStatus, IndexResult, IndexingProgress,
    │   │                #   SearchResult, FileKind/FileTypes, ChunkMetadata, EmbeddingModelSpec
    │   ├── settings/    # AppSettings, ThemeMode, HardwareAccelerator (+ accelerator resolution policy)
    │   ├── chunking/    # TextChunker, PdfChunker, AudioChunker, VideoChunker
    │   ├── indexing/    # IndexingResumeLogic, ImageSizing, IndexingPacing
    │   ├── search/      # VectorMath, ResultRanking
    │   ├── library/     # GalleryCategory, LibraryFilters, PreviewMath
    │   ├── text/        # TextViewerHelper, PdfPageMath
    │   └── format/      # SearchResultFormatter, formatFileSize, FileNames
    ├── data/            # Android adapters: FileStore (SQLite), SettingsStore (SharedPreferences), FileImporter /
    │                    #   ShareImporter / FileOpener (SAF, intents), EmbeddingStore (SqliteVectorStore), EngineManager
    ├── embed/           # MediaPipe UniversalEmbedder wrapper (Embedder), SocDetector (chip + NPU runtime), ModelFiles (model file staging), AcceleratorResolver
    ├── index/           # Indexer + per-modality indexers (document/, image/, audio/, video/), IndexingWorker,
    │                    #   IndexingQueue, IndexingNotifications, IndexingState, IndexingResumeManager, IndexingActionReceiver
    ├── search/          # Searcher (EmbeddingStore + Embedder adapter around core/search)
    └── ui/
        ├── main/        # LibraryState, SearchState (state holders), MainDialogs
        ├── gallery/     # GalleryScreen and its parts (top bars, banners, empty states), tiles, dialogs, ThumbnailHelper
        ├── search/      # SearchScreen, SearchResultCard, TargetFileBanner, VisualQueryBar
        ├── viewer/      # ViewerOverlayHost, Image/Media/Pdf/TextViewerScreen, PdfViewerHelper
        ├── settings/    # SettingsScreen, SettingsDialogs, SettingsComponents
        └── theme/       # Color, Theme
```

- `core` may only import the Kotlin/Java standard library and other `core` packages. `CoreIsPureTest` enforces this, so the logic can be reused when porting to other platforms later.
- State of the main screen is held in plain `remember`-ed holders (`LibraryState`, `SearchState`, `ViewerState`), not a ViewModel.

## 12. Technical Invariants

- **Single Model Instance:** Never bypass `EngineManager`. The ~485MB `EmbeddingGemma 2` model must only be loaded once to prevent Out-Of-Memory (OOM) crashes on mobile devices.
- **Stable Embedder Handle:** `EngineManager.getEmbedder` returns an `EmbeddingModel` handle that always routes to the current `Embedder` under a read/write lock. Replacing the instance (accelerator change, Smart mode switch) takes the write lock and waits for in-flight inference. Hold the `EmbeddingModel`, never a raw `Embedder`, and never close an `Embedder` that `EngineManager` owns.
- **Background Threading:** Never perform file extraction, PDF parsing, audio decoding, embedding, or database writes on the Android Main (UI) thread; always use dispatcher.
- **Hardware Acceleration Linking:** Vendor libraries (`libcdsprpc.so` for Qualcomm Hexagon FastRPC and `libOpenCL.so` for GPU) are declared in `AndroidManifest.xml` with `android:required="false"`, ensuring that devices lacking vendor drivers install and run normally.
- **Dynamic Hardware Fallback:** One decision point. `EngineManager` builds the load order with the pure `HardwareAccelerator.loadOrder` (`core/settings/AcceleratorPlan.kt`): the policy's choice (`HardwareAccelerator.resolve`) first, then the other accelerators the device can run (NPU > GPU > CPU), never one bound to fail. `Embedder.open` tries them in order (NPU retried once); `Embedder` itself loads exactly one model on one accelerator and verifies it with a warmup text embedding, because a model can load on a delegate it cannot execute. The accelerator reported (`effectiveAccelerator`) is the one that actually loaded.
- **Zero-Copy Symlink Dispatch Staging:** LiteRT-LM's C++ `litert_dispatch.cc` searches `context.filesDir` for `libLiteRtDispatch_*.so` and vendor QNN/NeuroPilot libraries. `SocDetector.ensureDispatchLibraries` creates zero-byte POSIX symlinks (`Os.symlink`) from `context.applicationInfo.nativeLibraryDir` into `context.filesDir`, avoiding 21 MB of duplicate `.so` copies on disk.
- **Portable Core:** Keep platform APIs out of `core/`. New pure logic goes there with a unit test; Android-specific code stays in the adapter packages.
