#!/usr/bin/env bash
# Automated Play Feature Delivery & Play AI Pack APK deployment script using bundletool.
# Simulates Google Play Store install-time dynamic delivery for LiteRT NPU hardware acceleration
# and automatically downloads, stages, and verifies the matching on-device LiteRT-LM model.

set -euo pipefail

# Prevent MSYS / Git Bash on Windows from rewriting Android paths starting with /data (like /data/local/tmp)
# while still converting local POSIX paths for Windows binaries (e.g. java.exe).
export MSYS2_ARG_CONV_EXCL="/data"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

DEVICE_ID=""
ALL_DEVICES=false
RELEASE=false
SKIP_BUILD=false
SKIP_MODEL_PUSH=false

print_usage() {
    echo "Usage: ./deploy-bundle.sh [OPTIONS]"
    echo "Options:"
    echo "  -d, --device-id SERIAL   Target ADB device serial (or 'all' for all devices)"
    echo "  -a, --all               Target all connected ADB devices"
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
        -a|--all)
            ALL_DEVICES=true
            shift
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

# 2. Detect & Select ADB Device(s)
echo "[2/8] Detecting ADB devices..."

DEVICES=()
MODELS=()
ABIS=()
TYPES=()
IS_EMULATORS=()

while IFS= read -r line; do
    line=$(echo "$line" | tr -d '\r')
    [[ -z "$line" || "$line" =~ ^List\ of\ devices ]] && continue
    if [[ "$line" =~ ^([^[:space:]]+)[[:space:]]+device([[:space:]]|$) ]]; then
        serial="${BASH_REMATCH[1]}"
        model=""
        if [[ "$line" =~ model:([^[:space:]]+) ]]; then
            model="${BASH_REMATCH[1]}"
        fi
        prop_model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || true)
        [[ -n "$prop_model" ]] && model="$prop_model"
        [[ -z "$model" ]] && model="Unknown"

        abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n' || true)
        [[ -z "$abi" ]] && abi="arm64-v8a"

        qemu_prop=$(adb -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n' || true)
        hardware=$(adb -s "$serial" shell getprop ro.hardware 2>/dev/null | tr -d '\r\n' || true)
        is_emu=false
        type_str="Physical"
        if [[ "$qemu_prop" == "1" || "$hardware" =~ goldfish|ranchu || "$serial" =~ ^emulator- ]]; then
            is_emu=true
            type_str="Emulator"
        fi

        DEVICES+=("$serial")
        MODELS+=("$model")
        ABIS+=("$abi")
        TYPES+=("$type_str")
        IS_EMULATORS+=("$is_emu")
    fi
done < <(adb devices -l)

if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "Error: No connected ADB devices in 'device' state found. Please connect a device or launch an emulator." >&2
    exit 1
fi

TARGET_DEVICES=()

if [[ "$ALL_DEVICES" == true || "$DEVICE_ID" =~ ^[aA][lL][lL]$ ]]; then
    TARGET_DEVICES=("${DEVICES[@]}")
elif [[ -n "$DEVICE_ID" ]]; then
    TARGET_DEVICES=("$DEVICE_ID")
else
    echo ""
    echo "Connected ADB devices:"
    for i in "${!DEVICES[@]}"; do
        num=$((i + 1))
        echo "  [$num] ${DEVICES[i]} - ${MODELS[i]} (${ABIS[i]}, ${TYPES[i]})"
    done
    echo "  [a] All devices (${#DEVICES[@]})"
    echo ""

    if [[ -t 0 ]]; then
        prompt="Select device [1-${#DEVICES[@]}, 'a' for all] (default: 1): "
        [[ ${#DEVICES[@]} -eq 1 ]] && prompt="Select device [1, 'a' for all] (default: 1): "
        read -r -p "$prompt" USER_CHOICE || USER_CHOICE=""
        USER_CHOICE=$(echo "$USER_CHOICE" | tr -d '\r' | xargs || true)
    else
        echo "Non-interactive environment detected; choosing default (first device: ${DEVICES[0]})."
        USER_CHOICE="1"
    fi

    if [[ -z "$USER_CHOICE" || "$USER_CHOICE" == "1" ]]; then
        TARGET_DEVICES=("${DEVICES[0]}")
    elif [[ "$USER_CHOICE" =~ ^(a|A|all|ALL|\*)$ ]]; then
        TARGET_DEVICES=("${DEVICES[@]}")
    else
        IFS=', ' read -r -a SELECTED_INDICES <<< "$USER_CHOICE"
        TARGET_DEVICES=()
        for idx in "${SELECTED_INDICES[@]}"; do
            if [[ "$idx" =~ ^[0-9]+$ ]] && (( idx >= 1 && idx <= ${#DEVICES[@]} )); then
                TARGET_DEVICES+=("${DEVICES[$((idx - 1))]}")
            fi
        done
        if [[ ${#TARGET_DEVICES[@]} -eq 0 ]]; then
            echo "Warning: Invalid choice '$USER_CHOICE'. Defaulting to first device: ${DEVICES[0]}"
            TARGET_DEVICES=("${DEVICES[0]}")
        fi
    fi
fi

# Build paths
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

LAST_BUILT_ABI=""
LAST_APKS_ABI=""
RESULTS=()
SUCCESS_COUNT=0
TOTAL_TARGETS="${#TARGET_DEVICES[@]}"
CURRENT_TARGET=0

for CURRENT_DEVICE_ID in "${TARGET_DEVICES[@]}"; do
    CURRENT_TARGET=$((CURRENT_TARGET + 1))
    echo ""
    echo "=========================================================="
    echo ">>> Deploying target [$CURRENT_TARGET/$TOTAL_TARGETS]: $CURRENT_DEVICE_ID"
    echo "=========================================================="

    DEVICE_ABI=$(adb -s "$CURRENT_DEVICE_ID" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n' || true)
    [[ -z "$DEVICE_ABI" ]] && DEVICE_ABI="arm64-v8a"
    echo "      Target device: $CURRENT_DEVICE_ID (ABI: $DEVICE_ABI)"

    QEMU_PROP=$(adb -s "$CURRENT_DEVICE_ID" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n' || true)
    HARDWARE=$(adb -s "$CURRENT_DEVICE_ID" shell getprop ro.hardware 2>/dev/null | tr -d '\r\n' || true)
    IS_EMULATOR_TARGET=false
    if [[ "$QEMU_PROP" == "1" || "$HARDWARE" =~ goldfish|ranchu || "$CURRENT_DEVICE_ID" =~ ^emulator- ]]; then
        IS_EMULATOR_TARGET=true
        echo "      Emulator target detected: forcing the persisted accelerator preference to CPU."
    fi

    DEVICE_FAILED=false
    DEVICE_ERR=""

    # 3. Query Device SoC and match Device Group & LiteRT Model
    echo "[3/8] Querying device SoC for Play Feature Delivery & Model targeting..."
    SOC_MFR=$(adb -s "$CURRENT_DEVICE_ID" shell getprop ro.soc.manufacturer 2>/dev/null | tr -d '\r\n' || true)
    SOC_MODEL=$(adb -s "$CURRENT_DEVICE_ID" shell getprop ro.soc.model 2>/dev/null | tr -d '\r\n' || true)
    PLATFORM=$(adb -s "$CURRENT_DEVICE_ID" shell getprop ro.board.platform 2>/dev/null | tr -d '\r\n' || true)
    BOARD=$(adb -s "$CURRENT_DEVICE_ID" shell getprop ro.product.board 2>/dev/null | tr -d '\r\n' || true)
    [[ -z "$SOC_MODEL" ]] && SOC_MODEL="$PLATFORM"

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

    # The Qualcomm HTP runtime libraries are git-ignored; without them the NPU split APKs are empty and
    # the app silently falls back to CPU/GPU. The fetch script is idempotent (no download when present).
    if [[ "$DEVICE_GROUP" == Qualcomm_* ]]; then
        if ! bash "$SCRIPT_DIR/litert_npu_runtime_libraries/fetch_qualcomm_library.sh"; then
            DEVICE_FAILED=true
            DEVICE_ERR="fetch_qualcomm_library.sh failed"
        fi
    fi

    if [[ "$DEVICE_GROUP" == Mediatek_* ]]; then
        if ! bash "$SCRIPT_DIR/litert_npu_runtime_libraries/fetch_mediatek_library.sh"; then
            DEVICE_FAILED=true
            DEVICE_ERR="fetch_mediatek_library.sh failed"
        fi
    fi

    # 4. Build App Bundle
    NEEDS_BUILD=false
    if [[ "$LAST_BUILT_ABI" == "$DEVICE_ABI" && -f "$AAB_PATH" ]] && aab_contains_abi "$AAB_PATH" "$DEVICE_ABI"; then
        echo "[4/8] Reusing AAB built earlier in this session for ABI '$DEVICE_ABI'..."
    elif [[ "$SKIP_BUILD" == true ]]; then
        if [[ ! -f "$AAB_PATH" ]]; then
            echo "[4/8] Existing AAB not found at $AAB_PATH; building bundle..."
            NEEDS_BUILD=true
        elif ! aab_contains_abi "$AAB_PATH" "$DEVICE_ABI"; then
            echo "[4/8] Existing AAB at $AAB_PATH lacks native libraries for ABI '$DEVICE_ABI'; rebuilding bundle..."
            NEEDS_BUILD=true
        else
            echo "[4/8] Skipping build step (reusing existing '$DEVICE_ABI' AAB at $AAB_PATH)..."
        fi
    else
        NEEDS_BUILD=true
    fi

    if [[ "$NEEDS_BUILD" == true && "$DEVICE_FAILED" != true ]]; then
        echo "[4/8] Building Android App Bundle (:$BUILD_TYPE, targetAbi=$DEVICE_ABI)..."
        if ! ./gradlew ":app:bundle$BUILD_TYPE" "-PtargetAbi=$DEVICE_ABI"; then
            DEVICE_FAILED=true
            DEVICE_ERR="Gradle bundle build failed"
        else
            LAST_BUILT_ABI="$DEVICE_ABI"
        fi
    fi

    if [[ "$DEVICE_FAILED" != true && ! -f "$AAB_PATH" ]]; then
        DEVICE_FAILED=true
        DEVICE_ERR="Bundle file not found at $AAB_PATH"
    fi

    # 5. Generate APKs with bundletool
    if [[ "$DEVICE_FAILED" != true ]]; then
        if [[ "$LAST_APKS_ABI" == "$DEVICE_ABI" && -f "$APKS_PATH" && "$NEEDS_BUILD" != true ]]; then
            echo "[5/8] Reusing split APKs generated earlier in this session for ABI '$DEVICE_ABI'..."
        else
            echo "[5/8] Generating split APKs via bundletool..."
            if ! java -jar "$BUNDLETOOL_JAR" build-apks --bundle="$AAB_PATH" --output="$APKS_PATH" --overwrite; then
                DEVICE_FAILED=true
                DEVICE_ERR="bundletool build-apks failed"
            else
                LAST_APKS_ABI="$DEVICE_ABI"
            fi
        fi
    fi

    # 6. Install onto device
    if [[ "$DEVICE_FAILED" != true ]]; then
        echo "[6/8] Installing matching APK splits to device $CURRENT_DEVICE_ID..."
        INSTALL_CMD=(java -jar "$BUNDLETOOL_JAR" install-apks --apks="$APKS_PATH" --device-id="$CURRENT_DEVICE_ID" --allow-downgrade)
        if [[ -n "$DEVICE_GROUP" ]]; then
            INSTALL_CMD+=(--device-groups="$DEVICE_GROUP")
        fi

        if ! "${INSTALL_CMD[@]}"; then
            echo "Installation failed. Attempting clean reinstall: uninstalling com.localfind..."
            adb -s "$CURRENT_DEVICE_ID" uninstall com.localfind || true
            echo "Retrying install-apks..."
            if ! "${INSTALL_CMD[@]}"; then
                DEVICE_FAILED=true
                DEVICE_ERR="bundletool install-apks failed after reinstall attempt"
            fi
        fi
    fi

    if [[ "$DEVICE_FAILED" != true ]]; then
        echo "Split APKs successfully installed!"
    fi

    # 7. Model Staging Check and Push
    if [[ "$DEVICE_FAILED" != true && "$SKIP_MODEL_PUSH" != true ]]; then
        echo "[7/8] Ensuring LiteRT-LM model ($MODEL_NAME) is present on device..."
        DEVICE_CHECK=$(adb -s "$CURRENT_DEVICE_ID" shell "if [ -f /data/local/tmp/$MODEL_NAME ] && [ -s /data/local/tmp/$MODEL_NAME ]; then echo EXISTS; fi" 2>/dev/null | tr -d '\r\n' || true)

        if [[ "$DEVICE_CHECK" == "EXISTS" ]]; then
            echo "      Model '$MODEL_NAME' is already present on device (/data/local/tmp)."
        else
            REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
            LOCAL_MODELS_DIR="$REPO_ROOT/.local/models"
            mkdir -p "$LOCAL_MODELS_DIR"
            CACHED_MODEL_FILE="$LOCAL_MODELS_DIR/$MODEL_NAME"

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

            if [[ -s "$CACHED_MODEL_FILE" ]]; then
                echo "      Pushing '$MODEL_NAME' to device (/data/local/tmp/)..."
                if adb -s "$CURRENT_DEVICE_ID" push "$CACHED_MODEL_FILE" "/data/local/tmp/$MODEL_NAME"; then
                    echo "      Model successfully pushed to /data/local/tmp/$MODEL_NAME"
                else
                    echo "Warning: adb push failed." >&2
                fi
            else
                echo "Warning: Model file could not be obtained. The app will search for existing local files or Play AI Pack on startup." >&2
            fi
        fi
    elif [[ "$SKIP_MODEL_PUSH" == true ]]; then
        echo "[7/8] Skipping model staging (--skip-model-push specified)..."
    fi

    # Force CPU preference on emulators to avoid routing into unavailable native delegates
    if [[ "$DEVICE_FAILED" != true ]]; then
        adb -s "$CURRENT_DEVICE_ID" shell am force-stop com.localfind || true

        if [[ "$IS_EMULATOR_TARGET" == true && "$RELEASE" != true ]]; then
            PREFS_DIR="shared_prefs"
            PREFS_PATH="$PREFS_DIR/localfind_settings.xml"
            PREFS_TEMP="$(mktemp)"
            PREFS_EXISTS=$(adb -s "$CURRENT_DEVICE_ID" shell "run-as com.localfind sh -c 'if [ -f $PREFS_PATH ]; then echo YES; fi'" 2>/dev/null | tr -d '\r\n' || true)
            if [[ "$PREFS_EXISTS" == "YES" ]]; then
                adb -s "$CURRENT_DEVICE_ID" exec-out run-as com.localfind cat "$PREFS_PATH" > "$PREFS_TEMP" || true
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
            adb -s "$CURRENT_DEVICE_ID" push "$PREFS_TEMP" "/data/local/tmp/localfind_settings.xml" >/dev/null || true
            adb -s "$CURRENT_DEVICE_ID" shell "run-as com.localfind mkdir -p $PREFS_DIR && run-as com.localfind cp /data/local/tmp/localfind_settings.xml $PREFS_PATH && rm -f /data/local/tmp/localfind_settings.xml" || true
            rm -f "$PREFS_TEMP"
        fi

        # 8. Launch App and Verify Model Load
        echo "[8/8] Launching com.localfind and verifying model is loaded..."
        adb -s "$CURRENT_DEVICE_ID" logcat -c
        adb -s "$CURRENT_DEVICE_ID" shell am start -n com.localfind/.MainActivity >/dev/null

        TIMEOUT_SECONDS=45
        ELAPSED=0
        MODEL_LOADED=false
        LOAD_ERROR=""

        while [[ $ELAPSED -lt $TIMEOUT_SECONDS ]]; do
            sleep 1
            ELAPSED=$((ELAPSED + 1))
            LOGS=$(adb -s "$CURRENT_DEVICE_ID" logcat -d -s SocDetector:I ModelFiles:I Embedder:I EngineManager:I AndroidRuntime:E DEBUG:F 2>/dev/null || true)

            if echo "$LOGS" | grep -q "Embedding model successfully loaded\."; then
                MODEL_LOADED=true
                echo "$LOGS" | grep -E "SocDetector:|ModelFiles:|Embedder:|EngineManager:" | while IFS= read -r logline; do
                    echo "      $logline"
                done
                break
            fi

            if echo "$LOGS" | grep -qE "Failed to initialize Embedder|Embedding model not found|FATAL EXCEPTION|Fatal signal"; then
                LOAD_ERROR=$(echo "$LOGS" | grep -E "SocDetector:|ModelFiles:|Embedder:|EngineManager:|FATAL EXCEPTION|Fatal signal" || true)
                break
            fi
        done

        if [[ "$MODEL_LOADED" != true ]]; then
            if [[ "$SKIP_MODEL_PUSH" == true ]] && echo "$LOAD_ERROR" | grep -q "Embedding model not found"; then
                echo "Warning: App launched, but model is not present on device (--skip-model-push was specified)." >&2
                RESULTS+=("[PASS] $CURRENT_DEVICE_ID - Launched (model check skipped)")
                SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
            elif [[ -n "$LOAD_ERROR" ]]; then
                DEVICE_FAILED=true
                DEVICE_ERR="Model failed to load: $LOAD_ERROR"
            else
                DEVICE_FAILED=true
                DEVICE_ERR="Timed out after ${TIMEOUT_SECONDS}s waiting for embedding model to load"
            fi
        else
            RESULTS+=("[PASS] $CURRENT_DEVICE_ID - Split APKs installed & model verified")
            SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
        fi
    fi

    if [[ "$DEVICE_FAILED" == true ]]; then
        echo "Error on $CURRENT_DEVICE_ID: $DEVICE_ERR" >&2
        RESULTS+=("[FAIL] $CURRENT_DEVICE_ID - $DEVICE_ERR")
    fi
done

# Summary Report
echo ""
echo "=========================================================="
echo " Deployment Summary ($SUCCESS_COUNT/$TOTAL_TARGETS succeeded)"
echo "=========================================================="
ANY_FAILED=false
for res in "${RESULTS[@]}"; do
    echo "  $res"
    if [[ "$res" =~ ^\[FAIL\] ]]; then
        ANY_FAILED=true
    fi
done
echo "=========================================================="

if [[ "$ANY_FAILED" == true ]]; then
    exit 1
fi

echo ""
echo "All deployments and model verifications completed successfully!"
echo "To monitor inference logs in realtime, run:"
echo "adb logcat -s UniversalEmbedder:D LocalFind:D SocDetector:D ModelFiles:D"
