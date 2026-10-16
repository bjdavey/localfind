# Test Samples for LocalFind

This directory contains lightweight, freely usable (Public Domain, CC0, and open source permissive licenses) test files covering every supported format in LocalFind ([PRD.md](../docs/PRD.md) §2).

## File Manifest

| File | Type | Format | Size | Source / Provenance | License |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `rfc20_ascii.txt` | Text | `.txt` | ~18 KB | [IETF RFC 20 (Vinton Cerf, 1969)](https://www.rfc-editor.org/rfc/rfc20.txt) | Public Domain |
| `markdown_features.md` | Text | `.md` | ~4.6 KB | [markdown-it Demo Suite](https://github.com/markdown-it/markdown-it) | MIT |
| `tracemonkey_paper.pdf` | Document | `.pdf` | ~1.0 MB | [Mozilla pdf.js Test Corpus](https://github.com/mozilla/pdf.js) | Open test asset |
| `wood_texture.jpg` | Image | `.jpg` | ~98 KB | [ImageIO Test Dataset](https://github.com/imageio/test_images) | CC0 / Permissive |
| `cat_sunset.png` | Image | `.png` | ~335 KB | [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Maine_coon_cat_black_silver_tabby_sunset.png) | CC0 1.0 Universal |
| `cat_chelsea.png` | Image | `.png` | ~220 KB | [ImageIO Test Dataset](https://github.com/imageio/test_images) | BSD / Permissive |
| `apollo11_eagle_has_landed.mp3` | Audio | `.mp3` | ~26 KB | [NASA / Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Apollo_11_Eagle_Has_Landed.mp3) | Public Domain (NASA) |
| `apollo11_small_step.wav` | Audio | `.wav` | ~266 KB | [NASA / Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Neil_Armstrong_small_step.wav) | Public Domain (NASA) |
| `sample_audio.m4a` | Audio | `.m4a` | ~46 KB | [Audio-Sample-files](https://github.com/rafaelreis-hotmart/Audio-Sample-files) | Free test media |
| `cockatoo.mp4` | Video | `.mp4` | ~729 KB | [ImageIO Test Dataset](https://github.com/imageio/test_images) | Open test media |
| `sample_video_with_audio.mp4` | Video | `.mp4` | ~1.57 MB | [Audio-Sample-files](https://github.com/rafaelreis-hotmart/Audio-Sample-files) | Free test media |

## How to Push to Android Device / Emulator

To test importing and indexing these files in the LocalFind Android app on an emulator or physical device connected via `adb`:

```bash
# Push all sample files to the device's Download folder
adb push samples/ /sdcard/Download/LocalFindSamples/
```

After pushing:
1. Open **LocalFind** on the device.
2. Go to the **Library** tab and tap **Import files**.
3. In the system file picker, browse to `Download > LocalFindSamples` and select the files to import and index.

