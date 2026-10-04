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

import '../audio_source_type.dart';

/// Audio encoder / capture settings.
class AudioSettings {
  const AudioSettings({
    this.source = AudioSourceType.uvc,
    this.codec = 'aac',
    this.bitrate = 128000,
    this.sampleRate = 48000,
    this.stereo = true,
  });

  factory AudioSettings.fromMap(Map<Object?, Object?> map) => AudioSettings(
        source: AudioSourceType.values.firstWhere(
          (e) => e.wireName == map['source'],
          orElse: () => AudioSourceType.uvc,
        ),
        codec: map['codec'] as String? ?? 'aac',
        bitrate: (map['bitrate'] as num?)?.toInt() ?? 128000,
        sampleRate: (map['sampleRate'] as num?)?.toInt() ?? 48000,
        stereo: map['stereo'] as bool? ?? true,
      );

  /// Capture source.
  final AudioSourceType source;

  /// Encoder codec: `aac` or `opus`.
  final String codec;

  /// Target audio bitrate in bits/s.
  final int bitrate;

  /// Capture sample rate in Hz.
  final int sampleRate;

  /// Stereo when true, mono otherwise.
  final bool stereo;

  AudioSettings copyWith({
    AudioSourceType? source,
    String? codec,
    int? bitrate,
    int? sampleRate,
    bool? stereo,
  }) =>
      AudioSettings(
        source: source ?? this.source,
        codec: codec ?? this.codec,
        bitrate: bitrate ?? this.bitrate,
        sampleRate: sampleRate ?? this.sampleRate,
        stereo: stereo ?? this.stereo,
      );

  Map<String, Object?> toMap() => <String, Object?>{
        'source': source.wireName,
        'codec': codec,
        'bitrate': bitrate,
        'sampleRate': sampleRate,
        'stereo': stereo,
      };
}
