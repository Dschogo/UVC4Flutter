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

import 'package:flutter/services.dart';

/// Thin wrapper around the plugin MethodChannel used by [StreamSession].
///
/// Keeping the channel isolated makes the session testable and avoids leaking
/// MethodChannel concerns into the app.
class StreamChannel {
  StreamChannel({MethodChannel? channel})
      : _channel =
            channel ?? const MethodChannel('com.serenegiant.flutter/aandusb_method');

  final MethodChannel _channel;

  Future<void> open(int deviceId) =>
      _channel.invokeMethod('stream.open', <String, Object?>{
        'deviceId': deviceId,
      });

  Future<void> close() => _channel.invokeMethod('stream.close');

  Future<void> start() => _channel.invokeMethod('stream.start');

  Future<void> stop() => _channel.invokeMethod('stream.stop');

  Future<void> setVideo(Map<String, Object?> settings) =>
      _channel.invokeMethod('stream.setVideo', <String, Object?>{
        'settings': settings,
      });

  Future<void> setAudio(Map<String, Object?> settings) =>
      _channel.invokeMethod('stream.setAudio', <String, Object?>{
        'settings': settings,
      });

  Future<void> setAudioSource(String source) =>
      _channel.invokeMethod('stream.setAudioSource', <String, Object?>{
        'source': source,
      });

  Future<void> setEndpoint(String protocol, String url) =>
      _channel.invokeMethod('stream.setEndpoint', <String, Object?>{
        'protocol': protocol,
        'url': url,
      });

  Future<void> setRegulator(
    String type,
    int minBitrate,
    int maxBitrate,
  ) =>
      _channel.invokeMethod('stream.setRegulator', <String, Object?>{
        'type': type,
        'minBitrate': minBitrate,
        'maxBitrate': maxBitrate,
      });

  Future<void> setRotation(int rotation) =>
      _channel.invokeMethod('stream.setRotation', <String, Object?>{
        'rotation': rotation,
      });

  Future<void> setTargetBitrate(int bitrate) =>
      _channel.invokeMethod('stream.setTargetBitrate', <String, Object?>{
        'bitrate': bitrate,
      });

  Future<void> setAudioMuted(bool muted) =>
      _channel.invokeMethod('stream.setAudioMuted', <String, Object?>{
        'muted': muted,
      });

  Future<void> setVideoMuted(bool muted) =>
      _channel.invokeMethod('stream.setVideoMuted', <String, Object?>{
        'muted': muted,
      });

  /// Current audio RMS level in 0..1 (for a VU meter).
  Future<double> getAudioLevel() async =>
      await _channel.invokeMethod<double>('stream.getAudioLevel') ?? 0.0;

  Future<Map<Object?, Object?>?> getStats() =>
      _channel.invokeMethod<Map<Object?, Object?>>('stream.getStats');

  Future<List<Map<Object?, Object?>>> getSupportedVideoEncoders() async =>
      await _channel.invokeListMethod<Map<Object?, Object?>>(
        'stream.getSupportedVideoEncoders',
      ) ??
      const [];

  Future<void> startPreview(int deviceId) =>
      _channel.invokeMethod('stream.startPreview', <String, Object?>{
        'deviceId': deviceId,
      });

  Future<void> stopPreview(int deviceId) =>
      _channel.invokeMethod('stream.stopPreview', <String, Object?>{
        'deviceId': deviceId,
      });

  Future<int> createPreviewTexture(int deviceId, int width, int height) async =>
      await _channel.invokeMethod<int>('createStreamPreviewTexture',
              <String, Object?>{
            'deviceId': deviceId,
            'width': width,
            'height': height,
          }) ??
      0;

  Future<void> releasePreviewTexture(int deviceId, int textureId) =>
      _channel.invokeMethod('releaseStreamPreviewTexture', <String, Object?>{
        'deviceId': deviceId,
        'textureId': textureId,
      });
}
