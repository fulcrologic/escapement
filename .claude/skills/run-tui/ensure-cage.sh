#!/usr/bin/env bash
# Ensure `cage` (a tiny wlroots kiosk compositor) is installed, so the TUI can be
# rendered into a HEADLESS Wayland output — a real terminal drawing real glyphs,
# captured pixel-perfect by grim, with NO window on the user's screen.
#
# Exits 0 if cage is available (already, or after install); non-zero otherwise.
# Linux only — macOS has no equivalent offscreen path (use headful there).
set -euo pipefail

if command -v cage >/dev/null; then exit 0; fi
[ "$(uname -s)" = Linux ] || { echo "[ensure-cage] cage is Linux-only" >&2; exit 1; }

echo "[ensure-cage] 'cage' not found — attempting install (needs sudo)…" >&2
SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null && SUDO=sudo

# wlr-randr is optional but lets `shot` resize the headless output to a roomy
# grid (full 200-col layout instead of the 1280x720 default). Best-effort.
if   command -v pacman  >/dev/null; then $SUDO pacman -S --needed --noconfirm cage wlr-randr || $SUDO pacman -S --needed --noconfirm cage
elif command -v apt-get >/dev/null; then $SUDO apt-get update -qq && $SUDO apt-get install -y cage wlr-randr || $SUDO apt-get install -y cage
elif command -v dnf     >/dev/null; then $SUDO dnf install -y cage wlr-randr || $SUDO dnf install -y cage
elif command -v zypper  >/dev/null; then $SUDO zypper install -y cage wlr-randr || $SUDO zypper install -y cage
elif command -v apk     >/dev/null; then $SUDO apk add cage wlr-randr || $SUDO apk add cage
else
  echo "[ensure-cage] no known package manager; install 'cage' manually" >&2; exit 1
fi

command -v cage >/dev/null || { echo "[ensure-cage] install did not produce 'cage'" >&2; exit 1; }
echo "[ensure-cage] installed cage" >&2
