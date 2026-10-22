#!/usr/bin/env bash
# Automated Play Feature Delivery & Play AI Pack APK deployment script using bundletool.
# Simulates Google Play Store install-time dynamic delivery for LiteRT NPU hardware acceleration.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

DEVICE_ID=""
RELEASE=false
SKIP_BUILD=false

print_usage() {
    echo "Usage: ./deploy-bundle.sh [OPTIONS]"
    echo "Options:"
    echo "  -d, --device-id SERIAL   Target ADB device serial"
    echo "  -r, --release           Build and install release bundle"
    echo "  -s, --skip-build        Skip Gradle build step"
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
    echo "[1/6] Downloading bundletool v$BUNDLETOOL_VERSION..."
    curl -sSL "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar" -o "$BUNDLETOOL_JAR"
    echo "      Bundletool downloaded to $BUNDLETOOL_JAR"
else
    echo "[1/6] bundletool found at $BUNDLETOOL_JAR"
fi

# 2. Select Device
echo "[2/6] Detecting ADB devices..."
DEVICES=($(adb devices | grep -E '\bdevice$' | awk '{print $1}'))

if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "Error: No connected ADB devices in 'device' state found." >&2
    exit 1
fi

if [[ -z "$DEVICE_ID" ]]; then
    if [[ ${#DEVICES[@]} -eq 1 ]]; then
        DEVICE_ID="${DEVICES[0]}"
    else
        # Prefer physical device if present
        for dev in "${DEVICES[@]}"; do
            if [[ ! "$dev" =~ ^emulator- ]]; then
                DEVICE_ID="$dev"
                break
            fi
        done
        if [[ -z "$DEVICE_ID" ]]; then
            DEVICE_ID="${DEVICES[0]}"
        fi
    fi
fi

echo "      Target device: $DEVICE_ID"

# 3. Query Device SoC and match Device Group
echo "[3/6] Querying device SoC for Play Feature Delivery targeting..."
SOC_MFR=$(adb -s "$DEVICE_ID" shell getprop ro.soc.manufacturer 2>/dev/null | tr -d '\r' || true)
SOC_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.soc.model 2>/dev/null | tr -d '\r' || true)
if [[ -z "$SOC_MODEL" ]]; then
    SOC_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.board.platform 2>/dev/null | tr -d '\r' || true)
fi

echo "      Detected SoC: Manufacturer='$SOC_MFR', Model='$SOC_MODEL'"

DEVICE_GROUP=""
SOC_MFR_LOWER=$(echo "$SOC_MFR" | tr '[:upper:]' '[:lower:]')
SOC_MODEL_UPPER=$(echo "$SOC_MODEL" | tr '[:lower:]' '[:upper:]')

if [[ "$SOC_MFR_LOWER" == *"qualcomm"* || "$SOC_MFR_LOWER" == *"qti"* ]]; then
    case "$SOC_MODEL_UPPER" in
        "SM8550") DEVICE_GROUP="Qualcomm_SM8550" ;;
        "SM8650") DEVICE_GROUP="Qualcomm_SM8650" ;;
        "SM8750") DEVICE_GROUP="Qualcomm_SM8750" ;;
        "SM8850") DEVICE_GROUP="Qualcomm_SM8850" ;;
    esac
elif [[ "$SOC_MFR_LOWER" == *"mediatek"* ]]; then
    case "$SOC_MODEL_UPPER" in
        "MT6991") DEVICE_GROUP="Mediatek_MT6991" ;;
        "MT6993") DEVICE_GROUP="Mediatek_MT6993" ;;
    esac
elif [[ "$SOC_MFR_LOWER" == *"google"* ]]; then
    case "$SOC_MODEL" in
        "Tensor G5") DEVICE_GROUP="Google_Tensor_G5" ;;
        "Tensor G6") DEVICE_GROUP="Google_Tensor_G6" ;;
    esac
fi

if [[ -n "$DEVICE_GROUP" ]]; then
    echo "      Matched Device Group: '$DEVICE_GROUP' (NPU split APKs will be installed)"
else
    echo "      No specific NPU Device Group matched (Device will receive base APK with CPU/GPU Smart fallback)"
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

if [[ "$SKIP_BUILD" != true ]]; then
    echo "[4/6] Building Android App Bundle (:$BUILD_TYPE)..."
    ./gradlew ":app:bundle$BUILD_TYPE"
else
    echo "[4/6] Skipping build step (reusing existing AAB at $AAB_PATH)..."
fi

if [[ ! -f "$AAB_PATH" ]]; then
    echo "Error: Bundle file not found at $AAB_PATH" >&2
    exit 1
fi

# 5. Generate APKs with bundletool
echo "[5/6] Generating split APKs via bundletool..."
java -jar "$BUNDLETOOL_JAR" build-apks --bundle="$AAB_PATH" --output="$APKS_PATH" --overwrite

# 6. Install onto device
echo "[6/6] Installing matching APK splits to device $DEVICE_ID..."
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

# 7. Launch App
echo "Launching com.localfind..."
adb -s "$DEVICE_ID" shell am start -n com.localfind/.MainActivity

echo ""
echo "Deployment completed successfully!"
echo "To monitor NPU initialization logs in realtime, run:"
echo "adb -s $DEVICE_ID logcat -c; adb -s $DEVICE_ID logcat -s UniversalEmbedder:D LocalFind:D SocDetector:D"
