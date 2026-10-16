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

---

## 6. Image & Video Handling Architecture (`google-ai-edge/gallery`)

Google AI Edge Gallery handles multimodal assets across its **Instant Media Search / Smart Album** (photos and keyframes) and **Video Moment Finder** (sliding window video retrieval) tasks.

### 6.1 Package & Dependency Map

| Capability | Package / Artifact | Key Classes & APIs | Usage in Gallery |
| :--- | :--- | :--- | :--- |
| **Video Playback & UI** | `androidx.media3:media3-exoplayer:1.11.1`<br>`androidx.media3:media3-ui:1.11.1` | `ExoPlayer`, `PlayerView`, `PlayerMessage` | Seamless video playback in Jetpack Compose, timeline scrubbing, looped moment previews, timeline message triggers. |
| **Video Trimming & Export** | `androidx.media3:media3-transformer:1.11.1` | `Transformer`, `EditedMediaItem`, `ClippingConfiguration` | Frame-accurate clip extraction and export; transmuxes keyframe chunks while transcoding boundary GOPs. |
| **Video Metadata & Frames** | Android Framework (`android.media`) | `MediaMetadataRetriever` | Fast keyframe extraction (`getFrameAtTime`), thumbnail probing (`getScaledFrameAtTime`), duration and metadata reads. |
| **Video Audio Extraction** | Android Framework (`android.media`) | `MediaExtractor`, `MediaCodec` | Pulls audio track from video and decodes to 16 kHz mono 16-bit PCM for multimodal embedding. |
| **Container Normalization** | Android Framework (`android.media`) | `MediaExtractor`, `MediaMuxer` | Detects fragmented MP4s (`moof` boxes) and remuxes them in-place to progressive MP4 without re-encoding to fix seekability. |
| **Image Loading & Preview** | `io.coil-kt:coil-compose:2.7.0` | `AsyncImage`, `ImageRequest` | Asynchronous image/thumbnail rendering in Compose lists and grids with crossfade animation and auto-sizing. |
| **Image Decoding & Sampling** | Android Framework (`android.graphics`, `android.content`) | `ImageDecoder`, `BitmapFactory`, `ContentResolver.loadThumbnail` | Downscaled bitmap decoding, memory-bounded sampling (`inSampleSize`), and fast thumbnail retrieval. |
| **EXIF & Orientation** | `androidx.exifinterface:exifinterface:1.4.1` | `ExifInterface` | Parses `TAG_ORIENTATION` for matrix rotation; extracts GPS latitude/longitude (paired with `MediaStore.setRequireOriginal`). |
| **Fast Engine Format** | Custom (`ImageUtils.kt`) | Custom TGA encoder (`encodeTga`) | Converts Bitmaps to uncompressed 32-bit BGRA TGA byte arrays for fast zero-overhead transfer into the C++ LiteRT/MediaPipe engine. |
| **Interactive Segmentation** | `com.google.mediapipe:tasks-vision:1.0.0` | `InteractiveSegmenter`, `BitmapImageBuilder` | User tap/stroke object cutout and foreground mask extraction in the Scrapbook tool. |
| **Live Camera Stream** | `androidx.camera:camera-*:1.4.2` | `CameraSelector`, `ImageAnalysis`, `ProcessCameraProvider` | Real-time live camera analysis frame streaming (`LiveCameraView`). |

---

### 6.2 Video Handling Pipeline (Video Moment Finder)

```
Video File
   │
   ▼
[Mp4Normalizer] ─── (Remux if fragmented MP4 / fMP4)
   │
   ▼
[MediaMetadataRetriever] ───> Probes non-black cover thumbnail (VideoThumbnails)
   │
   ├─► Sliding Time Windows (e.g. 2s–10s window, 1s step)
   │     ├─► Sample Frames (MediaMetadataRetriever.getFrameAtTime -> JPEG 80%)
   │     └─► Audio Slices (VideoAudioExtractor: MediaExtractor + MediaCodec -> 16kHz mono PCM)
   │
   ▼
[Token Budget & Interleaving] (estimateWindowTokenCount -> TAV order: [Time] Audio [Time] Frame)
   │
   ▼
[UniversalEmbedder] (UniversalEmbedder.embedContent) -> 768-dim L2-normalized vector
   │
   ▼
[SqliteVectorStore] (Upsert record with parent_id, startTimeMs, endTimeMs)
   │
   ▼
Search & Export
   ├─► Search & Preview: ExoPlayer + PlayerView in Compose (looping segment)
   └─► Save Moment Clip: Media3 Transformer (frame-accurate clipping)
```

