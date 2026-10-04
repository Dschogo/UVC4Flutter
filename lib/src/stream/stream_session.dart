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

import 'dart:async';

import 'models/audio_settings.dart';
import 'models/network_settings.dart';
import 'models/stream_stats.dart';
import 'models/video_encoder_info.dart';
import 'models/video_settings.dart';
import 'regulator_type.dart';
import 'stream_channel.dart';

/// Queries the hardware-accelerated video encoders available on the device.
///
/// Does not require an open [StreamSession].
Future<List<VideoEncoderInfo>> queryVideoEncoders([StreamChannel? channel]) async {
  final ch = channel ?? StreamChannel();
  final raw = await ch.getSupportedVideoEncoders();
  return raw.map(VideoEncoderInfo.fromMap).toList();
}

/// High level live streaming session.
///
/// The app interacts only with this class; StreamPack types stay native.
class StreamSession {
  StreamSession({
    StreamChannel? channel,
    this.statsInterval = const Duration(seconds: 1),
  }) : _channel = channel ?? StreamChannel();

  final StreamChannel _channel;

  /// Polling interval for [stats].
  final Duration statsInterval;

  int? _deviceId;
  int? _previewTextureId;

  VideoSettings _video = const VideoSettings();
  AudioSettings _audio = const AudioSettings();
  NetworkSettings _network = const NetworkSettings();
  RegulatorType _regulator = RegulatorType.simple;

  Timer? _statsTimer;
  StreamStats _latestStats = const StreamStats();
  final StreamController<StreamStats> _statsController =
      StreamController<StreamStats>.broadcast();

  /// Currently configured video settings.
  VideoSettings get video => _video;

  /// Currently configured audio settings.
  AudioSettings get audio => _audio;

  /// Currently configured network settings.
  NetworkSettings get network => _network;

  /// Currently configured regulator.
  RegulatorType get regulator => _regulator;

  /// Last received stats snapshot.
  StreamStats get latestStats => _latestStats;

  /// Broadcast stream of periodic stats snapshots.
  Stream<StreamStats> get stats => _statsController.stream;

  /// Opens a session for the given UVC device id.
  Future<void> open(int deviceId) async {
    _deviceId = deviceId;
    await _channel.open(deviceId);
  }

  /// Applies video settings (takes effect immediately when idle, otherwise on
  /// the next [start]).
  Future<void> setVideo(VideoSettings settings) async {
    _video = settings;
    await _channel.setVideo(settings.toMap());
  }

  /// Applies audio settings and selects the audio source.
  Future<void> setAudio(AudioSettings settings) async {
    _audio = settings;
    await _channel.setAudio(settings.toMap());
    await _channel.setAudioSource(settings.source.wireName);
  }

  /// Applies the network endpoint and bitrate regulator.
  Future<void> setNetwork(NetworkSettings settings, RegulatorType regulator) async {
    _network = settings;
    _regulator = regulator;
    await _channel.setEndpoint(settings.protocol.wireName, settings.resolveUrl());
    await _channel.setRegulator(
      regulator.wireName,
      _minBitrate,
      _maxBitrate,
    );
  }

  /// Starts streaming.
  Future<void> start() async {
    await _channel.start();
    _startStatsPolling();
  }

  /// Stops streaming.
  Future<void> stop() async {
    _stopStatsPolling();
    await _channel.stop();
  }

  /// Creates a Flutter texture that receives the native preview and returns its
  /// texture id (to be used with a `Texture` widget).
  Future<int> createPreviewTexture(int width, int height) async {
    final deviceId = _deviceId;
    if (deviceId == null) {
      throw StateError('Session is not opened');
    }
    final textureId = await _channel.createPreviewTexture(deviceId, width, height);
    _previewTextureId = textureId;
    return textureId;
  }

  /// Changes the live encoder target bitrate (bits/s).
  Future<void> setTargetBitrate(int bitrate) =>
      _channel.setTargetBitrate(bitrate);

  /// Mutes or unmutes the audio track.
  Future<void> setAudioMuted(bool muted) => _channel.setAudioMuted(muted);

  /// Mutes or unmutes the video track (sends black frames).
  Future<void> setVideoMuted(bool muted) => _channel.setVideoMuted(muted);

  /// Current audio RMS level in 0..1 (for a VU meter).
  Future<double> audioLevel() => _channel.getAudioLevel();

  /// Sets the output gain multiplier (0..2).
  Future<void> setAudioGain(double gain) => _channel.setAudioGain(gain);

  /// Starts the native preview fan-out (also started automatically by
  /// [createPreviewTexture]).
  Future<void> startPreview() async {
    final deviceId = _deviceId;
    if (deviceId != null) {
      await _channel.startPreview(deviceId);
    }
  }

  /// Stops the native preview fan-out (needed before changing the capture
  /// resolution).
  Future<void> stopPreview() async {
    final deviceId = _deviceId;
    if (deviceId != null) {
      await _channel.stopPreview(deviceId);
    }
  }

  /// Rotates preview and encoded output (Surface.ROTATION_*).
  Future<void> setRotation(int surfaceRotation) =>
      _channel.setRotation(surfaceRotation);

  /// Releases the session and the preview texture.
  Future<void> close() async {
    _stopStatsPolling();
    await _channel.close();
    final deviceId = _deviceId;
    final textureId = _previewTextureId;
    if ((deviceId != null) && (textureId != null)) {
      await _channel.releasePreviewTexture(deviceId, textureId);
      _previewTextureId = null;
    }
    await _statsController.close();
  }

  void _startStatsPolling() {
    _statsTimer?.cancel();
    _statsTimer = Timer.periodic(statsInterval, (_) => _refreshStats());
  }

  void _stopStatsPolling() {
    _statsTimer?.cancel();
    _statsTimer = null;
  }

  Future<void> _refreshStats() async {
    if (_statsController.isClosed) {
      return;
    }
    try {
      final map = await _channel.getStats();
      _latestStats = StreamStats.fromMap(map ?? const <Object?, Object?>{});
      _statsController.add(_latestStats);
    } catch (_) {
      // Ignore transient failures during teardown.
    }
  }

  int get _minBitrate => (_video.bitrate ~/ 4).clamp(200000, 50000000);

  int get _maxBitrate => ((_video.bitrate * 3) ~/ 2).clamp(200000, 80000000);
}
