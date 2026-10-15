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
                                  Room (SQLite): files + chunks ────────────┘
                                                                 cosine top-K
```

One model embeds **every** chunk type (text, image, audio) into one shared 768-dim vector space.
The text query uses the same model, so a text query can match an image, an audio clip, or a video frame.

## 2. Tech Stack

| Area | Choice |
| :--- | :--- |
| Language / UI | Kotlin, Jetpack Compose, single Activity |
| Min / target SDK | 26 / latest |
| Embedding model | EmbeddingGemma 2, **740M multimodal** (text + image + audio) |
| Inference | MediaPipe Tasks on Android (`UniversalEmbedder`, LiteRT under the hood) |
| Storage | Room (SQLite), embeddings stored as `BLOB` |
| Vector search | Brute-force cosine in Kotlin, vectors cached in memory |
| PDF text | PdfBox-Android |
| PDF viewing | Android `PdfRenderer` |
| Audio / video decode + playback | Media3 (ExoPlayer, `MediaExtractor`/`MediaCodec`, `MediaMetadataRetriever`) |
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
| Video | One frame every N seconds **and** audio windows | Start time (ms) |

- Window sizes and frame intervals are starting values. They will be tuned later.
- Indexing runs in a coroutine on a background dispatcher, so the UI stays responsive.
- Progress is shown per file. Background indexing (WorkManager) is in the backlog.

## 6. Data Model (Room)

```text
files
  id, uri, name, type, sizeBytes, addedAt, status (indexing | ready | missing | error)

chunks
  id, fileId (FK -> files.id, ON DELETE CASCADE),
  location (Long: char offset | page | ms), snippet (text, nullable), embedding (BLOB, float32[768])
```

- Removing a file deletes its row. Its chunks are deleted by cascade (PRD requirement).

## 7. Search

1. Embed the query text.
2. Compare it to all chunk vectors using a dot product (vectors are L2-normalized, so this equals cosine).
3. Return the top-K chunks, sorted by score.

- Vectors are loaded once into memory as `FloatArray`s and kept in sync on add and remove.
- This matches Google's AI Edge RAG SDK approach (`SqliteVectorStore`: SQLite + linear scan).
- Embedding the query takes most of the time; the scan is small for a personal library.
- If the scan becomes slow, consider ObjectBox (HNSW). Not needed for the MVP.

## 8. Opening Results

| Type | Opens in |
| :--- | :--- |
| Text | In-app text viewer, scrolled to the passage |
| PDF | In-app `PdfRenderer` viewer at the page |
| Image | In-app image viewer |
| Audio / Video | In-app Media3 ExoPlayer, `seekTo(location)` |

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
├── search/    # In-memory vector search
└── data/      # Room entities, DAOs, database
```
