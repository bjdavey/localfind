# LocalFind

**Find the exact moment in your videos, and anything else on your phone, just by describing it.**

![License](https://img.shields.io/badge/license-Apache%202.0-blue)
![Platform](https://img.shields.io/badge/platform-Android%2012%2B-green)
![Offline](https://img.shields.io/badge/network-100%25%20offline-brightgreen)

LocalFind is a private, 100% offline semantic search app for your personal files. Type what you remember, like *"kids blowing out candles"* or *"discussion about rent"*, and it jumps straight to the matching moment in a video, the right minute of a voice memo, the right page of a PDF, or the matching photo.

<!-- TODO: add screenshots / demo GIF here once available -->

## Features

- **Search by meaning**: natural language queries, ranked results with the file name, type and location (time or page).
- **Jump to the moment**: results open in the built-in viewer at the matching time, page or passage.
- **Search by image**: use a photo to find similar photos and matching video scenes.
- **Search inside a file**: scope a search to one document, video or audio file.
- **Library**: folders, multi-select, per-file progress, retry failed files, file details.
- **Background indexing**: pause, resume and cancel; survives the app being closed or killed.
- **Add files** with the system picker (files or whole folders) or share them from another app.
- **Light and dark themes**, plus advanced tuning in Settings (similarity threshold, hardware accelerator, ...).

### Supported files

| Type | Formats | Result opens at |
| :--- | :--- | :--- |
| Video | `.mp4`, `.mkv`, `.webm` | Matching time |
| Audio | `.mp3`, `.m4a`, `.wav` | Matching time |
| Image | `.jpg`, `.jpeg`, `.png`, `.gif` | The image |
| PDF | `.pdf` (text-based, no OCR) | Matching page |
| Text | `.txt`, `.md` | Matching passage |

## Privacy

- Works in airplane mode. The app has **no `INTERNET` permission**.
- No account, no ads, no analytics, no crash reporting, no cloud services.
- Files are linked, not copied (files shared from other apps are the exception, see [ARCHITECTURE](docs/ARCHITECTURE.md)). Search data stays on the device.
- See the [privacy policy](docs/PRIVACY.md).

## How it works

Every file is split into chunks (video windows, audio windows, PDF pages, text passages, whole images). One multimodal embedding model ([EmbeddingGemma 2](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm)) turns each chunk into a vector in a shared space, so a text query can match a video frame, an audio clip or a page. Vectors are stored in a local SQLite database and searched by cosine similarity. Inference runs on the phone's NPU, GPU or CPU.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Product scope: [docs/PRD.md](docs/PRD.md).

## Build and run

You need Android Studio (or the Android SDK) and a device or emulator running Android 12 (API 31) or newer. Gradle downloads the matching JDK automatically.

```powershell
cd android
.\gradlew test          # unit tests
.\gradlew assembleDebug # build the debug app
```

**Model files are not in this repository.** The app needs the EmbeddingGemma 2 model (about 485 MB) from [Hugging Face](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main):

- **Emulator or a phone without an NPU:** download `embeddinggemma-2-740m.litertlm`, push it to `/data/local/tmp/`, and the app stages it on first launch.
- **Phones with a supported NPU:** do not use `adb install`, it drops the NPU runtime split APKs. Use the deploy script, which detects the chipset, builds the App Bundle and installs the right splits:

  ```powershell
  android\deploy-bundle.ps1 -DeviceId <serial>   # or deploy-bundle.sh on macOS / Linux
  ```

**NPU acceleration** (several times faster indexing than CPU) is supported on Qualcomm Snapdragon 8 Gen 2, 8 Gen 3, 8 Elite and 8 Elite Gen 5, Google Tensor G5 and G6, and MediaTek Dimensity 9400 and 9500 (MediaTek untested). All other phones use the GPU or CPU. Test results: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#3-model-delivery--chipset-support). Small royalty-free test files are in [samples/](samples/README.md).

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first. Security issues: see [SECURITY.md](SECURITY.md).

## License

LocalFind is licensed under the [Apache License 2.0](LICENSE).

### Third-party

- The EmbeddingGemma model is **not** covered by this license. It is distributed by Google under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms). Model files are downloaded separately and are not part of this repository.
- Built with [MediaPipe Tasks](https://github.com/google-ai-edge/mediapipe), [LiteRT](https://github.com/google-ai-edge/LiteRT), [AndroidX Media3](https://github.com/androidx/media), [Jetpack Compose](https://developer.android.com/compose), [Coil](https://github.com/coil-kt/coil) and [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android), each under its own license.
- UI icons are [Google Material Symbols](https://fonts.google.com/icons) (Apache 2.0).
