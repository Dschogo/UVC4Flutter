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

/// Congestion-adaptive bitrate regulator.
enum RegulatorType {
  /// Fixed target bitrate, no adaptation.
  none,

  /// Generic loss-based regulator.
  simple,

  /// SRT-aware regulator using SRT send statistics.
  srt,

  /// RTMP regulator (uses the generic loss-based regulator).
  rtmp;

  String get wireName => switch (this) {
        RegulatorType.none => 'none',
        RegulatorType.simple => 'simple',
        RegulatorType.srt => 'srt',
        RegulatorType.rtmp => 'rtmp',
      };
}
