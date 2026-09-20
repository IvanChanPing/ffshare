#!/usr/bin/env bash

set -euo pipefail

# Purpose: reproducibly generate FFShare's required FFmpegKitNext Android AAR.
# Invocation: ./build_ffmpegkit.sh [output-directory], or --check for a no-build preflight;
#             set FFMPEG_KIT_DOCKER_NETWORK when a custom Docker daemon has no default bridge.
# Contract: use upstream tag v8.1.1 and its pinned android-r27d Nix shell; never patch upstream
#           codec sources. Native output stays in an ignored resumable worktree until verified.
#           Docker networking is unchanged unless the caller explicitly supplies a network mode;
#           the ephemeral container trusts only its /workspace bind mount for Nix flake evaluation.
# Verification: run Bash/preflight checks, a mismatched-UID bind-mount probe, and the full AAR build.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT="${SCRIPT_DIR}/app/libs"
CHECK_ONLY=0

if [[ "${1:-}" == "--check" ]]; then
  CHECK_ONLY=1
elif [[ -n "${1:-}" ]]; then
  OUTPUT="$1"
fi

readonly FFMPEG_KIT_TAG_VERSION="v8.1.1"
readonly FFMPEG_KIT_VERSION="8.1.1"
readonly SOURCE_DIR="${FFMPEG_KIT_WORK_DIR:-${SCRIPT_DIR}/.ffmpeg-kit-next-build}"
readonly NIX_IMAGE="${FFMPEG_KIT_NIX_IMAGE:-nixos/nix:2.35.2}"
readonly JOBS="${FFMPEG_KIT_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '1')}"
readonly AAR_RELATIVE_PATH="prebuilt/bundle-android-aar-24-maven/com/arthenica/ffmpeg-kit-next/${FFMPEG_KIT_VERSION}/ffmpeg-kit-next-${FFMPEG_KIT_VERSION}.aar"
readonly DOCKER_NETWORK="${FFMPEG_KIT_DOCKER_NETWORK:-}"

DOCKER_NETWORK_ARGS=()
if [[ -n "${DOCKER_NETWORK}" ]]; then
  DOCKER_NETWORK_ARGS=(--network "${DOCKER_NETWORK}")
fi

BUILD_ARGS=(
  "--jobs=${JOBS}"
  --disable-x86 --disable-x86-64 --disable-arm-v7a-neon \
  --enable-dav1d \
  --enable-fontconfig \
  --enable-freetype \
  --enable-fribidi \
  --enable-gmp \
  --enable-kvazaar \
  --enable-lame \
  --enable-libass \
  --enable-libiconv \
  --enable-libilbc \
  --enable-libtheora \
  --enable-libvorbis \
  --enable-libvpx \
  --enable-libwebp \
  --enable-libxml2 \
  --enable-opencore-amr \
  --enable-opus \
  --enable-shine \
  --enable-snappy \
  --enable-soxr \
  --enable-speex \
  --enable-twolame \
  --enable-vo-amrwbenc \
  --enable-vvenc \
  --enable-zimg \
  --enable-gpl \
  --enable-libvidstab \
  --enable-x264 \
  --enable-x265 \
  --enable-xvidcore \
  --enable-libjxl
)

if ! command -v git >/dev/null 2>&1; then
  printf 'error: git is required to obtain FFmpegKitNext %s\n' "${FFMPEG_KIT_TAG_VERSION}" >&2
  exit 1
fi

if command -v nix >/dev/null 2>&1; then
  RUNNER="host Nix"
elif command -v docker >/dev/null 2>&1; then
  RUNNER="Docker image ${NIX_IMAGE}"
else
  printf 'error: install Nix or Docker before building FFmpegKitNext\n' >&2
  exit 1
fi

if ((CHECK_ONLY)); then
  printf 'FFmpegKitNext preflight OK\nrunner: %s\nsource: %s\noutput: %s\n' \
    "${RUNNER}" "${SOURCE_DIR}" "${OUTPUT}"
  if [[ "${RUNNER}" == Docker* ]]; then
    printf 'docker network: %s\n' "${DOCKER_NETWORK:-default}"
  fi
  exit 0
fi

if [[ -e "${SOURCE_DIR}" && ! -d "${SOURCE_DIR}/.git" ]]; then
  printf 'error: build workspace exists but is not a Git checkout: %s\n' "${SOURCE_DIR}" >&2
  exit 1
fi

if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
  git clone --branch "${FFMPEG_KIT_TAG_VERSION}" --depth=1 \
    "https://github.com/arthenica/ffmpeg-kit-next" "${SOURCE_DIR}"
fi

actual_tag="$(git -C "${SOURCE_DIR}" describe --tags --exact-match 2>/dev/null || true)"
if [[ "${actual_tag}" != "${FFMPEG_KIT_TAG_VERSION}" ]]; then
  printf 'error: expected FFmpegKitNext %s, found %s in %s\n' \
    "${FFMPEG_KIT_TAG_VERSION}" "${actual_tag:-an untagged revision}" "${SOURCE_DIR}" >&2
  exit 1
fi

if ! git -C "${SOURCE_DIR}" diff --quiet || ! git -C "${SOURCE_DIR}" diff --cached --quiet; then
  printf 'error: refusing to build from a modified FFmpegKitNext checkout: %s\n' "${SOURCE_DIR}" >&2
  exit 1
fi

if command -v nix >/dev/null 2>&1; then
  (
    cd "${SOURCE_DIR}"
    ./nix-android.sh -p android-r27d "${BUILD_ARGS[@]}"
  )
else
  docker run --rm \
    "${DOCKER_NETWORK_ARGS[@]}" \
    --volume "${SOURCE_DIR}:/workspace" \
    --workdir /workspace \
    --env $'NIX_CONFIG=experimental-features = nix-command flakes\naccept-flake-config = true\nwarn-dirty = false' \
    --entrypoint /root/.nix-profile/bin/bash \
    "${NIX_IMAGE}" -lc '
      set -euo pipefail
      mkdir -p /bin /usr/bin
      ln -sf /root/.nix-profile/bin/bash /bin/bash
      git config --global --add safe.directory /workspace
      nix develop .#android-r27d -c bash -lc '\''
        set -euo pipefail
        ln -sf "$(command -v perl)" /usr/bin/perl
        exec bash ./scripts/start-android.sh "$@"
      '\'' bash "$@"
    ' bash "${BUILD_ARGS[@]}"
fi

aar_path="${SOURCE_DIR}/${AAR_RELATIVE_PATH}"
if [[ ! -s "${aar_path}" ]]; then
  printf 'error: FFmpegKitNext completed without the expected AAR: %s\n' "${aar_path}" >&2
  exit 1
fi

mkdir -p "${OUTPUT}"
output_aar="${OUTPUT}/ffmpeg-kit-next-${FFMPEG_KIT_VERSION}.aar"
temporary_aar="${output_aar}.pending.$$"
cp "${aar_path}" "${temporary_aar}"
[[ "$(stat -c '%s' "${temporary_aar}")" == "$(stat -c '%s' "${aar_path}")" ]]
mv -f "${temporary_aar}" "${output_aar}"
sha256sum "${output_aar}"
