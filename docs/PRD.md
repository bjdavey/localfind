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
| Image | `.jpg`, `.png` | The image |
| Audio | `.mp3`, `.m4a`, `.wav` | Matching time |
| Video | `.mp4` | Matching time |

### Features

1. **Import** - Pick files from device storage and index them.
2. **Search** - Type a natural language query and get ranked results.
3. **Results** - Each result shows the file name, type, and location (time or page).
4. **Open** - Tapping a result opens the file at the matching location.
5. **Library** - See indexed files and remove one.

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
