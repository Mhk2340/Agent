#!/usr/bin/env bash
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
    echo "Native compilation is restricted to GitHub Actions for this project." >&2
    exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
set -a
source "$ROOT/native/llama.cpp.lock"
set +a

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_ROOT" ]]; then
    if [[ -z "$SDK_ROOT" ]]; then
        echo "ANDROID_SDK_ROOT/ANDROID_HOME or ANDROID_NDK_HOME is required" >&2
        exit 1
    fi
    NDK_ROOT="$SDK_ROOT/ndk/$ANDROID_NDK_VERSION"
fi

if [[ ! -f "$NDK_ROOT/source.properties" ]]; then
    echo "Android NDK was not found at $NDK_ROOT" >&2
    exit 1
fi
# PROVISIONAL - pending GEN-PERF-1
ACTUAL_NDK_VERSION="$(sed -n 's/^Pkg.Revision = //p' "$NDK_ROOT/source.properties" | head -n 1)"
if [[ "$ACTUAL_NDK_VERSION" != "$ANDROID_NDK_VERSION" ]]; then
    echo "Expected NDK $ANDROID_NDK_VERSION, found $ACTUAL_NDK_VERSION" >&2
    exit 1
fi

if ! command -v cmake >/dev/null 2>&1; then
    echo "Pinned CMake $CMAKE_VERSION is not installed" >&2
    exit 1
fi
ACTUAL_CMAKE_VERSION="$(cmake --version | sed -n '1s/^cmake version //p')"
if [[ "$ACTUAL_CMAKE_VERSION" != "$CMAKE_VERSION" ]]; then
    echo "Expected CMake $CMAKE_VERSION, found $ACTUAL_CMAKE_VERSION" >&2
    exit 1
fi

WORK_ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/agent-phase-h-$LLAMA_CPP_COMMIT"
SOURCE_DIR="$WORK_ROOT/llama.cpp"
BUILD_DIR="$WORK_ROOT/build"
mkdir -p "$WORK_ROOT"
rm -rf "$SOURCE_DIR" "$BUILD_DIR"

git init "$SOURCE_DIR"
git -C "$SOURCE_DIR" remote add origin "$LLAMA_CPP_REPOSITORY"
# PROVISIONAL - pending GEN-PERF-1
git -C "$SOURCE_DIR" fetch --depth=1 origin "$LLAMA_CPP_COMMIT"
# PROVISIONAL - pending GEN-PERF-1
git -C "$SOURCE_DIR" fetch --depth=1 origin "+refs/tags/$LLAMA_CPP_TAG:refs/tags/$LLAMA_CPP_TAG"
TAG_COMMIT="$(git -C "$SOURCE_DIR" rev-parse "$LLAMA_CPP_TAG^{commit}")"
if [[ "$TAG_COMMIT" != "$LLAMA_CPP_COMMIT" ]]; then
    echo "Pinned tag $LLAMA_CPP_TAG resolved to $TAG_COMMIT, expected $LLAMA_CPP_COMMIT" >&2
    exit 1
fi
git -C "$SOURCE_DIR" checkout --detach "$LLAMA_CPP_COMMIT"
ACTUAL_LLAMA_COMMIT="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [[ "$ACTUAL_LLAMA_COMMIT" != "$LLAMA_CPP_COMMIT" ]]; then
    echo "Checked out $ACTUAL_LLAMA_COMMIT, expected $LLAMA_CPP_COMMIT" >&2
    exit 1
fi

TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake"
if [[ ! -f "$TOOLCHAIN_FILE" ]]; then
    echo "Android CMake toolchain was not found at $TOOLCHAIN_FILE" >&2
    exit 1
fi

cmake -S "$ROOT/native" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_NDK="$NDK_ROOT" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DLLAMA_CPP_SOURCE_DIR="$SOURCE_DIR"

# PROVISIONAL - pending GEN-PERF-1
cmake --build "$BUILD_DIR" --target agent_llama --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"

OUTPUT_DIR="$ROOT/app/src/main/jniLibs/$ANDROID_ABI"
mkdir -p "$OUTPUT_DIR"
NATIVE_LIBRARY="$(find "$BUILD_DIR" -type f -name libagent_llama.so -print -quit)"
if [[ -z "$NATIVE_LIBRARY" ]]; then
    echo "CMake completed without producing libagent_llama.so" >&2
    exit 1
fi
cp "$NATIVE_LIBRARY" "$OUTPUT_DIR/libagent_llama.so"
LIBCXX_SHARED="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [[ ! -f "$LIBCXX_SHARED" ]]; then
    echo "NDK libc++_shared.so was not found at $LIBCXX_SHARED" >&2
    exit 1
fi
cp "$LIBCXX_SHARED" "$OUTPUT_DIR/libc++_shared.so"

READELF="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if [[ -x "$READELF" ]]; then
    ELF_HEADER="$("$READELF" -h "$OUTPUT_DIR/libagent_llama.so")"
    if [[ "$ELF_HEADER" != *AArch64* ]]; then
        echo "The JNI shared library is not an AArch64 ELF" >&2
        exit 1
    fi
fi

echo "Built CPU-only JNI library from llama.cpp $ACTUAL_LLAMA_COMMIT for $ANDROID_ABI ($ANDROID_PLATFORM)."
