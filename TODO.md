# TODO

> [!NOTE]
> Follow [AGENTS.md](AGENTS.md). Work on **one item at a time**, top to bottom.


## Milestone 0: Setup & Architecture

- [x] Write README.md, AGENTS.md, TODO.md, docs/PRD.md
- [x] Discuss and write [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Milestone 1: Android MVP

- [x] Initialize git repository and `.gitignore`
- [x] Create a minimal Android project in `android/`
- [x] Add the embedding model and embed a text string
- [x] Store embeddings locally
- [x] Import files from device storage
- [x] Index text and Markdown files
- [x] Search: text query returns ranked results
- [x] Results list UI
- [x] Index PDFs (text-based only, no OCR) and open at the matching page
- [x] Index images and open the matching image
- [x] Index audio and play from the matching time
- [x] Index video and play from the matching time

## Milestone 2: Basic

- [x] Search and download free internet files for local testing
- [x] Remove file from library and delete its embeddings
- [x] Good builtin viewer for each file type (open at matching time/page/passage) and for whole library file too
- [x] Settings page for threshold and all other variables

## Milestone 3: Enhance UI/UX
- [x] Suggest good UI design and enhance overall
- [x] Add light/dark mode switch in settings
- [x] Background indexing with progress indicator per file (WorkManager) which can resume if app killed and reopened
- [x] Filter search results by file type (All, Video, Audio, Image, Document)
- [x] Try support CPU, GPU, NPU with option in settings depending on device
- [x] Search inside a specific file

## Milestone 4: First alpha
- [x] Don't index kinda empty pages pdf
- [x] Pause, resume, and cancel indexing queue
- [x] Find tricks, try and test to make video indexing faster, like avoiding thermal throttling or skip smiliar chunks
- [x] GPU Support
- [x] Create folders and recursive folder import (DocumentFile / tree URI)
- [x] Enhance the indexing sync manager and support resume during a file indexing
- [x] Clean source code


## Milestone 5: First release
- [x] Simplify UI for non-tech people and keep advanced mode option in settings
- [x] Button to retry All for failed or in queue 
- [x] Enhance UI/UX
- [x] Search by image
- [x] Support GIF
- [x] Support import by share from other apps
- [x] Better PDF viewer
- [x] Support landscape orientation in file viewer only
- [x] Show file details and open in phone gallery or its own app
- [x] Show original file path in file details view
- [x] Multi select
- [x] Smart Mode in Advanced Settings: dynamic GPU/CPU switching based on screen and battery saver state
- [x] Hardware NPU acceleration support
- [x] Dynamic SoC-specific NPU runtime & model delivery: Play AI Pack, Play Feature Delivery & NPU default

## Milestone 6: Pre-publish hardening (by AI Agents)
- [x] Refactor: 
    1. Remove dead code, 
    2. Merge duplicated logic 
    3. Split files over ~500 LOC (GalleryScreen, MainActivity, GalleryDialogs, SettingsScreen, GalleryTiles, IndexingWorker)
    4. Simplify / tech debt (always-non-null nullable callbacks, IndexingWorker pause logic, MainActivity state; ask before adding a ViewModel)
    5. Folder/package structure: try to isolate services in core folder that can be easily ported into different platforms later
    6. Review design decisions & update docs
    7. Design fixes from the review: shared embedder handle with read/write lock (no use of a closed model), global crash guard replaced by a per-file retry limit, data-transport backend discovery removed from the manifest, file-availability checks moved off the main thread. (`Searcher` keeps its multi-type filter branch: it has a unit test and a category could span several types.)
- [x] Remove `POST_NOTIFICATIONS` permission (notification controls are then unavailable on Android 13+; update PRD / ARCHITECTURE)
- [x] SocDetector: recreate dispatch-library symlinks when the target path changed (compare `Os.readlink`), not only when dangling
- [x] Set the compile cache dir on the GPU/CPU embedder paths. (Pinning `activationDataType` to FLOAT32 was tried and reverted: it broke the NPU text graph on SM8650; unset = engine default)
- [x] SAF persisted-URI limit: no per-file grant inside an imported folder (the tree grant covers it), warn near the limit
- [x] Storage checks: free-space check before model staging and indexing; pause with "Storage full" on `SQLiteFullException` / I/O errors
- [x] AI pack: switch `fast-follow` to `install-time` so the model is present at first launch (copied once into `filesDir`; check Play's size limits for the larger NPU files before release)
- [x] Use the NPU model file for GPU/CPU fallback on NPU devices (remove the separate fallback-model branch). Superseded: NPU models cannot run on GPU/CPU (`DISPATCH_OP`), see "NPU/GPU/CPU rework" in Bugs / Issues
- [x] Embedding-space id stored per file; banner offers re-analysis when it differs from the current one (GPU vs CPU vectors assumed to agree; unmeasured)
- [x] Foreground service timeout (Android 15+): checkpoint + retry already existed on stop; now the user is told why indexing paused (`STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`)
- [x] ~~Use `NpuCompatibilityChecker`~~ skipped (needs the `litert` dependency and is documented for `CompiledModel`, not the MediaPipe path); fixed the `SocDetector` bugs directly: `"unknown"` handling and `sun` vs `sunfish` board match
- [x] ~~Load only the needed encoders~~ spike result: `UniversalEmbedderOptions` (MediaPipe 1.1.1) has no per-modality switch (only delegates, `maxInputLength`, `visionTokensPerImage`), so nothing to do now; see Backlog
- [x] Decide: LiteRT 2.3.0 Maven NPU packages vs current Feature Delivery modules + fetch scripts. Decision: keep Feature Delivery (it already uses the Maven dispatch libraries); documented in ARCHITECTURE §3.1
- [x] Grouping matches by file (one card per file with its best hit; "N more matches" expands the rest)
- [x] Split `SocDetector`: model file resolution/staging (`stageAiPackModel`, `ensureModelFile`, ...) moved to `ModelFiles`; the two temp-file copy blocks merged into one `copyAtomically`. `SocDetector` keeps chip detection + NPU runtime. (Follow-ups: the AI-pack model sharing `MODEL_FILE_NAME` and model location resolved in 3 places are done in the NPU/GPU/CPU rework; ADB staging paths in release builds remain, see Bugs / Issues)
- [x] Support MKV and WebM video formats (.mkv, .webm)
- [x] NPU load failure behaves like real Smart: in-memory "NPU unavailable" flag in `EngineManager` (cleared by a Settings change, Retry Loading or restart) makes the NPU choice run as Smart, so the fallback follows screen/Battery Saver; the load order never falls back to the GPU unless Smart picks it; Settings shows "NPU could not be loaded, using Smart". Also declared `android.hardware.npu` in the manifest (Android 17 / targetSdk 37 denies NPU access otherwise)

## Milestone 7: First publish
- [x] Final E2E test with edge cases
- [x] Real remote devices with CPU/GPU/NPU benchmarks (automated test and deployment runner script)
- [x] App Icon
- [x] Internal test release
- [x] Open source release prep: README redesign, LICENSE (Apache 2.0), CONTRIBUTING, SECURITY, docs/privacy.html, docs/index.html (GitHub Pages)
- Screenshots
- Landing page with features requests form
- What else?
- Publish

## Bugs / Issues
- [x] E2E: files with a space or non-ASCII character in the name that were imported by share (copied to `shared_imports/`) show "File Not Found" when opened, although the copy exists. Cause: `FileAvailability.isLocalFileAvailable` strips `file://` but does not percent-decode the path `FileAvailability.kt:21`
- [ ] E2E: share import silently ignores `file://` and ungranted URIs (no message to the user)
- [ ] E2E: after killing the app while paused, the banner shows "Paused (1 pending)" but loses the file name and progress bar
- [ ] E2E: empty / whitespace-only text files are marked Ready with an "(Empty document)" tile instead of being flagged
- [x] Fix bundle script for emulator and make sure model is loaded
- [ ] Privacy: `allowBackup="true"` with empty `data_extraction_rules.xml` lets cloud backup / device transfer include the vector DB (text snippets), shared copies and the file list; share import accepts `file://` URIs (can copy LocalFind's own private files)
- [ ] Search waits for the in-flight indexing chunk because inference is synchronized (slow queries while indexing)
- [ ] File identity is the URI: the same file imported via share and via picker becomes two entries; re-importing resets status / folder; same-named folders are merged
- [ ] Model delivery: `/sdcard` model staging should be debug-only
- [ ] `largeHeap` hides Java-heap pressure from whole-file `readText`
- [ ] Resume checkpoint ignores chunk-size setting changes
- [ ] "Paused: Model not loaded" message is wiped by the worker's `finally` right after it is set
- [ ] FileProvider `files-path "."` exposes all of `filesDir` (including DB and model); `data_extraction_rules.xml` is still an empty template (decide what to back up)
- [x] NPU/GPU/CPU rework (switching to CPU crashed / model not loaded on NPU phones; the chipset NPU model cannot run text/vision on GPU/CPU, `DISPATCH_OP`):
  - Models found once (`ModelFiles.locate`), the Play pack model stored under its real (chipset) name; `Capabilities` drive Settings (impossible modes disabled)
  - One decision point: pure `HardwareAccelerator.loadOrder` (choice first, then what the device can run); `Embedder` loads one model on one accelerator, verified with a warmup embedding; the reported accelerator is the one that loaded; no mixed text/vision modes; NPU retried once
  - Recovery: when the NPU model cannot load and no universal model exists, "Download compatible model" fetches the on-demand AI pack `:ai_pack:universal_model` via Play (`ai-delivery`); `deploy-bundle -WithRecoveryPack` tests it with `--local-testing`
  - Fixed `deploy-bundle.ps1` crashing at device detection when a device returns empty `getprop` output
- [x] App-wide crash resilience: defensive gallery grid keys, viewer fallbacks, and global crash guard
- [x] Anything under search view is still clickable
- [x] Fix app crash due to duplicate keys in search results list (LazyColumn crash on device)
- [x] Can not cancel stuck sync file
- [x] Should not allow import files other than supported that has builtin viewer
- [x] Fix resume indexing after app closed, and keep indexing working in background while showing notification to prevent the app from being stopped when closed
- [x] Allow import new file or search while indexing
- [x] Fix stuck indexing after canceled
- [x] Opening image/video should be smooth transition like a gallery app, perhaps just popup without opening it in a new view?
- [x] Fix flicker when opening a file or a search result and not to scroll back to start when closing it
- [x] Stuck after PDF indexing 
- [x] Check if original file is deleted when opening
- [x] Search after filter, not filter search results
- [x] Fix PDF indexing progress bar repeating after 15%
- [x] Fix: PDF search result doesn't open at that page
- [x] GPU vs. CPU switch in Advanced Settings and micro-pacing frame yielding to prevent UI lag


## Backlog

### Core
- Support other languages
- Code Refactor: Performance (measure first), Security & config (see Bugs / Issues)
- Import from URL without saving the file (streaming keyframe indexing)
- Export / Backup index and "Clear Database" reset
- Allow other media files format: `.webp`, `.heic`, `.opus`/`.ogg`, `.aac`/`.flac`, `.mov`/`.webm`/`.mkv`
- Support code files import
- Top bar should hide on scrolling
- Stable file id + fingerprint (dedupe, detect changed files); startup cleanup between `FileStore` and the vector store
- Move `uri` / `name` out of every chunk into `FileStore` (`fileId` and `type` must stay for pre-filtering)
- Prompt a re-index when indexing settings change
- Rescan imported folders for new files
- Storage screen (index and model size) with "Clear index"
- Onboarding (privacy, notifications, battery) and accessibility (TalkBack, font scaling)
- State on rotation at target SDK 37 (`remember` vs `rememberSaveable` / ViewModel)
- Check whether video-frame JPEG encoding is needed, and whether a single-thread embedder dispatcher is required (measure first)
- Release: Play Console process (closed test, Data safety, privacy policy URL, licenses screen); doc fixes (NPU filename and group-name mismatches, MT6993 name, SMART rule repeated, README build section, LICENSE)

### Search Experience
- Hybrid search (Vector + SQLite FTS5 keyword search), including filename matching
- Recent searches history
- Show the matched passage as a snippet on text results
- Check that audio embeddings capture speech ("discussion about rent") before advertising it
- Search priority over indexing

### Indexing & Performance
- Battery / thermal-friendly indexing (pause on low battery / charging-only option)
- Vector quantization (FP16 / INT8 embeddings)
- Lower RAM: load only the needed encoders (the 300M audio encoder is rarely used). The model supports on-demand modality loading but `UniversalEmbedder` 1.1.1 exposes no option; recheck on MediaPipe / ML Kit updates
- PDF OCR (ML Kit / on-device OCR for scanned documents)
- File staleness detection (handle deleted or modified files)
- fMP4 remuxing for seekable video (see ARCHITECTURE.md, Indexing)

### Platforms
- iOS app
- Desktop app
