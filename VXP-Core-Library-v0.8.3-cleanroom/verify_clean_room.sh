#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
fail=0
runtime_roots=("$ROOT/vxp-core" "$ROOT/vxp-core-android")
check_runtime() {
  local pat="$1"
  if grep -RniE "$pat" "${runtime_roots[@]}" --exclude='*.jar' --exclude-dir=build 2>/dev/null; then
    echo "[FAIL] forbidden runtime pattern: $pat"
    fail=1
  fi
}
check_all() {
  local pat="$1"
  if grep -RniE "$pat" "$ROOT" --exclude='*.jar' --exclude='verify_clean_room.sh' --exclude-dir=build --exclude-dir=.gradle 2>/dev/null; then
    echo "[FAIL] forbidden package pattern: $pat"
    fail=1
  fi
}
check_runtime 'JNIEXPORT|System\.loadLibrary|externalNativeBuild|NativeVxpBridge|vxp_runner\.cpp'
check_all 'mre-sdk|MRE SDK|mre_sdk_header_catalog|MreApiCatalog'
if find "$ROOT" -type f \( -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' -o -name '*.so' \) | grep -q .; then
  echo '[FAIL] native source/binary present'
  find "$ROOT" -type f \( -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' -o -name '*.so' \)
  fail=1
fi
if [[ $fail -ne 0 ]]; then exit 1; fi
echo 'OK: clean-room Kotlin-only source checks passed.'
