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

import '../stream_protocol.dart';

/// Streaming endpoint settings.
class NetworkSettings {
  const NetworkSettings({
    this.protocol = StreamProtocol.srt,
    this.url = '',
    this.srtLatencyMs,
    this.srtPassphrase,
    this.srtStreamId,
  });

  factory NetworkSettings.fromMap(Map<Object?, Object?> map) => NetworkSettings(
        protocol: StreamProtocol.values.firstWhere(
          (e) => e.wireName == map['protocol'],
          orElse: () => StreamProtocol.srt,
        ),
        url: map['url'] as String? ?? '',
        srtLatencyMs: (map['srtLatencyMs'] as num?)?.toInt(),
        srtPassphrase: map['srtPassphrase'] as String?,
        srtStreamId: map['srtStreamId'] as String?,
      );

  /// Selected protocol.
  final StreamProtocol protocol;

  /// Endpoint URL (e.g. `srt://host:9000` or `rtmp://host/app/key`).
  final String url;

  /// SRT latency in milliseconds.
  final int? srtLatencyMs;

  /// SRT passphrase.
  final String? srtPassphrase;

  /// SRT stream id.
  final String? srtStreamId;

  NetworkSettings copyWith({
    StreamProtocol? protocol,
    String? url,
    int? srtLatencyMs,
    String? srtPassphrase,
    String? srtStreamId,
  }) =>
      NetworkSettings(
        protocol: protocol ?? this.protocol,
        url: url ?? this.url,
        srtLatencyMs: srtLatencyMs ?? this.srtLatencyMs,
        srtPassphrase: srtPassphrase ?? this.srtPassphrase,
        srtStreamId: srtStreamId ?? this.srtStreamId,
      );

  Map<String, Object?> toMap() => <String, Object?>{
        'protocol': protocol.wireName,
        'url': url,
        'srtLatencyMs': srtLatencyMs,
        'srtPassphrase': srtPassphrase,
        'srtStreamId': srtStreamId,
      };

  /// Returns [url] with the protocol scheme and SRT options applied.
  String resolveUrl() {
    var base = url.trim();
    if (base.isEmpty) {
      return base;
    }
    final scheme = protocol.wireName;
    if (!base.contains('://')) {
      base = '$scheme://$base';
    }
    if (protocol != StreamProtocol.srt) {
      return base;
    }
    final uri = Uri.tryParse(base);
    if (uri == null) {
      return base;
    }
    final params = <String, String>{...uri.queryParameters};
    if (srtLatencyMs != null) {
      params.putIfAbsent('latency', () => '$srtLatencyMs');
    }
    if (srtPassphrase != null && srtPassphrase!.isNotEmpty) {
      params.putIfAbsent('passphrase', () => srtPassphrase!);
    }
    if (srtStreamId != null && srtStreamId!.isNotEmpty) {
      params.putIfAbsent('streamid', () => srtStreamId!);
    }
    return uri.replace(queryParameters: params).toString();
  }

  /// [url] without its `scheme://` prefix (for editor fields that show the
  /// scheme as a fixed prefix).
  String get urlWithoutScheme {
    final index = url.indexOf('://');
    return index >= 0 ? url.substring(index + 3) : url;
  }
}
