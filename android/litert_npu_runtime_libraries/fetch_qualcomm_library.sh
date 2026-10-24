#!/bin/bash
# Downloads Qualcomm AI Runtime (QAIRT) v2.50.0.260828 and populates the SoC-specific
# Play Feature Delivery modules under litert_npu_runtime_libraries/qualcomm_runtime_*.
#
# The populated .so files are git-ignored (see .gitignore), so every checkout must run this once.
# deploy-bundle.ps1 / deploy-bundle.sh call it automatically for Qualcomm devices.
# It is idempotent: when every library already exists it exits without downloading the ~2.4 GB SDK.

set -e

QAIRT_URL='https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip'
QAIRT_CONTENT_DIR='qairt/2.50.0.260828'

HTP_VERSIONS=(73 75 79 81)
JNI_ARM64_DIR="src/main/jni/arm64-v8a"
DEST_DIR=$(dirname "$(realpath "${BASH_SOURCE[0]}")")

# "<SDK path relative to QAIRT_CONTENT_DIR>|<destination module>" for every library we ship.
LIBS=(
  "lib/aarch64-android/libQnnSystem.so|qualcomm_runtime_common"
  "lib/aarch64-android/libQnnHtp.so|qualcomm_runtime_htp"
)
# SoC-specific Hexagon HTP stub & skel libraries (SM8550=v73, SM8650=v75, SM8750=v79, SM8850=v81).
for version in "${HTP_VERSIONS[@]}"; do
  LIBS+=("lib/hexagon-v${version}/unsigned/libQnnHtpV${version}Skel.so|qualcomm_runtime_v${version}")
  LIBS+=("lib/aarch64-android/libQnnHtpV${version}Stub.so|qualcomm_runtime_v${version}")
done

dest_path() { # <entry>
  local entry="$1"
  echo "${DEST_DIR}/${entry#*|}/${JNI_ARM64_DIR}/$(basename "${entry%%|*}")"
}

# Nothing to do when every library is already in place.
missing=false
for entry in "${LIBS[@]}"; do
  [[ -f "$(dest_path "$entry")" ]] || missing=true
done
if [[ "$missing" == false ]]; then
  echo "Qualcomm NPU runtime libraries already present; skipping download."
  exit 0
fi

tmp_dir=$(mktemp -d)
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

# curl ships with Git Bash, macOS and most Linux distros; wget is not always installed.
echo "Downloading QAIRT SDK (~2.4 GB)..."
if command -v curl > /dev/null; then
  curl -L --fail -o "$tmp_dir/qairt_sdk.zip" "$QAIRT_URL"
else
  wget "$QAIRT_URL" -O "$tmp_dir/qairt_sdk.zip"
fi

# Extract only the needed files by exact path. A blanket "*.so" pattern is unreliable (unzip
# reported "filename not matched" for it under Git Bash).
sdk_paths=()
for entry in "${LIBS[@]}"; do
  sdk_paths+=("${QAIRT_CONTENT_DIR}/${entry%%|*}")
done
unzip -q -o "$tmp_dir/qairt_sdk.zip" "${sdk_paths[@]}" -d "$tmp_dir"

for entry in "${LIBS[@]}"; do
  dest="$(dest_path "$entry")"
  echo "Copying $(basename "$dest") to ${entry#*|}"
  mkdir -p "$(dirname "$dest")"
  cp -f "${tmp_dir}/${QAIRT_CONTENT_DIR}/${entry%%|*}" "$dest"
done
