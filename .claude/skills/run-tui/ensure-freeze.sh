#!/usr/bin/env bash
# Print the path to a `freeze` binary, downloading it if absent.
# freeze (charmbracelet) renders ANSI → PNG offscreen, so headless captures can
# become real screenshots with no window/compositor. Cross-platform: macOS + Linux.
#
#   FREEZE=$(ensure-freeze.sh)   # prints the binary path on stdout; logs to stderr
set -euo pipefail

VER="0.2.2"
BIN_DIR="$(cd "$(dirname "$0")" && pwd)/bin"
FREEZE="$BIN_DIR/freeze"

# Already have one (downloaded earlier, or on PATH)? Use it.
if [ -x "$FREEZE" ]; then echo "$FREEZE"; exit 0; fi
if command -v freeze >/dev/null; then command -v freeze; exit 0; fi

case "$(uname -s)" in
  Linux)  OS=Linux ;;
  Darwin) OS=Darwin ;;
  *) echo "[ensure-freeze] unsupported OS $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  ARCH=x86_64 ;;
  arm64|aarch64) ARCH=arm64 ;;
  *) echo "[ensure-freeze] unsupported arch $(uname -m)" >&2; exit 1 ;;
esac

ASSET="freeze_${VER}_${OS}_${ARCH}.tar.gz"
URL="https://github.com/charmbracelet/freeze/releases/download/v${VER}/${ASSET}"
echo "[ensure-freeze] fetching $ASSET …" >&2
mkdir -p "$BIN_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if command -v curl >/dev/null; then
  curl -fsSL "$URL" -o "$TMP/f.tgz"
elif command -v wget >/dev/null; then
  wget -qO "$TMP/f.tgz" "$URL"
else
  echo "[ensure-freeze] need curl or wget to download freeze" >&2; exit 1
fi

tar -xzf "$TMP/f.tgz" -C "$TMP"
# Tarball layout: freeze_<ver>_<os>_<arch>/freeze
SRC="$(find "$TMP" -type f -name freeze -perm -u+x | head -1)"
[ -n "$SRC" ] || SRC="$(find "$TMP" -type f -name freeze | head -1)"
[ -n "$SRC" ] || { echo "[ensure-freeze] freeze binary not found in archive" >&2; exit 1; }
install -m 0755 "$SRC" "$FREEZE"
echo "[ensure-freeze] installed → $FREEZE" >&2
echo "$FREEZE"
