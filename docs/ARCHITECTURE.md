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
| PDF viewing | Android `PdfRenderer` |
| Image / thumbnails | Coil (`AsyncImage`), `ImageDecoder` |
| Audio / video | Media3 (ExoPlayer, UI `PlayerView`), `MediaMetadataRetriever`, `MediaExtractor` / `MediaCodec` |
| Icons / Symbols | Google Material Symbols (Vector Drawables in `res/drawable/` via Compose `painterResource`) |
| Concurrency | Kotlin coroutines, AndroidX WorkManager |

## 3. Model Delivery & Chipset Support

- **100% Offline & Standalone:** Inference runs purely on-device. LocalFind does **not** depend on Google AI Edge Gallery or any external APK. All required native runtimes are bundled inside LocalFind.
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
- **Dynamic SoC Resolution (`SocDetector`):** LocalFind dynamically queries device platform properties (`Build.SOC_MANUFACTURER`, `Build.SOC_MODEL`, `Build.HARDWARE`) at runtime. It automatically selects and stages the exact matching chipset model file without hardcoding phone names or chipsets.

### 3.1 Hardware Support Matrix & Execution Status

Running on hardware NPUs requires both the **pre-compiled model file** and the vendor's **native driver binaries (`.so`)**:

| Platform / Chipset | Marketing Name | Hardware Target | Status in LocalFind | Native Binary Requirement |
| :--- | :--- | :--- | :--- | :--- |
| **Qualcomm SM8550** | Snapdragon 8 Gen 2 (S23 series, OnePlus 11) | Hexagon HTP v73 NPU | **Model supported**; falls back to GPU OpenCL | Requires Qualcomm LiteRT dispatch, `libQnnHtpV73Stub.so`, `libQnnHtpV73Skel.so`, `libQnnHtp.so`, and `libQnnSystem.so`; not bundled. |
| **Qualcomm SM8650** | Snapdragon 8 Gen 3 (S24 Ultra, Fold 6, OnePlus 12) | **Hexagon HTP v75 NPU** | **Supported Now** (Verified 1.7x speedup over GPU) | `libLiteRtDispatch_Qualcomm.so`, `libQnnHtpV75Stub.so`, `libQnnHtpV75Skel.so`, `libQnnHtp.so`, and `libQnnSystem.so` bundled in `jniLibs`. |
| **Qualcomm SM8750** | Snapdragon 8 Elite / Gen 4 (S25 series, OnePlus 13) | Hexagon HTP v79 NPU | **Model supported**; falls back to GPU OpenCL | Requires Qualcomm LiteRT dispatch, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so`, `libQnnHtp.so`, and `libQnnSystem.so`; not bundled. |
| **Qualcomm SM8850** | Snapdragon 8 Elite Gen 5 (Galaxy S26 series, OnePlus 14, Xiaomi 16) | Hexagon HTP v81 NPU | **Model supported**; falls back to GPU OpenCL | Requires Qualcomm LiteRT dispatch, `libQnnHtpV81Stub.so`, `libQnnHtpV81Skel.so`, `libQnnHtp.so`, and `libQnnSystem.so`; not bundled. |
| **MediaTek MT6991** | Dimensity 9400 (Vivo X200, Find X8) | MediaTek APU | **Model supported**; falls back to GPU OpenCL | Requires `libLiteRtDispatch_MediaTek.so` and the device's Neuron adapter; not bundled. |
| **MediaTek MT6993** | Dimensity Next-Gen (Vivo X300, Find X9) | MediaTek APU | **Model supported**; falls back to GPU OpenCL | Requires `libLiteRtDispatch_MediaTek.so` and the device's Neuron adapter; not bundled. |
| **Google Tensor G5/G6** | Pixel 10 / Pixel 11 | Google EdgeTPU | **Model supported**; falls back to GPU OpenCL | Requires `libLiteRtDispatch_GoogleTensor.so` and the device's Edge TPU runtime; not bundled. |
| **Universal (All Phones)** | Any Qualcomm, MediaTek, Tensor, Exynos | **GPU (OpenCL / Vulkan)** | **Supported Now** across all devices | Requires a compatible system GPU driver (`libOpenCL.so` or Vulkan driver); supplied by the device, not bundled. |
| **Universal (All Phones)** | Any ARM64 or x86_64 device | **CPU (XNNPack FP16)** | **Supported Now** across all devices | No vendor binary required; XNNPack is included in the MediaPipe Tasks runtime. |

- **Graceful Multi-Tier Fallback:** If a device-specific NPU model or native driver is missing, or if NPU delegate initialization fails on unsupported silicon, LocalFind seamlessly cascades to **GPU** vision acceleration, and subsequently to **CPU** (XNNPack). Text and audio modalities run reliably on CPU to eliminate driver overhead.
- **Deployment Strategy:**
  - **Release:** Play Asset Delivery **install-time** asset pack.
  - **Development:** `adb push <model-file> /data/local/tmp/` (auto-detected and staged into app storage).

## 4. File Access: Link, Don't Copy

- Files are picked with the system picker (`ACTION_OPEN_DOCUMENT`, multi-select) or folder picker (`ACTION_OPEN_DOCUMENT_TREE`).
- The app calls `takePersistableUriPermission` and stores **only the URI** plus index data.
- No storage permission is needed, and files are not duplicated.
- If a file is moved or deleted, it is shown as **missing** in the Library, and the user can remove it.

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
- Indexing runs in a background WorkManager worker (`IndexingWorker`), surviving app process death and automatically resuming unindexed files from the last completed chunk (`IndexingResumeManager`).
- Progress is tracked per file with live percentage and spinner indicators on gallery tiles and in the top indexing banner.

## 6. Data Model (SqliteVectorStore)

Data is stored locally using MediaPipe's `SqliteVectorStore` (`RetrievalRecord`):

- **id**: Chunk ID (e.g. `"<fileId>_<chunkIndex>"`)
- **content**: Passage text / snippet (`TextPart`)
- **embeddings**: 768-dimensional float vector (`float[768]`)
- **metadata**: Key-value map including:
  - `fileId`: Identifier of the source file
  - `uri`: Document URI
  - `name`: File name
  - `type`: File mime or category (text, pdf, image, audio, video)
  - `location`: Location offset (char offset, page number, or ms timestamp)

- Removing a file deletes all chunks matching the file's `fileId` via `delete(mapOf("fileId" to fileId))`.

## 7. Search

1. Embed the query text using `Embedder`.
2. Query `SqliteVectorStore.search(queryEmbedding, topK)` to find nearest neighbors via cosine similarity (supporting optional `fileId` or modality `type` pre-filtering).
3. Filter results that meet or exceed the similarity threshold (default `0.60`).
4. Return the top-K chunks sorted by descending similarity score.

## 8. Opening Results

| Type | Opens in |
| :--- | :--- |
| Text | In-app text viewer, scrolled to the passage |
| PDF | In-app `PdfRenderer` viewer at the page |
| Image | In-app image viewer (Coil) |
| Audio / Video | In-app Media3 ExoPlayer (`PlayerView`), `seekTo(location)` |

## 9. Settings & Tuning Variables

All variables are persisted locally using native Android `SharedPreferences` ([SettingsStore](file:///e:/src/bj/localfind/android/app/src/main/java/com/localfind/data/AppSettings.kt)):

| Area | Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| Search | Similarity Threshold | `0.60` | Minimum cosine similarity score required for matches |
| Search | Top-K | `10` | Maximum nearest-neighbor results retrieved |
| Indexing | Text Chunk Size | `500` chars | Passage character length for text, Markdown, and PDF |
| Indexing | Text Chunk Overlap | `100` chars | Sliding overlap characters between consecutive passages |
| Indexing | Audio Window Duration | `10` s | Duration of each fixed audio chunk |
| Indexing | Video Window Duration | `5` s | Duration of each video sliding window |
| Indexing | Video Frames / Window | `2` frames | Sampled JPEG frames per video window |
| Indexing | Video Include Audio | `false` | Whether to decode and embed audio tracks alongside video keyframes |
| Indexing | Video Skip Similar Chunks | `true` | Skip redundant consecutive video windows (`>= 85%` luminance similarity) |
| Indexing | Max Image Dimension | `512` px | Maximum dimension for image decoding (video frames use `256` px hardware downsampling) |
| Acceleration | Vision Accelerator | `SMART` | Hardware accelerator policy: `SMART` (Auto), `NPU` (Hardware Neural Engine), `GPU` (Faster), `CPU` (Cool & Smooth UI) |

## 10. Offline Guarantee

- No `INTERNET` permission. Any merged permission from libraries is removed with `tools:node="remove"`.
- No analytics, crash reporting, or cloud SDKs.

## 11. Project Layout

Single Gradle module, organized by feature and modality subpackages:

```text
android/app/src/main/
├── res/drawable/   # Google Material Symbols Vector Drawables (ic_*.xml)
└── java/.../localfind/
    ├── MainActivity.kt
    ├── ui/
    │   ├── gallery/   # GalleryScreen, GalleryTiles, GalleryDialogs, ThumbnailHelper
    │   ├── search/    # SearchScreen, SearchResultCard, SearchResultFormatter
    │   ├── viewer/    # ViewerOverlayHost, Image/Media/Pdf/TextViewerScreen, TextViewerHelper
    │   ├── settings/  # SettingsScreen
    │   └── theme/     # Color, Theme, Type
    ├── index/
    │   ├── Indexer.kt, IndexingWorker.kt, IndexingState.kt, IndexingResumeManager.kt, IndexingActionReceiver.kt
    │   ├── document/  # DocumentIndexer, TextChunker, PdfChunker
    │   ├── image/     # ImageIndexer
    │   ├── audio/     # AudioIndexer, AudioChunker, AudioDecoder
    │   └── video/     # VideoIndexer, VideoChunker
    ├── embed/         # MediaPipe UniversalEmbedder wrapper (Embedder, VectorMath, SocDetector)
    └── data/          # FileStore (SQLite), FileImporter (SAF), EmbeddingStore (SqliteVectorStore), EngineManager, AppSettings
```

## 12. Technical Invariants

- **Single Model Instance:** Never bypass `EngineManager`. The ~485MB `EmbeddingGemma 2` model must only be loaded once to prevent Out-Of-Memory (OOM) crashes on mobile devices.
- **Background Threading:** Never perform file extraction, PDF parsing, audio decoding, embedding, or database writes on the Android Main (UI) thread; always use dispatcher.
- **Hardware Acceleration Linking:** Vendor libraries (`libcdsprpc.so` for Qualcomm Hexagon FastRPC and `libOpenCL.so` for GPU) are declared in `AndroidManifest.xml` with `android:required="false"`, ensuring that devices lacking vendor drivers install and run normally.
- **Dynamic Hardware Fallback:** If dedicated NPU models or delegates fail to initialize on any specific silicon, `Embedder` catches runtime initialization errors and automatically cascades to GPU and CPU (XNNPack).
- **Dispatch Runtime Auto-Staging:** LiteRT dispatch loader expects vendor dispatch `.so` files in `context.filesDir`. LocalFind automatically stages bundled native dispatch libraries on initialization.