#### 1. Fragmented MP4 Normalization (`Mp4Normalizer`)
- **Problem**: Modern camera apps and screen recorders frequently write fragmented MP4 files (`moof`/`mdat` fragments without `sidx` or `mfra` index tables). Under Android, `MediaMetadataRetriever.getFrameAtTime` and `MediaExtractor.seekTo` fail to seek accurately, always returning the opening frame.
- **Gallery Solution**: Scans the MP4 box structure for `BOX_TYPE_MOOF` (0x6D6F6F66). If fragmented, `Mp4Normalizer` uses `MediaExtractor` and `MediaMuxer` to remux the tracks into a progressive MP4 with full sample tables in-place. **Zero re-encoding, lossless, and instant.**

#### 2. Smart Non-Black Thumbnail Probing (`VideoThumbnails`)
- **Problem**: Opening frame (t=0) is frequently black due to camera auto-exposure ramps or fade-ins.
- **Gallery Solution**: Probes offsets `[0ms, 200ms, 500ms, 1000ms, 2000ms, 4000ms]` using `retriever.getScaledFrameAtTime(..., 64, 64)`.
- Calculates integer Rec.601 luma:
  $$\text{luma} = \frac{77R + 151G + 28B}{256}$$
- A frame is accepted if $\text{meanLuma} \ge 12$ or $\text{peakLuma} \ge 64$ (which preserves candle-lit or night shots).

#### 3. Sliding Window Slicing (`VideoMomentFinderViewModel`)
- Slices the video into overlapping intervals:
  - `windowDurationSec`: default window duration (2 to 10 seconds).
  - `overlapDurationSec`: overlap duration (default 1 to 2 seconds).
  - `stepMs = (windowDurationMs - overlapMs).coerceAtLeast(1000L)`.
- Inside each window, uniformly samples $N$ frame timestamps (`framesPerWindow`, default 2):
  $$t_i = \text{windowStartMs} + \frac{i \times \text{curWindowDuration}}{\text{framesPerWindow} - 1}$$
- Frames are extracted via `retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)`, compressed to JPEG at 80% quality, and immediately recycled.

#### 4. Audio Extraction & Downmixing (`VideoAudioExtractor`)
- Extracts the audio track in a single forward pass using `MediaExtractor` and `MediaCodec`.
- Automatically downmixes multi-channel stereo/surround to mono.
- Resamples audio to **16 kHz mono signed 16-bit PCM** (`ShortArray`), the exact native rate expected by EmbeddingGemma's mel-spectrogram frontend (320-sample frame, 160-sample hop).

#### 5. Multimodal Audio-Visual Interleaving (`AudioInterleaving`)
- EmbeddingGemma represents multimodal video windows using an interleaved layout:
  ```
  ["00:00"] <SOA> audio_slice_1 <EOA> ["00:00"] <SOI> frame_1 <EOI>
  ["00:02"] <SOA> audio_slice_2 <EOA> ["00:02"] <SOI> frame_2 <EOI>
  ```
- Before embedding, token budget is estimated:
  - Audio: $\approx 6.25\text{ tokens/sec}$.
  - Vision: 70 tokens/frame (configured via `visionTokenBudget`).
  - Text & markers: 8 tokens/slice.
  - If total tokens exceed `MAX_INPUT_SEQUENCE_LENGTH` (1024), it gracefully degrades to frame-only embedding.

#### 6. Frame-Accurate Video Trimming (`ClipTrimmer` / `Common.kt`)
- Trims saved video moments to disk using Jetpack Media3 Transformer:
  ```kotlin
  val mediaItem = MediaItem.Builder()
      .setUri(videoUri)
      .setClippingConfiguration(
          ClippingConfiguration.Builder()
              .setStartPositionMs(startTimeMs)
              .setEndPositionMs(endTimeMs)
              .build()
      )
      .build()
  val editedItem = EditedMediaItem.Builder(mediaItem).build()
  val transformer = Transformer.Builder(context).build()
  transformer.start(editedItem, outputFilePath)
  ```
