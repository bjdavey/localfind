#!/bin/bash
# Downloads Qualcomm AI Runtime (QAIRT) v2.50.0.260828 and populates the SoC-specific
# Play Feature Delivery modules under litert_npu_runtime_libraries/qualcomm_runtime_*.

set -e

tmp_dir=$(mktemp -d)
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

QAIRT_URL='https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip'
QAIRT_CONTENT_DIR='qairt/2.50.0.260828'

pushd "$tmp_dir"
wget "$QAIRT_URL" -O qairt_sdk.zip
unzip qairt_sdk.zip *.so > /dev/null
popd

SOURCE_DIR="${tmp_dir}/${QAIRT_CONTENT_DIR}"
HTP_VERSIONS=(73 75 79 81)
JNI_ARM64_DIR="src/main/jni/arm64-v8a"
DEST_DIR=$(dirname $(realpath ${BASH_SOURCE[0]}))

copy_lib() {
  local source_path="${SOURCE_DIR}/$1"
  local module="$2"
  if [[ ! -f "${source_path}" ]]; then
    return 1
  fi
  echo "Copying $(basename "${source_path}") to ${module}"
  mkdir -p "${DEST_DIR}/${module}/${JNI_ARM64_DIR}/"
  cp -rf "${source_path}" "${DEST_DIR}/${module}/${JNI_ARM64_DIR}/"
}

# Libraries used by every Qualcomm SoC alongside the LiteRT runtime AAR.
copy_lib "lib/aarch64-android/libQnnSystem.so" "qualcomm_runtime_common"

# Libraries shared by the HTP SoCs.
copy_lib "lib/aarch64-android/libQnnHtp.so" "qualcomm_runtime_htp"

# SoC-specific Hexagon HTP stub & skel libraries (SM8550=v73, SM8650=v75, SM8750=v79, SM8850=v81).
for version in "${HTP_VERSIONS[@]}"; do
  copy_lib "lib/hexagon-v${version}/unsigned/libQnnHtpV${version}Skel.so" "qualcomm_runtime_v${version}"
  copy_lib "lib/aarch64-android/libQnnHtpV${version}Stub.so" "qualcomm_runtime_v${version}"
done

