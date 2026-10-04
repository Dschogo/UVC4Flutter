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

/// Snapshot of the live stream statistics.
class StreamStats {
  const StreamStats({
    this.isStreaming = false,
    this.fps = 0,
    this.sentBitrateKbps = 0,
    this.targetBitrate = 0,
    this.estimatedBandwidthBps,
    this.droppedFrames = 0,
    this.rttMs,
    this.packetLoss,
    this.gpuPercent,
    this.powerAmps,
    this.powerWatts,
    this.error,
  });

  factory StreamStats.fromMap(Map<Object?, Object?> map) => StreamStats(
        isStreaming: map['isStreaming'] as bool? ?? false,
        fps: (map['fps'] as num?)?.toDouble() ?? 0,
        sentBitrateKbps: (map['sentBitrateKbps'] as num?)?.toDouble() ?? 0,
        targetBitrate: (map['targetBitrate'] as num?)?.toInt() ?? 0,
        estimatedBandwidthBps:
            (map['estimatedBandwidthBps'] as num?)?.toInt(),
        droppedFrames: (map['droppedFrames'] as num?)?.toInt() ?? 0,
        rttMs: (map['rttMs'] as num?)?.toDouble(),
        packetLoss: (map['packetLoss'] as num?)?.toDouble(),
        gpuPercent: (map['gpu'] as num?)?.toDouble(),
        powerAmps: (map['powerAmps'] as num?)?.toDouble(),
        powerWatts: (map['powerWatts'] as num?)?.toDouble(),
        error: map['error'] as String?,
      );

  /// Whether the stream is currently running.
  final bool isStreaming;

  /// Measured output frame rate.
  final double fps;

  /// Bitrate written by the endpoint in kbit/s.
  final double sentBitrateKbps;

  /// Current encoder target bitrate in bits/s.
  final int targetBitrate;

  /// SRT estimated available bandwidth in bits/s, when available.
  final int? estimatedBandwidthBps;

  /// Frames/packets dropped or lost since the previous sample.
  final int droppedFrames;

  /// Round trip time in milliseconds, when available (SRT).
  final double? rttMs;

  /// Packet loss ratio in percent, when available (SRT).
  final double? packetLoss;

  /// GPU usage in percent, when the SoC exposes it.
  final double? gpuPercent;

  /// Instantaneous battery current in amperes.
  final double? powerAmps;

  /// Instantaneous power draw in watts.
  final double? powerWatts;

  /// Last stream error message, if any.
  final String? error;
}
