// Copyright (c) 2024-2026 saki t_saki@serenegiant.com
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/// A hardware-accelerated video encoder reported by the device.
class VideoEncoderInfo {
  const VideoEncoderInfo({required this.mime, required this.name});

  factory VideoEncoderInfo.fromMap(Map<Object?, Object?> map) => VideoEncoderInfo(
        mime: map['mime'] as String? ?? '',
        name: map['name'] as String? ?? '',
      );

  /// Encoder MIME type (e.g. `video/hevc`).
  final String mime;

  /// Codec implementation name.
  final String name;

  /// Human readable label.
  String get label => switch (mime) {
        'video/avc' => 'H.264 (AVC)',
        'video/hevc' => 'H.265 (HEVC)',
        'video/av01' => 'AV1',
        'video/apv' => 'APV',
        'video/x-vnd.on2.vp9' => 'VP9',
        'video/x-vnd.on2.vp8' => 'VP8',
        _ => mime,
      };
}
