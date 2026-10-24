#!/bin/bash
# Downloads MediaTek NeuroPilot SDK and populates the SoC-specific
# Play Feature Delivery modules under litert_npu_runtime_libraries/mediatek_runtime_*.
#
# The populated .so files are git-ignored (see .gitignore), so every checkout must run this once.
# deploy-bundle.ps1 / deploy-bundle.sh call it automatically for MediaTek devices.
# It is idempotent: when both libraries already exist it exits without downloading the SDK.

set -e

JNI_ARM64_DIR="src/main/jni/arm64-v8a"
DEST_DIR=$(dirname "$(realpath "${BASH_SOURCE[0]}")")

if [[ -f "${DEST_DIR}/mediatek_runtime_v8/${JNI_ARM64_DIR}/libneuronusdk_adapter.mtk.so" &&
      -f "${DEST_DIR}/mediatek_runtime_v9/${JNI_ARM64_DIR}/libneuronusdk_adapter.so" ]]; then
  echo "MediaTek NPU runtime libraries already present; skipping download."
  exit 0
fi

tmp_dir=$(mktemp -d)
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

NEURO_PILOT_SDK_URL='https://s3.ap-southeast-1.amazonaws.com/mediatek.neuropilot.com/66f2c33a-2005-4f0b-afef-2053c8654e4f.gz'
V8_VERSION='v8_0_10'
V9_VERSION='v9_0_3'

# curl ships with Git Bash, macOS and most Linux distros; wget is not always installed.
pushd "$tmp_dir"
if command -v curl > /dev/null; then
  curl -L --fail -o neuro_pilot_sdk.gz "$NEURO_PILOT_SDK_URL"
else
  wget "$NEURO_PILOT_SDK_URL" -O neuro_pilot_sdk.gz
fi
tar -xzf neuro_pilot_sdk.gz
popd

SOURCE_DIR="${tmp_dir}/neuro_pilot"

echo "Copying libraries to ${DEST_DIR}/mediatek_runtime_v8/${JNI_ARM64_DIR}/"
mkdir -p "${DEST_DIR}/mediatek_runtime_v8/${JNI_ARM64_DIR}/"
cp -rf "${SOURCE_DIR}/${V8_VERSION}/usdk/lib64/libneuronusdk_adapter.mtk.so" \
  "${DEST_DIR}/mediatek_runtime_v8/${JNI_ARM64_DIR}/"

echo "Copying libraries to ${DEST_DIR}/mediatek_runtime_v9/${JNI_ARM64_DIR}/"
mkdir -p "${DEST_DIR}/mediatek_runtime_v9/${JNI_ARM64_DIR}/"
cp -rf "${SOURCE_DIR}/${V9_VERSION}/usdk/lib64/libneuronusdk_adapter.so" \
  "${DEST_DIR}/mediatek_runtime_v9/${JNI_ARM64_DIR}/"
