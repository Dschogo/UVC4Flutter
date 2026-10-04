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

/// Video encoder / capture settings.
class VideoSettings {
  const VideoSettings({
    this.codec = 'video/hevc',
    this.width = 1920,
    this.height = 1080,
    this.fps = 30,
    this.bitrate = 6000000,
    this.gopSeconds = 1.0,
    this.profile,
    this.level,
    this.inputFormat = 'mjpeg',
  });

  factory VideoSettings.fromMap(Map<Object?, Object?> map) => VideoSettings(
        codec: map['codec'] as String? ?? 'video/hevc',
        width: (map['width'] as num?)?.toInt() ?? 1920,
        height: (map['height'] as num?)?.toInt() ?? 1080,
        fps: (map['fps'] as num?)?.toInt() ?? 30,
        bitrate: (map['bitrate'] as num?)?.toInt() ?? 6000000,
        gopSeconds: (map['gopSeconds'] as num?)?.toDouble() ?? 1.0,
        profile: (map['profile'] as num?)?.toInt(),
        level: (map['level'] as num?)?.toInt(),
        inputFormat: map['inputFormat'] as String? ?? 'mjpeg',
      );

  /// Encoder MIME type (e.g. `video/hevc`, `video/avc`, `video/av01`).
  final String codec;

  /// Target width in pixels.
  final int width;

  /// Target height in pixels.
  final int height;

  /// Target frame rate.
  final int fps;

  /// Target video bitrate in bits/s.
  final int bitrate;

  /// I-frame interval in seconds.
  final double gopSeconds;

  /// Optional encoder profile (MediaCodecInfo.CodecProfileLevel).
  final int? profile;

  /// Optional encoder level (MediaCodecInfo.CodecProfileLevel).
  final int? level;

  /// UVC capture frame format: `mjpeg` or `yuyv`.
  final String inputFormat;

  VideoSettings copyWith({
    String? codec,
    int? width,
    int? height,
    int? fps,
    int? bitrate,
    double? gopSeconds,
    int? profile,
    int? level,
    String? inputFormat,
  }) =>
      VideoSettings(
        codec: codec ?? this.codec,
        width: width ?? this.width,
        height: height ?? this.height,
        fps: fps ?? this.fps,
        bitrate: bitrate ?? this.bitrate,
        gopSeconds: gopSeconds ?? this.gopSeconds,
        profile: profile ?? this.profile,
        level: level ?? this.level,
        inputFormat: inputFormat ?? this.inputFormat,
      );

  Map<String, Object?> toMap() => <String, Object?>{
        'codec': codec,
        'width': width,
        'height': height,
        'fps': fps,
        'bitrate': bitrate,
        'gopSeconds': gopSeconds,
        'profile': profile,
        'level': level,
        'inputFormat': inputFormat,
      };
}
