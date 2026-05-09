#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PUBSPEC_FILE="$ROOT/pubspec.yaml"
METADATA_FILE="$ROOT/docs/fdroid/io.github.evokelektrique.tunnelforge.yml"
ALLOW_DIRTY=0
DRY_RUN=0

print_usage() {
  cat <<EOF
Prepare the local fdroiddata draft for the current release commit.

Usage:
  $SCRIPT_NAME [--dry-run] [--allow-dirty]

Reads:
  pubspec.yaml version, for example 0.7.0+27
  current git HEAD commit

Updates:
  docs/fdroid/io.github.evokelektrique.tunnelforge.yml

Options:
  --dry-run       Print the values without editing the metadata file.
  --allow-dirty   Allow writing while the worktree has uncommitted changes.
EOF
}

fail() {
  echo "error: $*" >&2
  exit 1
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dry-run)
        DRY_RUN=1
        ;;
      --allow-dirty)
        ALLOW_DIRTY=1
        ;;
      -h|--help|help)
        print_usage
        exit 0
        ;;
      *)
        print_usage >&2
        fail "unsupported argument: $1"
        ;;
    esac
    shift
  done
}

read_pubspec_version() {
  [[ -f "$PUBSPEC_FILE" ]] || fail "missing $PUBSPEC_FILE"

  local version
  version="$(sed -n 's/^version:[[:space:]]*//p' "$PUBSPEC_FILE" | head -n 1)"
  [[ -n "$version" ]] || fail "pubspec.yaml does not contain a version field"

  if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)\+([0-9]+)$ ]]; then
    fail "pubspec version must match x.y.z+build, got: $version"
  fi

  VERSION_NAME="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
  VERSION_CODE="${BASH_REMATCH[4]}"
}

read_commit() {
  COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
  [[ -n "$COMMIT" ]] || fail "could not read current git commit"
}

check_worktree() {
  if [[ "$DRY_RUN" -eq 1 || "$ALLOW_DIRTY" -eq 1 ]]; then
    return
  fi

  if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
    fail "worktree has uncommitted changes. Commit first, or use --allow-dirty for a draft update."
  fi
}

update_metadata() {
  [[ -f "$METADATA_FILE" ]] || fail "missing $METADATA_FILE"

  local tmp_file
  tmp_file="$(mktemp)"
  sed \
    -e "s/^\([[:space:]]*-[[:space:]]*versionName:[[:space:]]*\).*/\1$VERSION_NAME/" \
    -e "s/^\([[:space:]]*versionCode:[[:space:]]*\).*/\1$VERSION_CODE/" \
    -e "s/^\([[:space:]]*commit:[[:space:]]*\).*/\1$COMMIT/" \
    -e "s/^CurrentVersion: .*/CurrentVersion: $VERSION_NAME/" \
    -e "s/^CurrentVersionCode: .*/CurrentVersionCode: $VERSION_CODE/" \
    "$METADATA_FILE" > "$tmp_file"
  mv "$tmp_file" "$METADATA_FILE"
}

main() {
  parse_args "$@"
  read_pubspec_version
  read_commit
  check_worktree

  cat <<EOF
versionName: $VERSION_NAME
versionCode: $VERSION_CODE
commit: $COMMIT
EOF

  if [[ "$DRY_RUN" -eq 1 ]]; then
    return
  fi

  update_metadata
  echo "updated: $METADATA_FILE"
}

main "$@"
