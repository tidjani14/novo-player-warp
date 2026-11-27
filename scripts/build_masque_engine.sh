#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="${RUNNER_TEMP:-/tmp}/novo-usque-android"
ENGINE_REV="1bc50c004b99e6df42fc17f4f93320d782020dde"
PATCH_FILE="$ROOT_DIR/warp-addon/third_party/usque-android-socket-protector.patch"
AAR_PATH="$ROOT_DIR/warp-addon/app/libs/usque-android.aar"
GOMOBILE_VERSION="v0.0.0-20251209145715-2553ed8ce294"

rm -rf "$ENGINE_DIR"
git clone --filter=blob:none https://github.com/Abobo7/usque-android.git "$ENGINE_DIR"
git -C "$ENGINE_DIR" checkout --detach "$ENGINE_REV"
git -C "$ENGINE_DIR" apply --check "$PATCH_FILE"
git -C "$ENGINE_DIR" apply "$PATCH_FILE"

export GOTOOLCHAIN=local
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_HOME" ]]; then
    echo "ANDROID_HOME/ANDROID_SDK_ROOT is required" >&2
    exit 1
fi
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
    echo "Android NDK not found at $ANDROID_NDK_HOME" >&2
    exit 1
fi

GOBIN_DIR="$(go env GOPATH)/bin"
mkdir -p "$GOBIN_DIR" "$(dirname "$AAR_PATH")"
GOBIN="$GOBIN_DIR" go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION"
"$GOBIN_DIR/gomobile" init

cd "$ENGINE_DIR"
"$GOBIN_DIR/gomobile" bind -v \
    -target=android/arm,android/arm64,android/386,android/amd64 \
    -androidapi 24 \
    -ldflags="-s -w" \
    -o "$AAR_PATH" \
    ./android

test -s "$AAR_PATH"
echo "Built MASQUE Android binding: $AAR_PATH"
