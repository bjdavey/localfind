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
| Hardware Acceleration | Automatic GPU vision acceleration (OpenCL/Vulkan) with graceful CPU fallback (XNNPack) |
| Storage | `SqliteVectorStore` (MediaPipe, SQLite-backed) |
| Vector search | `SqliteVectorStore` cosine top-K search |
| PDF text | PdfBox-Android |
| PDF viewing | Android `PdfRenderer` |
| Image / thumbnails | Coil (`AsyncImage`), `ImageDecoder` |
| Audio / video | Media3 (ExoPlayer, UI `PlayerView`), `MediaMetadataRetriever`, `MediaExtractor` / `MediaCodec` |
| Icons / Symbols | Google Material Symbols (Vector Drawables in `res/drawable/` via Compose `painterResource`) |
| Concurrency | Kotlin coroutines, AndroidX WorkManager |

## 3. Model Delivery

- No network at runtime, so the model must already be on the device.
- **Release:** Play Asset Delivery **install-time** asset pack. The model arrives with the install and works offline.
- **Development:** `adb push` the model file into the app's files directory.

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
    ├── embed/         # MediaPipe UniversalEmbedder wrapper (Embedder, VectorMath)
    └── data/          # FileStore (SQLite), FileImporter (SAF), EmbeddingStore (SqliteVectorStore), EngineManager, AppSettings
```

## 12. Technical Invariants

- **Single Model Instance:** Never bypass `EngineManager`. The ~485MB `EmbeddingGemma 2` model must only be loaded once to prevent Out-Of-Memory (OOM) crashes on mobile devices.
- **Background Threading:** Never perform file extraction, PDF parsing, audio decoding, embedding, or database writes on the Android Main (UI) thread; always use dispatcher.

