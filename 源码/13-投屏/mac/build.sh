#!/bin/bash
# 编译 Mac 端投屏服务
set -e
cd "$(dirname "$0")"
swiftc -O -parse-as-library -swift-version 5 -o mirrord mirrord.swift \
  -framework ScreenCaptureKit \
  -framework CoreMedia \
  -framework CoreVideo \
  -framework CoreImage \
  -framework ImageIO \
  -framework AppKit
echo "OK -> $(pwd)/mirrord"
