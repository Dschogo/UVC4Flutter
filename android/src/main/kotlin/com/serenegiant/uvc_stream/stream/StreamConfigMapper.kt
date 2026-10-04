/**
 * uvc_stream
 * Copyright (c) 2024-2026 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.serenegiant.uvc_stream.stream

import android.media.AudioFormat
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import androidx.core.net.toUri
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.encoders.AudioCodecConfig
import io.github.thibaultbee.streampack.core.elements.encoders.VideoCodecConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.regulator.SimpleBitrateRegulator
import io.github.thibaultbee.streampack.core.regulator.controllers.IBitrateRegulatorController
import io.github.thibaultbee.streampack.core.regulator.controllers.intervalBitrateRegulatorControllerFactory
import io.github.thibaultbee.streampack.ext.rtmp.configuration.mediadescriptor.RtmpMediaDescriptor
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.intervalSrtBitrateRegulatorControllerFactory

/**
 * Converts the plain Dart maps sent over the MethodChannel into StreamPack
 * configuration objects. Keeping this mapping in one place makes the Dart side
 * independent of StreamPack types.
 */
object StreamConfigMapper {

    fun videoMimeType(codec: String?): String {
        if (codec.isNullOrBlank()) return MediaFormat.MIMETYPE_VIDEO_AVC
        if (codec.startsWith("video/")) return codec
        return when (codec.lowercase()) {
            "hevc", "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "av1" -> MediaFormat.MIMETYPE_VIDEO_AV1
            "vp9" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "apv" -> MediaFormat.MIMETYPE_VIDEO_APV
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
    }

    fun audioMimeType(codec: String?): String = when (codec?.lowercase()) {
        "opus" -> MediaFormat.MIMETYPE_AUDIO_OPUS
        else -> MediaFormat.MIMETYPE_AUDIO_AAC
    }

    @Suppress("UNCHECKED_CAST")
    fun videoConfig(map: Map<String, Any?>): VideoCodecConfig {
        val mimeType = videoMimeType(map["codec"] as? String)
        val bitrate = (map["bitrate"] as? Number)?.toInt() ?: 2_000_000
        val width = (map["width"] as? Number)?.toInt() ?: 1280
        val height = (map["height"] as? Number)?.toInt() ?: 720
        val fps = (map["fps"] as? Number)?.toInt() ?: 30
        val gopSeconds = (map["gopSeconds"] as? Number)?.toFloat() ?: 1f
        val profile = (map["profile"] as? Number)?.toInt()
        val level = (map["level"] as? Number)?.toInt()
        return VideoCodecConfig(
            mimeType = mimeType,
            startBitrate = bitrate,
            resolution = Size(width, height),
            fps = fps,
            gopDurationInS = gopSeconds,
            profileLevelColorBuilder = {
                profile?.let { this.profile = it }
                level?.let { this.level = it }
            },
        )
    }

    fun videoSourceConfig(map: Map<String, Any?>): VideoSourceConfig {
        val width = (map["width"] as? Number)?.toInt() ?: 1280
        val height = (map["height"] as? Number)?.toInt() ?: 720
        val fps = (map["fps"] as? Number)?.toInt() ?: 30
        return VideoSourceConfig(resolution = Size(width, height), fps = fps)
    }

    fun audioConfig(map: Map<String, Any?>): AudioCodecConfig {
        val mimeType = audioMimeType(map["codec"] as? String)
        val bitrate = (map["bitrate"] as? Number)?.toInt() ?: 128_000
        val sampleRate = (map["sampleRate"] as? Number)?.toInt() ?: 48_000
        val stereo = map["stereo"] as? Boolean ?: true
        return AudioCodecConfig(
            mimeType = mimeType,
            startBitrate = bitrate,
            sampleRate = sampleRate,
            channelConfig = if (stereo) {
                AudioFormat.CHANNEL_IN_STEREO
            } else {
                AudioFormat.CHANNEL_IN_MONO
            },
            byteFormat = AudioFormat.ENCODING_PCM_16BIT,
        )
    }

    fun audioSourceConfig(map: Map<String, Any?>): AudioSourceConfig {
        val sampleRate = (map["sampleRate"] as? Number)?.toInt() ?: 48_000
        val stereo = map["stereo"] as? Boolean ?: true
        return AudioSourceConfig(
            sampleRate = sampleRate,
            channelConfig = if (stereo) {
                AudioFormat.CHANNEL_IN_STEREO
            } else {
                AudioFormat.CHANNEL_IN_MONO
            },
            byteFormat = AudioFormat.ENCODING_PCM_16BIT,
        )
    }

    fun descriptor(protocol: String?, url: String): MediaDescriptor =
        when (protocol?.lowercase()) {
            "rtmp", "rtmps" -> RtmpMediaDescriptor(url.toUri())
            else -> SrtMediaDescriptor(url)
        }

    fun regulator(
        type: String?,
        minBitrate: Int,
        maxBitrate: Int,
        audioBitrate: Int,
    ): IBitrateRegulatorController.Factory? {
        val config = BitrateRegulatorConfig(
            videoBitrateRange = Range(minBitrate, maxBitrate),
            audioBitrateRange = Range(audioBitrate, audioBitrate),
        )
        return when (type?.lowercase()) {
            "simple", "rtmp" -> intervalBitrateRegulatorControllerFactory(
                bitrateRegulatorConfig = config,
            )

            "srt" -> intervalSrtBitrateRegulatorControllerFactory(
                bitrateRegulatorConfig = config,
            )

            "none", null -> null
            else -> intervalBitrateRegulatorControllerFactory(
                bitrateRegulatorConfig = config,
                bitrateRegulatorFactory = SimpleBitrateRegulator.Factory(),
            )
        }
    }
}
