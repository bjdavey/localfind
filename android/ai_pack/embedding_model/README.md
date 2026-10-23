# Google Play AI Pack (`:ai_pack:embedding_model`)

This module packages the on-device **EmbeddingGemma 2 (740M)** `.litertlm` multimodal models using Google Play AI Pack (`com.android.ai-pack`) with `install-time` delivery (the model is present at first launch; the app copies it once into `filesDir` because Play only exposes install-time files through `AssetManager`) and SoC device-group targeting (`android/app/device_targeting_configuration.xml`).

## Asset Directory Layout

Before building a production release App Bundle (`./gradlew bundleRelease`), place each pre-compiled `.litertlm` model into its corresponding device-group directory renamed to `embeddinggemma-2-740m.litertlm`:

| Asset Path (`src/main/assets/...`) | Source Model File (Hugging Face `litert-community`) | Target SoC |
| :--- | :--- | :--- |
| `model#group_Qualcomm_SM8550/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.SM8550.litertlm` | Snapdragon 8 Gen 2 (V73) |
| `model#group_Qualcomm_SM8650/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.SM8650.litertlm` | Snapdragon 8 Gen 3 (V75) |
| `model#group_Qualcomm_SM8750/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.SM8750.litertlm` | Snapdragon 8 Elite (V79) |
| `model#group_Qualcomm_SM8850/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.SM8850.litertlm` | Snapdragon 8 Elite Gen 5 (V81) |
| `model#group_Mediatek_MT6991/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.MT6991.litertlm` | Dimensity 9400 (v8) |
| `model#group_Mediatek_MT6993/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.MT6993.litertlm` | Dimensity 9500 (v9) |
| `model#group_Google_Tensor_G5/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.google_tensor_g5.litertlm` | Google Tensor G5 |
| `model#group_Google_Tensor_G6/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.google_tensor_g6.litertlm` | Google Tensor G6 |
| `model#group_other/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.litertlm` | Default CPU/GPU (`other`) |
