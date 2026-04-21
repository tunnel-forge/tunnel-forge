#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/.env"
BUILD_DIR="$ROOT/build/release"
TEMP_KEYSTORE_PATH=""

print_usage() {
  cat <<EOF
Build a signed Android release from the root .env configuration.

Usage:
  $SCRIPT_NAME
  $SCRIPT_NAME apk
  $SCRIPT_NAME --help

Environment:
  Loads $ENV_FILE automatically.
  Requires exactly one of:
    - TUNNEL_FORGE_ANDROID_KEYSTORE_PATH
    - TUNNEL_FORGE_ANDROID_KEYSTORE_BASE64

Outputs:
  build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
  build/app/outputs/flutter-apk/app-x86_64-release.apk
EOF
}

fail() {
  echo "error: $*" >&2
  exit 1
}

cleanup() {
  if [[ -n "$TEMP_KEYSTORE_PATH" && -f "$TEMP_KEYSTORE_PATH" ]]; then
    rm -f "$TEMP_KEYSTORE_PATH"
  fi
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || fail "required command not found: $command_name"
}

load_env() {
  [[ -f "$ENV_FILE" ]] || fail "missing $ENV_FILE. Copy .env.example to .env and fill the required values."
  # .env is intentionally shell-compatible so the same file can drive local tooling.
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
}

require_value() {
  local var_name="$1"
  local value="${!var_name:-}"
  [[ -n "$value" ]] || fail "missing required env var: $var_name"
}

prepare_keystore() {
  local keystore_path="${TUNNEL_FORGE_ANDROID_KEYSTORE_PATH:-}"
  local keystore_base64="${TUNNEL_FORGE_ANDROID_KEYSTORE_BASE64:-}"

  if [[ -n "$keystore_path" && -n "$keystore_base64" ]]; then
    fail "set only one of TUNNEL_FORGE_ANDROID_KEYSTORE_PATH or TUNNEL_FORGE_ANDROID_KEYSTORE_BASE64"
  fi

  if [[ -n "$keystore_path" ]]; then
    if [[ "$keystore_path" != /* ]]; then
      keystore_path="$ROOT/$keystore_path"
    fi
    [[ -f "$keystore_path" ]] || fail "keystore file not found: $keystore_path"
    export TUNNEL_FORGE_ANDROID_KEYSTORE_PATH="$keystore_path"
    return
  fi

  [[ -n "$keystore_base64" ]] || fail "set one keystore source in .env"

  require_command base64

  mkdir -p "$BUILD_DIR"
  TEMP_KEYSTORE_PATH="$(mktemp "$BUILD_DIR/keystore.XXXXXX.jks")"
  chmod 600 "$TEMP_KEYSTORE_PATH"
  printf '%s' "$keystore_base64" | base64 --decode > "$TEMP_KEYSTORE_PATH" \
    || fail "failed to decode TUNNEL_FORGE_ANDROID_KEYSTORE_BASE64"

  export TUNNEL_FORGE_ANDROID_KEYSTORE_PATH="$TEMP_KEYSTORE_PATH"
}

validate_release_env() {
  require_value TUNNEL_FORGE_ANDROID_KEYSTORE_PASSWORD
  require_value TUNNEL_FORGE_ANDROID_KEY_ALIAS
  require_value TUNNEL_FORGE_ANDROID_KEY_PASSWORD
  prepare_keystore
}

run_build() {
  require_command flutter

  (
    cd "$ROOT"
    flutter build apk \
      --release \
      --split-per-abi \
      --target-platform android-arm64,android-x64
  )

  cat <<EOF
Built signed release APKs:
  $ROOT/build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
  $ROOT/build/app/outputs/flutter-apk/app-x86_64-release.apk
EOF
}

main() {
  local command="${1:-apk}"

  case "$command" in
    apk)
      ;;
    -h|--help|help)
      print_usage
      exit 0
      ;;
    *)
      print_usage >&2
      fail "unsupported command: $command"
      ;;
  esac

  trap cleanup EXIT

  load_env
  validate_release_env
  run_build
}

main "$@"
