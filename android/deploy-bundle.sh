#!/usr/bin/env bash
# Automated Play Feature Delivery & Play AI Pack APK deployment script using bundletool.
# Simulates Google Play Store install-time dynamic delivery for LiteRT NPU hardware acceleration
# and automatically downloads, stages, and verifies the matching on-device LiteRT-LM model.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

DEVICE_ID=""
RELEASE=false
SKIP_BUILD=false
SKIP_MODEL_PUSH=false

print_usage() {
    echo "Usage: ./deploy-bundle.sh [OPTIONS]"
    echo "Options:"
    echo "  -d, --device-id SERIAL   Target ADB device serial"
    echo "  -r, --release           Build and install release bundle"
    echo "  -s, --skip-build        Skip Gradle build step (unless cached AAB ABI mismatches)"
    echo "  --skip-model-push       Skip model check/download/push step"
    echo "  -h, --help              Show this help message"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -d|--device-id)
            DEVICE_ID="$2"
            shift 2
            ;;
        -r|--release)
            RELEASE=true
            shift
            ;;
        -s|--skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --skip-model-push)
            SKIP_MODEL_PUSH=true
            shift
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            print_usage
            exit 1
            ;;
    esac
done

echo "=========================================================="
echo " LocalFind - Bundletool Automated NPU Deployment Helper   "
echo "=========================================================="

# 1. Ensure bundletool.jar exists
TOOLS_DIR="$SCRIPT_DIR/.tools"
mkdir -p "$TOOLS_DIR"
BUNDLETOOL_VERSION="1.18.3"
BUNDLETOOL_JAR="$TOOLS_DIR/bundletool.jar"

if [[ ! -f "$BUNDLETOOL_JAR" ]]; then
    echo "[1/8] Downloading bundletool v$BUNDLETOOL_VERSION..."
    curl -sSL "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar" -o "$BUNDLETOOL_JAR"
    echo "      Bundletool downloaded to $BUNDLETOOL_JAR"
else
    echo "[1/8] bundletool found at $BUNDLETOOL_JAR"
fi

# 2. Select Device
echo "[2/8] Detecting ADB devices..."
DEVICES=($(adb devices | grep -E '\bdevice$' | awk '{print $1}'))

if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "Error: No connected ADB devices in 'device' state found. Please connect a device or launch an emulator." >&2
    exit 1
fi

