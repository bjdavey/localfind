# Google AI Edge Gallery Architecture & Reference

Reference findings and architectural patterns extracted from [`google-ai-edge/gallery`](https://github.com/google-ai-edge/gallery) for use in LocalFind.

---

## 1. Overview

The Google AI Edge Gallery is Google's reference showcase for on-device GenAI and multimodal embeddings. Its **Instant Media Search** (photo library) and **Video Moment Finder** (video timeline navigation) demonstrate 100% offline multimodal retrieval using:
- **Embedding Model**: EmbeddingGemma 2 (`740M multimodal`: text, images, audio, video frames in a single shared 768-dimensional vector space).
- **Inference Runtime**: MediaPipe Tasks (`UniversalEmbedder`, LiteRT backend).
- **Vector Storage**: `SqliteVectorStore` from MediaPipe Tasks Retrieval.

---

## 2. Vector & Metadata Storage (`SqliteVectorStore`)

Instead of requiring an external database library like Room or an external vector database, Google AI Edge provides **`SqliteVectorStore`** directly inside `com.google.mediapipe:tasks-retrieval`:

- **Class**: `com.google.mediapipe.tasks.retrieval.components.SqliteVectorStore`
- **Interface**: `com.google.mediapipe.tasks.retrieval.components.VectorStore`
- **Native Engine**: `libsqlite_vector_store_jni.so` backed by local SQLite (`rag_vector_store` table).
- **Zero Extra Dependencies**: Bundled inside `tasks-retrieval`. No KSP compiler plugins or Room boilerplate needed.

### API Capabilities

```kotlin
// Initialization
val store = SqliteVectorStore(context, databaseFilePath, embeddingDimension) // dimension = 768

// CRUD & Search
store.upsert(records: List<RetrievalRecord>)
store.get(ids: List<String>): List<RetrievalRecord>
store.getAllRecordIds(): List<String>
store.delete(ids: List<String>)
store.delete(metadataFilter: Map<String, String>) // e.g. delete all chunks for fileId
store.deleteAll()
store.search(queryEmbedding: FloatArray, topK: Int): List<RetrievalRecord>
store.search(queryEmbedding: FloatArray, topK: Int, metadataFilter: Map<String, String>): List<RetrievalRecord>
store.close()
```

### Data Record: `RetrievalRecord`

```kotlin
RetrievalRecord(
    id = "fileId_chunkIndex",
    content = listOf(TextPart("passage text...")), // or ImagePart / AudioPart
    embeddings = floatArrayOf(...),                // 768 floats (L2 normalized)
    metadata = mapOf(
        "fileId" to fileId,
        "uri" to uriString,
        "name" to fileName,
        "type" to fileType,
        "location" to locationOffsetString
    )
)
```

> [!NOTE]
> Avoid using internal SQLite column names (`id`, `embeddings`) as metadata keys to prevent key collisions in MediaPipe's record conversion.

---

## 3. Testing Pattern: `InMemoryVectorStore`

Because `SqliteVectorStore` depends on native `.so` binaries (`libsqlite_vector_store_jni.so`), host JVM unit tests cannot load the native SQLite library directly.

Google AI Edge Gallery uses a lightweight in-memory fallback implementing `VectorStore`:
- Use `FakeVectorStore` / `InMemoryVectorStore` in unit tests (`src/test`).
- Use real `SqliteVectorStore` on device / emulator (`src/androidTest` and runtime).

---

## 4. Multimodal Indexing & Search Flow

1. **Extraction**:
   - Text files (`.txt`, `.md`): Split into paragraph passages.
   - PDF files (`.pdf`): Extract text page-by-page.
   - Images (`.jpg`, `.png`): Process full image bitmap.
   - Audio (`.mp3`, `.m4a`, `.wav`): Fixed time window chunks.
   - Video (`.mp4`): Sample keyframes every N seconds + audio windows.
2. **Embedding**:
   - `Embedder` transforms the chunk into a 768-dimensional float array.
3. **Storage**:
   - Inserted into `SqliteVectorStore` with metadata (`location` offset, `fileId`, `type`).
4. **Search**:
   - Query text is embedded into a 768-dim vector.
   - `store.search(queryVector, topK)` returns ranked `RetrievalRecord`s sorted by cosine similarity.
   - Matching `location` in metadata allows opening the target file directly at the exact position/timestamp.

---

## 5. Model Characteristics & Memory

- **Embedding Dimension**: 768 dimensions.
- **MRL (Matryoshka Representation Learning)**: EmbeddingGemma 2 supports truncated dimensions (512, 256, 128) if storage compression is needed in the future.
- **Offline / Edge**: Fully private and runs 100% on-device with zero network access.

