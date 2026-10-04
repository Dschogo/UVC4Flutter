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

import android.content.Context
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.metrics.BasicEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.WithEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.writtenBitrateInBps
import io.github.thibaultbee.streampack.core.elements.utils.extensions.isNaturalToPortrait
import io.github.thibaultbee.streampack.core.regulator.controllers.IBitrateRegulatorController
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.ext.srt.elements.endpoints.SrtEndpointMetrics

/**
 * Owns a StreamPack [SingleStreamer] and exposes a small, StreamPack-agnostic
 * API to the Flutter layer. All calls must run on a background dispatcher.
 */
class StreamSession(
    private val context: Context,
    private val bridge: NativeUvcBridge,
) {
    private var deviceId: Int = -1
    private var streamer: SingleStreamer? = null
    private var selector: AudioSourceSelector? = null
    private val systemStats = SystemStatsCollector(context)
    private val audioLevelEffect = AudioLevelEffect()

    private val videoSettings = mutableMapOf<String, Any?>()
    private val audioSettings = mutableMapOf<String, Any?>()

    private var protocol: String = "srt"
    private var url: String = ""
    private var regulatorType: String = "none"
    private var rotation: Int = Surface.ROTATION_0

    private var appliedWidth: Int = -1
    private var appliedHeight: Int = -1
    private var appliedFrameType: Int = -1

    private var lastMetrics: BasicEndpointMetrics? = null

    val isStreaming: Boolean
        get() = streamer?.isStreamingFlow?.value == true

    val lastError: String?
        get() = streamer?.throwableFlow?.value?.message

    fun open(deviceId: Int) {
        require(deviceId > 0) { "Invalid deviceId $deviceId" }
        this.deviceId = deviceId
        selector = AudioSourceSelector(context, deviceId, bridge)
    }

    fun setVideo(settings: Map<String, Any?>) {
        videoSettings.putAll(settings)
        if ((deviceId > 0) && !isStreaming) {
            applyInputFormat()
        }
    }

    fun setAudio(settings: Map<String, Any?>) {
        audioSettings.putAll(settings)
    }

    fun setAudioSource(type: AudioSourceType) {
        selector?.type = type
    }

    fun setEndpoint(protocol: String, url: String) {
        this.protocol = protocol
        this.url = url
    }

    @Suppress("UNUSED_PARAMETER")
    fun setRegulator(type: String, minBitrate: Int, maxBitrate: Int) {
        // Bounds are derived from the video target bitrate in regulator();
        // min/max are accepted for API compatibility.
        this.regulatorType = type
    }

    suspend fun setRotation(rotation: Int) {
        this.rotation = ((rotation % 4) + 4) % 4
        val streamer = streamer ?: return
        val width = (videoSettings["width"] as? Number)?.toInt() ?: 1920
        val height = (videoSettings["height"] as? Number)?.toInt() ?: 1080
        streamer.setTargetRotation((targetRotationFor(width, height) + this.rotation) % 4)
        (streamer.videoInput.sourceFlow.value as? UvcVideoSource)
            ?.setUserRotation(this.rotation * 90)
    }

    suspend fun start() {
        require(deviceId > 0) { "Session is not opened" }
        require(url.isNotBlank()) { "Endpoint URL is not set" }
        val streamer = ensureStreamer()
        if (streamer.isStreamingFlow.value) {
            return
        }

        applyInputFormat()

        val videoWidth = (videoSettings["width"] as? Number)?.toInt() ?: 1920
        val videoHeight = (videoSettings["height"] as? Number)?.toInt() ?: 1080
        // StreamPack rotates the encoder resolution from the device natural
        // orientation based on the target rotation. The base rotation keeps the
        // requested resolution untouched; the user rotation is added on top so
        // StreamPack rotates the encoded frames and swaps the resolution.
        streamer.setTargetRotation(
            (targetRotationFor(videoWidth, videoHeight) + rotation) % 4
        )

        if (videoSettings.isNotEmpty()) {
            streamer.setVideoConfig(StreamConfigMapper.videoConfig(videoSettings))
            streamer.setVideoSource(
                UvcVideoSource.Factory(deviceId, bridge, rotation * 90)
            )
        }

        val selector = selector
        if (selector != null) {
            if (audioSettings.isNotEmpty()) {
                streamer.setAudioConfig(StreamConfigMapper.audioConfig(audioSettings))
            }
            streamer.setAudioSource(selector.factory())
            selector.acquire()
            // Feed the VU meter for any audio source (UVC, phone mic, BT).
            try {
                val processor = streamer.audioInput.processor
                if (!processor.contains(audioLevelEffect)) {
                    processor.add(audioLevelEffect)
                }
            } catch (_: Throwable) {
                // Audio input not ready; the VU meter stays at zero.
            }
        }

        streamer.bitrateRegulatorControllerFactory = regulator()
        lastMetrics = null

        streamer.startStream(StreamConfigMapper.descriptor(protocol, url))
    }

    suspend fun stop() {
        val streamer = streamer ?: return
        if (streamer.isStreamingFlow.value) {
            streamer.stopStream()
        }
        selector?.release()
    }

    suspend fun close() {
        selector?.release()
        streamer?.release()
        streamer = null
        lastMetrics = null
    }

    fun setTargetBitrate(bitrate: Int) {
        streamer?.videoEncoder?.bitrate = bitrate
    }

    /** Mutes/unmutes the audio track. */
    fun setAudioMuted(muted: Boolean) {
        streamer?.audioInput?.isMuted = muted
    }

    /** Mutes/unmutes the video track (sends black frames). */
    fun setVideoMuted(muted: Boolean) {
        streamer?.videoInput?.isMuted = muted
    }

    fun stats(): Map<String, Any?> {
        val streamer = streamer
        val metrics = (streamer?.endpoint as? WithEndpointMetrics<*>)?.metrics
        var sentBitrateKbps = 0.0
        var droppedFrames = 0
        if (metrics != null) {
            val previous = lastMetrics
            lastMetrics = metrics
            if (previous != null) {
                val delta = metrics - previous
                sentBitrateKbps = delta.writtenBitrateInBps / 1000.0
                droppedFrames =
                    (delta.packetsWriteDropped + delta.packetsWriteLost).toInt()
            }
        }

        val srt = readSrtStats(metrics)

        return mapOf(
            "isStreaming" to (streamer?.isStreamingFlow?.value ?: false),
            "sentBitrateKbps" to sentBitrateKbps,
            "droppedFrames" to droppedFrames,
            "targetBitrate" to (streamer?.videoEncoder?.bitrate ?: 0),
            "estimatedBandwidthBps" to srt.estimatedBandwidthBps,
            "rttMs" to srt.rttMs,
            "packetLoss" to srt.packetLoss,
            "error" to lastError,
        ) + systemStats.sample()
    }

    private data class SrtStats(
        val estimatedBandwidthBps: Long?,
        val rttMs: Double?,
        val packetLoss: Double?,
    )

    private fun readSrtStats(metrics: BasicEndpointMetrics?): SrtStats {
        val srtMetrics = metrics as? SrtEndpointMetrics ?: return SrtStats(null, null, null)
        val stats = srtMetrics.rawMetrics.bistatsOrNull(clear = false, instantaneous = true)
            ?: srtMetrics.rawMetrics.bstatsOrNull(clear = false)
            ?: return SrtStats(null, null, null)
        val bandwidth = if (stats.mbpsBandwidth > 0.0) {
            (stats.mbpsBandwidth * 1_000_000.0).toLong()
        } else {
            null
        }
        val totalSent = stats.pktSentTotal
        val lost = (stats.pktSndLossTotal + stats.pktSndDropTotal).toLong()
        val loss = if (totalSent > 0) lost * 100.0 / totalSent else 0.0
        return SrtStats(
            estimatedBandwidthBps = bandwidth,
            rttMs = stats.msRTT,
            packetLoss = loss,
        )
    }

    private fun ensureStreamer(): SingleStreamer {
        this.streamer?.let { return it }
        val created = SingleStreamer(
            context = context,
            defaultRotation = Surface.ROTATION_0,
        )
        this.streamer = created
        return created
    }

    private fun applyInputFormat() {
        val width = (videoSettings["width"] as? Number)?.toInt() ?: return
        val height = (videoSettings["height"] as? Number)?.toInt() ?: return
        val frameType = UvcFrameType.fromName(videoSettings["inputFormat"] as? String)
        if ((width == appliedWidth) && (height == appliedHeight) &&
            (frameType == appliedFrameType)) {
            return
        }
        bridge.setVideoSize(deviceId, frameType, width, height)
        appliedWidth = width
        appliedHeight = height
        appliedFrameType = frameType
    }

    private fun targetRotationFor(width: Int, height: Int): Int {
        val wantPortrait = height > width
        return if (context.isNaturalToPortrait == wantPortrait) {
            Surface.ROTATION_0
        } else {
            Surface.ROTATION_90
        }
    }

    private fun regulator(): IBitrateRegulatorController.Factory? {
        val videoBitrate = (videoSettings["bitrate"] as? Number)?.toInt() ?: 6_000_000
        // The user's target bitrate is the regulator ceiling: the adaptive
        // regulator must never exceed it.
        val min = maxOf(videoBitrate / 4, 200_000)
        val max = videoBitrate
        return StreamConfigMapper.regulator(
            type = regulatorType,
            minBitrate = min,
            maxBitrate = max,
            audioBitrate = (audioSettings["bitrate"] as? Number)?.toInt() ?: 128_000,
        )
    }
}