if [[ -z "$DEVICE_ID" ]]; then
    if [[ ${#DEVICES[@]} -eq 1 ]]; then
        DEVICE_ID="${DEVICES[0]}"
    else
        # Prefer physical device (non-emulator) if present
        PHYSICAL_DEVICE=""
        for d in "${DEVICES[@]}"; do
            if [[ "$d" != emulator-* ]]; then
                PHYSICAL_DEVICE="$d"
                break
            fi
        done
        if [[ -n "$PHYSICAL_DEVICE" ]]; then
            DEVICE_ID="$PHYSICAL_DEVICE"
        else
            DEVICE_ID="${DEVICES[0]}"
        fi
    fi
fi

DEVICE_ABI=$(adb -s "$DEVICE_ID" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n' || true)
if [[ -z "$DEVICE_ABI" ]]; then
    DEVICE_ABI="arm64-v8a"
fi

echo "      Target device: $DEVICE_ID (ABI: $DEVICE_ABI)"

QEMU_PROP=$(adb -s "$DEVICE_ID" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n' || true)
HARDWARE=$(adb -s "$DEVICE_ID" shell getprop ro.hardware 2>/dev/null | tr -d '\r\n' || true)
IS_EMULATOR_TARGET=false
if [[ "$QEMU_PROP" == "1" || "$HARDWARE" =~ goldfish|ranchu ]]; then
    IS_EMULATOR_TARGET=true
    echo "      Emulator target detected: forcing the persisted accelerator preference to CPU."
fi

# 3. Query Device SoC and match Device Group & LiteRT Model
echo "[3/8] Querying device SoC for Play Feature Delivery & Model targeting..."
SOC_MFR=$(adb -s "$DEVICE_ID" shell getprop ro.soc.manufacturer 2>/dev/null | tr -d '\r\n' || true)
SOC_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.soc.model 2>/dev/null | tr -d '\r\n' || true)
PLATFORM=$(adb -s "$DEVICE_ID" shell getprop ro.board.platform 2>/dev/null | tr -d '\r\n' || true)
BOARD=$(adb -s "$DEVICE_ID" shell getprop ro.product.board 2>/dev/null | tr -d '\r\n' || true)

if [[ -z "$SOC_MODEL" ]]; then
    SOC_MODEL="$PLATFORM"
fi

echo "      Detected SoC: Manufacturer='$SOC_MFR', Model='$SOC_MODEL', Platform='$PLATFORM', Board='$BOARD'"

DEVICE_GROUP=""
MODEL_NAME="embeddinggemma-2-740m.litertlm" # Universal CPU/GPU fallback for Emulators & generic devices

LOOKUP_KEY=$(echo "$SOC_MFR $SOC_MODEL $PLATFORM $BOARD $HARDWARE" | tr '[:lower:]' '[:upper:]')

if [[ "$IS_EMULATOR_TARGET" != true && "$DEVICE_ABI" == "arm64-v8a" ]]; then
    if [[ "$LOOKUP_KEY" =~ QUALCOMM|QTI|QCOM ]]; then
        if [[ "$LOOKUP_KEY" =~ SM8550|KALAMA ]]; then
            DEVICE_GROUP="Qualcomm_SM8550"
            MODEL_NAME="embeddinggemma-2-740m_Qualcomm_SM8550.litertlm"
        elif [[ "$LOOKUP_KEY" =~ SM8650|PINEAPPLE ]]; then
            DEVICE_GROUP="Qualcomm_SM8650"
            MODEL_NAME="embeddinggemma-2-740m_Qualcomm_SM8650.litertlm"
        elif [[ "$LOOKUP_KEY" =~ SM8750|SUN ]]; then
            DEVICE_GROUP="Qualcomm_SM8750"
            MODEL_NAME="embeddinggemma-2-740m_Qualcomm_SM8750.litertlm"
        elif [[ "$LOOKUP_KEY" =~ SM8850 ]]; then
            DEVICE_GROUP="Qualcomm_SM8850"
            MODEL_NAME="embeddinggemma-2-740m_Qualcomm_SM8850.litertlm"
        fi
    elif [[ "$LOOKUP_KEY" =~ MEDIATEK|MT69 ]]; then
        if [[ "$LOOKUP_KEY" =~ MT6991|6991 ]]; then
            DEVICE_GROUP="Mediatek_MT6991"
            MODEL_NAME="embeddinggemma-2-740m_MediaTek_MT6991.litertlm"
        elif [[ "$LOOKUP_KEY" =~ MT6993|6993 ]]; then
            DEVICE_GROUP="Mediatek_MT6993"
            MODEL_NAME="embeddinggemma-2-740m_MediaTek_MT6993.litertlm"
        fi
    elif [[ "$LOOKUP_KEY" =~ GOOGLE|TENSOR ]]; then
        if [[ "$LOOKUP_KEY" =~ TENSOR\ G5|LAGUNA|G5 ]]; then
            DEVICE_GROUP="Google_Tensor_G5"
            MODEL_NAME="embeddinggemma-2-740m_Google_Tensor_G5.litertlm"
        elif [[ "$LOOKUP_KEY" =~ TENSOR\ G6|G6 ]]; then
            DEVICE_GROUP="Google_Tensor_G6"
            MODEL_NAME="embeddinggemma-2-740m_Google_Tensor_G6.litertlm"
        fi
    fi
fi

if [[ -n "$DEVICE_GROUP" ]]; then
    echo "      Matched Device Group: '$DEVICE_GROUP' (NPU split APKs will be installed)"
    echo "      Target NPU Model:     '$MODEL_NAME'"
else
    echo "      No specific NPU Device Group matched (Device will receive base APK with CPU/GPU fallback)"
    echo "      Target Base Model:    '$MODEL_NAME'"
fi

# 4. Build App Bundle
BUILD_TYPE="Debug"
BUILD_TYPE_LOWER="debug"
if [[ "$RELEASE" == true ]]; then
    BUILD_TYPE="Release"
    BUILD_TYPE_LOWER="release"
fi

AAB_PATH="$SCRIPT_DIR/app/build/outputs/bundle/$BUILD_TYPE_LOWER/app-$BUILD_TYPE_LOWER.aab"
APKS_PATH="$SCRIPT_DIR/app/build/outputs/bundle/$BUILD_TYPE_LOWER/app-$BUILD_TYPE_LOWER.apks"

aab_contains_abi() {
    local bundle_file="$1"
    local abi="$2"
    [[ -f "$bundle_file" ]] && jar tf "$bundle_file" | grep -q "^base/lib/${abi}/"
}

NEEDS_BUILD=true
if [[ "$SKIP_BUILD" == true ]]; then
    if [[ ! -f "$AAB_PATH" ]]; then
        echo "[4/8] Existing AAB not found at $AAB_PATH; building bundle..."
    elif ! aab_contains_abi "$AAB_PATH" "$DEVICE_ABI"; then
        echo "[4/8] Existing AAB at $AAB_PATH lacks native libraries for ABI '$DEVICE_ABI'; rebuilding bundle..."
    else
        echo "[4/8] Skipping build step (reusing existing '$DEVICE_ABI' AAB at $AAB_PATH)..."
        NEEDS_BUILD=false
    fi
fi

if [[ "$NEEDS_BUILD" == true ]]; then
    echo "[4/8] Building Android App Bundle (:$BUILD_TYPE, targetAbi=$DEVICE_ABI)..."
    ./gradlew ":app:bundle$BUILD_TYPE" "-PtargetAbi=$DEVICE_ABI"
fi

if [[ ! -f "$AAB_PATH" ]]; then
    echo "Error: Bundle file not found at $AAB_PATH" >&2
    exit 1
fi

# 5. Generate APKs with bundletool
echo "[5/8] Generating split APKs via bundletool..."
java -jar "$BUNDLETOOL_JAR" build-apks --bundle="$AAB_PATH" --output="$APKS_PATH" --overwrite

# 6. Install onto device
echo "[6/8] Installing matching APK splits to device $DEVICE_ID..."
INSTALL_CMD=(java -jar "$BUNDLETOOL_JAR" install-apks --apks="$APKS_PATH" --device-id="$DEVICE_ID" --allow-downgrade)
if [[ -n "$DEVICE_GROUP" ]]; then
    INSTALL_CMD+=(--device-groups="$DEVICE_GROUP")
fi

if ! "${INSTALL_CMD[@]}"; then
    echo "Installation failed. Attempting clean reinstall: uninstalling com.localfind..."
    adb -s "$DEVICE_ID" uninstall com.localfind || true
    echo "Retrying install-apks..."
    "${INSTALL_CMD[@]}"
fi

echo "Split APKs successfully installed!"

# 7. Model Staging Check and Push
if [[ "$SKIP_MODEL_PUSH" != true ]]; then
    echo "[7/8] Ensuring LiteRT-LM model ($MODEL_NAME) is present on device..."
    DEVICE_CHECK=$(adb -s "$DEVICE_ID" shell "if [ -f /data/local/tmp/$MODEL_NAME ] && [ -s /data/local/tmp/$MODEL_NAME ]; then echo EXISTS; fi" 2>/dev/null | tr -d '\r\n' || true)
    
    if [[ "$DEVICE_CHECK" == "EXISTS" ]]; then
        echo "      Model '$MODEL_NAME' is already present on device (/data/local/tmp)."
    else
        REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
        LOCAL_MODELS_DIR="$REPO_ROOT/.local/models"
        mkdir -p "$LOCAL_MODELS_DIR"
        CACHED_MODEL_FILE="$LOCAL_MODELS_DIR/$MODEL_NAME"

        # If model is not in local cache, download from Hugging Face
        if [[ ! -s "$CACHED_MODEL_FILE" ]]; then
            echo "      Model not found in local cache. Downloading '$MODEL_NAME' from Hugging Face..."
            HF_URL="https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/$MODEL_NAME"
            DOWNLOAD_OK=false

            if command -v curl &>/dev/null; then
                echo "      Downloading via curl: $HF_URL"
                if curl -L --fail --progress-bar -o "$CACHED_MODEL_FILE" "$HF_URL"; then
                    DOWNLOAD_OK=true
                else
                    rm -f "$CACHED_MODEL_FILE"
                fi
            elif command -v wget &>/dev/null; then
                echo "      Downloading via wget: $HF_URL"
                if wget -O "$CACHED_MODEL_FILE" "$HF_URL"; then
                    DOWNLOAD_OK=true
                else
                    rm -f "$CACHED_MODEL_FILE"
                fi
            fi

            # If NPU model failed to download, attempt fallback to universal base model
            if [[ "$DOWNLOAD_OK" != true && "$MODEL_NAME" != "embeddinggemma-2-740m.litertlm" ]]; then
                echo "      Attempting fallback to universal model 'embeddinggemma-2-740m.litertlm'..."
                FALLBACK_MODEL="embeddinggemma-2-740m.litertlm"
                CACHED_FALLBACK="$LOCAL_MODELS_DIR/$FALLBACK_MODEL"
                if [[ ! -s "$CACHED_FALLBACK" ]]; then
                    FALLBACK_URL="https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/$FALLBACK_MODEL"
                    if command -v curl &>/dev/null; then
                        curl -L --fail --progress-bar -o "$CACHED_FALLBACK" "$FALLBACK_URL" || rm -f "$CACHED_FALLBACK"
                    elif command -v wget &>/dev/null; then
                        wget -O "$CACHED_FALLBACK" "$FALLBACK_URL" || rm -f "$CACHED_FALLBACK"
                    fi
                fi
                if [[ -s "$CACHED_FALLBACK" ]]; then
                    MODEL_NAME="$FALLBACK_MODEL"
                    CACHED_MODEL_FILE="$CACHED_FALLBACK"
                fi
            fi
        fi

        # Push to device if cached model file is available
        if [[ -s "$CACHED_MODEL_FILE" ]]; then
            echo "      Pushing '$MODEL_NAME' to device (/data/local/tmp/)..."
            adb -s "$DEVICE_ID" push "$CACHED_MODEL_FILE" "/data/local/tmp/$MODEL_NAME"
            echo "      Model successfully pushed to /data/local/tmp/$MODEL_NAME"
        else
            echo "Warning: Model file could not be obtained. The app will search for existing local files or Play AI Pack on startup." >&2
        fi
    fi
else
    echo "[7/8] Skipping model staging (--skip-model-push specified)..."
fi

# Stop any running instance so startup logs and preferences are cleanly applied
adb -s "$DEVICE_ID" shell am force-stop com.localfind

if [[ "$IS_EMULATOR_TARGET" == true && "$RELEASE" != true ]]; then
    PREFS_DIR="shared_prefs"
    PREFS_PATH="$PREFS_DIR/localfind_settings.xml"
    PREFS_TEMP="$(mktemp)"
    trap 'rm -f "$PREFS_TEMP"' EXIT
    PREFS_EXISTS=$(adb -s "$DEVICE_ID" shell "run-as com.localfind sh -c 'if [ -f $PREFS_PATH ]; then echo YES; fi'" 2>/dev/null | tr -d '\r\n' || true)
    if [[ "$PREFS_EXISTS" == "YES" ]]; then
        adb -s "$DEVICE_ID" exec-out run-as com.localfind cat "$PREFS_PATH" > "$PREFS_TEMP" || true
    fi
    if [[ ! -s "$PREFS_TEMP" ]] || ! grep -q "<map" "$PREFS_TEMP"; then
        cat > "$PREFS_TEMP" <<'EOF'
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="hardware_accelerator">CPU</string>
</map>
EOF
    elif grep -q 'name="hardware_accelerator"' "$PREFS_TEMP"; then
        sed -i.bak 's|<string name="hardware_accelerator">[^<]*</string>|<string name="hardware_accelerator">CPU</string>|g' "$PREFS_TEMP"
        rm -f "${PREFS_TEMP}.bak"
    else
        sed -i.bak 's|<map/>|<map></map>|g; s|</map>|    <string name="hardware_accelerator">CPU</string>\n</map>|' "$PREFS_TEMP"
        rm -f "${PREFS_TEMP}.bak"
    fi
    adb -s "$DEVICE_ID" push "$PREFS_TEMP" "/data/local/tmp/localfind_settings.xml" >/dev/null
    adb -s "$DEVICE_ID" shell "run-as com.localfind mkdir -p $PREFS_DIR && run-as com.localfind cp /data/local/tmp/localfind_settings.xml $PREFS_PATH && rm -f /data/local/tmp/localfind_settings.xml"
fi

# 8. Launch App and Verify Model Load
echo "[8/8] Launching com.localfind and verifying model is loaded..."
adb -s "$DEVICE_ID" logcat -c
adb -s "$DEVICE_ID" shell am start -n com.localfind/.MainActivity >/dev/null

TIMEOUT_SECONDS=45
ELAPSED=0
MODEL_LOADED=false
LOAD_ERROR=""

while [[ $ELAPSED -lt $TIMEOUT_SECONDS ]]; do
    sleep 1
    ELAPSED=$((ELAPSED + 1))
    LOGS=$(adb -s "$DEVICE_ID" logcat -d -s SocDetector:I Embedder:I EngineManager:I AndroidRuntime:E DEBUG:F 2>/dev/null || true)

    if echo "$LOGS" | grep -q "Embedding model successfully loaded\."; then
        MODEL_LOADED=true
        echo "$LOGS" | grep -E "SocDetector:|Embedder:|EngineManager:" | while IFS= read -r line; do
            echo "      $line"
        done
        break
    fi

    if echo "$LOGS" | grep -qE "Failed to initialize Embedder|Embedding model not found|FATAL EXCEPTION|Fatal signal"; then
        LOAD_ERROR=$(echo "$LOGS" | grep -E "SocDetector:|Embedder:|EngineManager:|FATAL EXCEPTION|Fatal signal" || true)
        break
    fi
done

if [[ "$MODEL_LOADED" != true ]]; then
    if [[ "$SKIP_MODEL_PUSH" == true ]] && echo "$LOAD_ERROR" | grep -q "Embedding model not found"; then
        echo "Warning: App launched, but model is not present on device (--skip-model-push was specified)." >&2
    elif [[ -n "$LOAD_ERROR" ]]; then
        echo "Error: Model failed to load on $DEVICE_ID:" >&2
        echo "$LOAD_ERROR" >&2
        exit 1
    else
        echo "Error: Timed out after ${TIMEOUT_SECONDS}s waiting for embedding model to load on $DEVICE_ID." >&2
        exit 1
    fi
fi

echo ""
echo "Deployment and model verification completed successfully!"
echo "To monitor inference logs in realtime, run:"
echo "adb -s $DEVICE_ID logcat -c; adb -s $DEVICE_ID logcat -s UniversalEmbedder:D LocalFind:D SocDetector:D"