- Media3 Transformer transcodes only the boundary GOP (Group of Pictures) keyframes and transmuxes the rest without re-encoding.

---

### 6.3 Image Handling Pipeline (Smart Album & Photo Library)

```
Image / Photo Asset
   │
   ├─► EXIF Metadata (ExifInterface + MediaStore.setRequireOriginal)
   │     ├─► Orientation (Matrix postRotate / preScale)
   │     └─► Unredacted GPS Latitude & Longitude -> Geocoder
   │
   ├─► Sizing & Decoding:
   │     ├─► Content URIs: ImageDecoder.decodeBitmap { setTargetSize, ALLOCATOR_SOFTWARE }
   │     ├─► File URIs: BitmapFactory.Options with inJustDecodeBounds & inSampleSize
   │     └─► Thumbnails: ContentResolver.loadThumbnail(Size(512, 512))
   │
   ├─► In-Memory Caching:
   │     └─► LruCache<String, Bitmap> (sized to 1/8 JVM heap, fallback dimensions 1024/512/256/128)
   │
   ├─► Vector Embedder Format:
   │     └─► ImageUtils.encodeTga(bitmap) (fast 32-bit BGRA uncompressed byte array)
   │
   └─► Interactive Cutout / Segmentation (Scrapbook):
         └─► MediaPipe InteractiveSegmenter (MPImage from BitmapImageBuilder)
```

#### 1. Resilient Bitmap Decoding & Downsampling
- **API 28+ Content URIs**: Uses `ImageDecoder`:
  ```kotlin
  val source = ImageDecoder.createSource(contentResolver, uri)
  val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      val maxDim = maxOf(info.size.width, info.size.height)
      if (maxDim > targetDimension) {
          val scale = targetDimension.toFloat() / maxDim
          decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
      }
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
  }
  ```
- **File / Stream Fallback**: Uses `BitmapFactory.Options` with 2-pass decoding:
  1. `inJustDecodeBounds = true` to read dimensions.
  2. `inSampleSize = maxOf(1, maxDim / targetDimension)`.
  3. Decode bitmap stream.

#### 2. EXIF Orientation & Location Protection
- **Orientation**: Reads `ExifInterface.TAG_ORIENTATION` and applies `android.graphics.Matrix` rotation (`90°`, `180°`, `270°`, flip, transpose) to guarantee upright bitmaps.
- **Location**: Uses `MediaStore.setRequireOriginal(uri)` when reading EXIF GPS coordinates to bypass Android 10+ privacy location stripping, resolving city/state via `android.location.Geocoder`.

#### 3. Zero-Copy TGA Encoding (`ImageUtils.encodeTga`)
- Rather than compressing bitmaps to JPEG and having the C++ LiteRT/MediaPipe runtime decompress them back into raw pixels, Gallery encodes bitmaps into uncompressed **32-bit BGRA Top-Down TGA** byte arrays:
  - 18-byte TGA header + 2 alignment bytes.
  - Direct `bitmap.getPixels(pixels)` -> `IntBuffer.put(pixels)`.
  - Massive latency reduction when batch-indexing hundreds of photos.

---

### 6.4 Media Preview & Playback UI

#### 1. Thumbnail Cards & Image Grids (`Coil Compose`)
- Uses `coil.compose.AsyncImage` with `ImageRequest.Builder`:
  ```kotlin
  AsyncImage(
      model = ImageRequest.Builder(LocalContext.current)
          .data(thumbnailFile)
          .crossfade(true)
          .build(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize()
  )
  ```

#### 2. Video Player Integration in Jetpack Compose (`Media3 ExoPlayer`)
- Embeds Media3's `PlayerView` via `AndroidView`:
  ```kotlin
  val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
  
  AndroidView(
      factory = { ctx ->
          PlayerView(ctx).apply {
              player = exoPlayer
              useController = false
          }
      },
      modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
  )
  ```
- **Timeline-Synchronized Triggers**: To auto-stop or loop playback at precise clip endpoints, Gallery uses ExoPlayer's internal message bus (`exoPlayer.createMessage { ... }.setPosition(endTimeMs).send()`) instead of imprecise coroutine polling loops.

