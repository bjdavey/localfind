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
- [ ] Search inside a specific file
- [ ] Support to open the origin file in phone gallery or its own app

## Bugs / Issues
- [x] Can not cancel stuck sync file
- [x] Should not allow import files other than supported that has builtin viewer
- [ ] Fix resume indexing after app closed, and keep indexing working in background while showing notification to prevent the app from being stopped when closed
- [ ] Allow import new file or search while indexing
- [ ] Fix stuck indexing after canceled
- [ ] Fix flicker when opening a file or a search result and not to scroll back to start when closing it
- [ ] Opening image/video should be smoth transition like a gallery app, perhaps just popup without opening it in a new view?
- [ ] Better PDF viewer
- [ ] Top bar should hide on scrolling


## Backlog

### Core
- Find large online public dataset for testing
- Allow other media files format
- Import from URL without saving the file (streaming keyframe indexing)
- Create folders and recursive folder import (DocumentFile / tree URI)
- Export / Backup index and "Clear Database" reset
- Support import by share from other apps\
- Simplify UI for non-tech people and keep advanced mode option in settings

### Search Experience
- Hybrid search (Vector + SQLite FTS5 keyword search)
- Search by image (visual similarity query)
- Recent searches history

### Indexing & Performance
- Battery / thermal-friendly indexing (pause on low battery / charging-only option)
- Pause, resume, and cancel indexing queue
- Vector quantization (FP16 / INT8 embeddings)
- PDF OCR (ML Kit / on-device OCR for scanned documents)
- File staleness detection (handle deleted or modified files)
- fMP4 remuxing for seekable video (see ARCHITECTURE.md, Indexing)

### Platforms
- Move core and logic into separate folder/classes for easy porting into other platforms
- iOS app
- Desktop app
