#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PUBSPEC_FILE="$ROOT/pubspec.yaml"

print_usage() {
  cat <<EOF
Manage the Flutter app version in pubspec.yaml.

Usage:
  $SCRIPT_NAME show
  $SCRIPT_NAME set <semver+build>
  $SCRIPT_NAME bump <major|minor|patch|build>

Examples:
  $SCRIPT_NAME show
  $SCRIPT_NAME set 0.1.1+2
  $SCRIPT_NAME bump patch
  $SCRIPT_NAME bump build
EOF
}

require_pubspec() {
  if [[ ! -f "$PUBSPEC_FILE" ]]; then
    echo "error: pubspec.yaml not found at $PUBSPEC_FILE" >&2
    exit 1
  fi
}

current_version() {
  require_pubspec
  sed -n 's/^version:[[:space:]]*//p' "$PUBSPEC_FILE" | head -n 1
}

parse_version() {
  local version="$1"
  if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)\+([0-9]+)$ ]]; then
    echo "error: version must match x.y.z+build" >&2
    exit 1
  fi

  VERSION_MAJOR="${BASH_REMATCH[1]}"
  VERSION_MINOR="${BASH_REMATCH[2]}"
  VERSION_PATCH="${BASH_REMATCH[3]}"
  VERSION_BUILD="${BASH_REMATCH[4]}"
}

write_version() {
  local next_version="$1"
  require_pubspec

  if ! grep -q '^version:' "$PUBSPEC_FILE"; then
    echo "error: pubspec.yaml does not contain a version field" >&2
    exit 1
  fi

  local tmp_file
  tmp_file="$(mktemp)"
  sed "s/^version:[[:space:]].*/version: $next_version/" "$PUBSPEC_FILE" > "$tmp_file"
  mv "$tmp_file" "$PUBSPEC_FILE"
}

show_version() {
  local version
  version="$(current_version)"
  if [[ -z "$version" ]]; then
    echo "error: could not read version from pubspec.yaml" >&2
    exit 1
  fi
  echo "$version"
}

set_version() {
  local next_version="$1"
  parse_version "$next_version"
  write_version "$next_version"
  echo "version: $next_version"
}

bump_version() {
  local bump_type="$1"
  local version
  version="$(current_version)"
  if [[ -z "$version" ]]; then
    echo "error: could not read version from pubspec.yaml" >&2
    exit 1
  fi

  parse_version "$version"

  local major="$VERSION_MAJOR"
  local minor="$VERSION_MINOR"
  local patch="$VERSION_PATCH"
  local build="$VERSION_BUILD"

  case "$bump_type" in
    major)
      major=$((major + 1))
      minor=0
      patch=0
      build=$((build + 1))
      ;;
    minor)
      minor=$((minor + 1))
      patch=0
      build=$((build + 1))
      ;;
    patch)
      patch=$((patch + 1))
      build=$((build + 1))
      ;;
    build)
      build=$((build + 1))
      ;;
    *)
      echo "error: bump type must be one of major, minor, patch, build" >&2
      exit 1
      ;;
  esac

  local next_version="${major}.${minor}.${patch}+${build}"
  write_version "$next_version"
  echo "version: $next_version"
}

main() {
  local command="${1:-}"

  case "$command" in
    show)
      if [[ $# -ne 1 ]]; then
        print_usage
        exit 1
      fi
      show_version
      ;;
    set)
      if [[ $# -ne 2 ]]; then
        print_usage
        exit 1
      fi
      set_version "$2"
      ;;
    bump)
      if [[ $# -ne 2 ]]; then
        print_usage
        exit 1
      fi
      bump_version "$2"
      ;;
    -h|--help|help)
      print_usage
      ;;
    *)
      print_usage
      exit 1
      ;;
  esac
}

main "$@"
