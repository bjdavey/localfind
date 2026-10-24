# Google Play AI Pack (`:ai_pack:universal_model`)

On-demand recovery pack with the **universal** EmbeddingGemma 2 (740M) model, the only model that runs on GPU and CPU.

NPU devices receive just their chipset NPU model through `:ai_pack:embedding_model`, and that model runs only on the NPU. If its load fails (firmware / driver), the app's model error offers "Download compatible model": `embed/RecoveryModel.kt` asks Play to fetch this pack, Play downloads it (the app makes no network calls and has no network permission), and the model is used in place from Play's storage.

- Delivery: `on-demand`, no device targeting (every device gets the same file).
- Not installed with the app, so it adds nothing to the normal download.

## Asset Directory Layout

Before building a production release App Bundle, place the universal model here (git-ignored):

| Asset Path (`src/main/assets/...`) | Source Model File (Hugging Face `litert-community`) |
| :--- | :--- |
| `model/embeddinggemma-2-740m.litertlm` | `embeddinggemma-2-740m.litertlm` |

## Local Testing

`android\deploy-bundle.ps1 -WithRecoveryPack` (or `deploy-bundle.sh --with-recovery-pack`) copies `.local/models/embeddinggemma-2-740m.litertlm` into this pack and installs with bundletool `--local-testing`, so the download works without Play. Local testing cannot update an install: the script uninstalls the app first (its data is wiped). Without the switch the copy is removed again to keep bundles small.
