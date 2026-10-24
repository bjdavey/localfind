# Product Requirements (PRD)

## 1. Vision

LocalFind lets users search their personal files by meaning, not just by name or keyword. For example, searching *"sunset over the ocean"* finds the matching scene in a long video, and *"discussion about rent"* jumps to the right minute in a voice memo.

Everything runs **100% offline on the device**.

## 2. MVP Scope (Android)

### Supported files

| Type | Formats | Result opens at |
| :--- | :--- | :--- |
| Text | `.txt`, `.md` | Matching passage |
| PDF | `.pdf` (text-based only, no OCR) | Matching page |
| Image | `.jpg`, `.jpeg`, `.png`, `.gif` | The image |
| Audio | `.mp3`, `.m4a`, `.wav` | Matching time |
| Video | `.mp4`, `.mkv`, `.webm` | Matching time |

### Features

1. **Import** - Pick files (or whole folders) from device storage, or share them from another app, and index them.
2. **Search** - Type a natural language query and get ranked results; filter by type (All, Photos, Videos, PDFs, Text, Audio).
3. **Search by image** - Use a photo as the query to find similar images and matching video moments; also available from a photo's details.
4. **Search inside a file** - Scope a search to one document, video or audio file.
5. **Results** - Each result shows the file name, type, and location (time or page).
6. **Open** - Tapping a result opens the file in the built-in viewer at the matching location (landscape supported). Any file can also be opened in the phone gallery or its own app.
7. **Library** - See indexed files in a grid with per-file progress, organise them in folders, select several at once, view file details (including the original path), retry failed files and remove files.
8. **Background indexing** - Indexing continues when the app is closed, can be paused, resumed and cancelled, and resumes after the app is killed. The app does not request the notification permission, so on Android 13+ the indexing notification (and its Pause / Resume / Cancel buttons) is hidden; use the in-app banner.
9. **Settings** - Light/dark theme, similarity threshold and other tuning values, and advanced options such as the hardware accelerator (NPU, GPU, CPU or Smart).

### Out of scope (MVP)

- OCR / scanned documents
- iOS and desktop

## 3. User Flow

```text
Library ──> Add files ──> Indexing ──> Ready
   │
   └──> Search ──> Results ──> Tap ──> File opens at match
```

## 4. Requirements

- Works fully in airplane mode. No network permission.
- UI stays responsive while indexing.
- Removing a file also removes its index data.
