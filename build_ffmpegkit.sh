#!/bin/sh

FFMPEG_KIT_TAG_VERSION=v8.1.1
FFMPEG_KIT_DIRECTORY=ffmpeg-kit-next

rm -rf "./$FFMPEG_KIT_DIRECTORY"

git clone --branch $FFMPEG_KIT_TAG_VERSION --depth=1 "https://github.com/arthenica/ffmpeg-kit-next" "$FFMPEG_KIT_DIRECTORY"

cd "$FFMPEG_KIT_DIRECTORY"


# future
# --enable-libaom
# --enable-libjxl

./nix-android.sh -p android-r27d \
  --disable-x86 --disable-x86-64 --disable-arm-v7a-neon \
  --enable-dav1d \
  --enable-fontconfig \
  --enable-freetype \
  --enable-fribidi \
  --enable-gmp \
  --enable-gnutls \
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
  --enable-xvidcore

