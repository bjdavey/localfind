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
| Min / target SDK | 28 / latest |
| Embedding model | EmbeddingGemma 2, **740M multimodal** (text + image + audio) |
| Inference | MediaPipe Tasks on Android (`UniversalEmbedder`, LiteRT under the hood) |
| Storage | `SqliteVectorStore` (MediaPipe, SQLite-backed) |
| Vector search | `SqliteVectorStore` cosine top-K search |
| PDF text | PdfBox-Android |
| PDF viewing | Android `PdfRenderer` |
| Image / thumbnails | Coil (`AsyncImage`), `ImageDecoder` |
| Audio / video | Media3 (ExoPlayer, Transformer, UI `PlayerView`), `MediaMetadataRetriever`, `MediaExtractor` / `MediaCodec` / `MediaMuxer` (fMP4 remuxing) |
| Concurrency | Kotlin coroutines |

## 3. Model Delivery

- No network at runtime, so the model must already be on the device.
- **Release:** Play Asset Delivery **install-time** asset pack. The model arrives with the install and works offline.
- **Development:** `adb push` the model file into the app's files directory.

## 4. File Access: Link, Don't Copy

- Files are picked with the system picker (`ACTION_OPEN_DOCUMENT`, multi-select).
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
| Video | Sliding window (sampled frames + 16 kHz audio) | Start time (ms) |

- Window sizes and frame intervals are starting values. They will be tuned later.
- Video indexing uses sliding time windows with interleaved frames and 16 kHz audio, plus fMP4 normalization via `MediaMuxer` for seekability.
- Indexing runs in a coroutine on a background dispatcher, so the UI stays responsive.
- Progress is shown per file. Background indexing (WorkManager) is in the backlog.

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
2. Query `SqliteVectorStore.search(queryEmbedding, topK)` to find nearest neighbors via cosine similarity.
3. Return the top-K chunks sorted by similarity score.

## 8. Opening Results

| Type | Opens in |
| :--- | :--- |
| Text | In-app text viewer, scrolled to the passage |
| PDF | In-app `PdfRenderer` viewer at the page |
| Image | In-app image viewer (Coil) |
| Audio / Video | In-app Media3 ExoPlayer (`PlayerView`), `seekTo(location)` |

## 9. Offline Guarantee

- No `INTERNET` permission. Any merged permission from libraries is removed with `tools:node="remove"`.
- No analytics, crash reporting, or cloud SDKs.

## 10. Project Layout

Single Gradle module, organized by package:

```text
android/app/src/main/java/.../localfind/
├── ui/        # Compose screens: Library, Search, Viewers
├── index/     # Chunk extraction per file type + Indexer
├── embed/     # MediaPipe embedder wrapper
├── search/    # Search operations
└── data/      # SqliteVectorStore / VectorStore wrapper
```
