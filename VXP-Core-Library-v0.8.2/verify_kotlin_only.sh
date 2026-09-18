#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
if find "$ROOT/vxp-core" "$ROOT/vxp-core-android" -type f \( -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name 'CMakeLists.txt' -o -name '*.so' \) | grep -q .; then
  echo "FAIL: native source/binary found"
  exit 1
fi
if grep -R -nE 'System\.loadLibrary|external fun|JNIEXPORT|externalNativeBuild' "$ROOT/vxp-core" "$ROOT/vxp-core-android"; then
  echo "FAIL: native bridge reference found"
  exit 1
fi
echo "OK: vxp-core + vxp-core-android are Kotlin-only (no JNI/NDK/C/C++)."
