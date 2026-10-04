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
import android.media.AudioFormat
import io.github.thibaultbee.streampack.core.elements.encoders.AudioCodecConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.time.TimeUtils
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * StreamPack audio source reading PCM frames from the UVC device UAC interface
 * through the native JNI pull API.
 */
@OptIn(InternalStreamPackApi::class)
class UvcAudioSource(
    val deviceId: Int,
    private val bridge: NativeUvcBridge,
) : IAudioSourceInternal {

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow.asStateFlow()

    @Volatile
    private var _minBufferSize = DEFAULT_MIN_BUFFER_SIZE
    override val minBufferSize: Int
        get() = _minBufferSize

    @Volatile
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(DEFAULT_MIN_BUFFER_SIZE)

    // PCM bytes that did not fit the destination buffer and are carried over to
    // the next call. The native read reports its whole capacity, so a single UAC
    // frame can be larger than the encoder input buffer.
    private var pendingData = ByteArray(0)
    private var pendingOffset = 0
    private var lastTimestamp = 0L

    override suspend fun configure(config: AudioSourceConfig) {
        val channels = when (config.channelConfig) {
            AudioFormat.CHANNEL_IN_MONO -> 1
            else -> 2
        }
        val bytesPerSample =
            AudioCodecConfig.getNumOfBytesPerSample(config.byteFormat)
        val frameSize = (config.sampleRate / 50) * channels * bytesPerSample
        _minBufferSize = frameSize.coerceAtLeast(MIN_BUFFER_SIZE)
        scratch = ByteBuffer.allocateDirect(_minBufferSize)
    }

    override suspend fun startStream() {
        if (_isStreamingFlow.value) {
            return
        }
        bridge.startUacRead(deviceId)
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        _isStreamingFlow.value = false
    }

    override fun release() {
        _isStreamingFlow.value = false
    }

    override fun fillAudioFrame(buffer: ByteBuffer): Long {
        // Serve PCM carried over from the previous call first.
        drainPending(buffer)

        var waitedMs = 0
        val outLen = IntArray(1)

        while (buffer.remaining() > 0) {
            val chunk = scratch
            chunk.clear()
            outLen[0] = 0
            val pts = bridge.readUacFrame(deviceId, chunk, outLen)
            // The native side reads into its whole capacity (GetDirectBufferCapacity
            // ignores the Java limit) and reports that length. Clamp defensively and
            // buffer the remainder so we never overflow the destination ByteBuffer.
            val length = outLen[0].coerceIn(0, chunk.capacity())
            if (length > 0) {
                if (pts > 0) {
                    lastTimestamp = pts
                }
                val copied = minOf(length, buffer.remaining())
                chunk.position(0)
                chunk.limit(copied)
                applyGain(chunk, copied, gain)
                updateLevel(chunk, copied)
                buffer.put(chunk)
                if (copied < length) {
                    chunk.limit(length)
                    chunk.position(copied)
                    val rest = ByteArray(length - copied)
                    chunk.get(rest)
                    pendingData = rest
                    pendingOffset = 0
                }
                waitedMs = 0
            } else {
                if (!_isStreamingFlow.value) {
                    break
                }
                Thread.sleep(READ_RETRY_MS.toLong())
                waitedMs += READ_RETRY_MS
                if (waitedMs >= READ_TIMEOUT_MS) {
                    break
                }
            }
        }

        if (buffer.remaining() > 0) {
            val silence = ByteArray(buffer.remaining())
            buffer.put(silence)
        }
        // StreamPack reads a queued frame as offset=position(), size=limit()
        // (MediaCodecEncoder.queueInputFrame), so leave the buffer flipped:
        // offset 0, length = bytes written. Without this, position==limit==capacity
        // and MediaCodec throws "buffer offset and size goes beyond the capacity".
        buffer.flip()
        return if (lastTimestamp > 0) lastTimestamp else TimeUtils.currentTime()
    }

    private fun drainPending(buffer: ByteBuffer) {
        if (pendingOffset >= pendingData.size) {
            return
        }
        val count = minOf(pendingData.size - pendingOffset, buffer.remaining())
        buffer.put(pendingData, pendingOffset, count)
        pendingOffset += count
        if (pendingOffset >= pendingData.size) {
            pendingData = ByteArray(0)
            pendingOffset = 0
        }
    }

    /** Scales 16-bit little-endian PCM in place by [gain]. */
    private fun applyGain(buffer: ByteBuffer, length: Int, gain: Float) {
        if (gain == 1f) {
            return
        }
        var i = 0
        while (i + 1 < length) {
            val lo = buffer.get(i).toInt() and 0xFF
            val hi = buffer.get(i + 1).toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
            buffer.put(i, (scaled and 0xFF).toByte())
            buffer.put(i + 1, ((scaled shr 8) and 0xFF).toByte())
            i += 2
        }
    }

    /** Updates the process-wide VU level from 16-bit little-endian PCM. */
    private fun updateLevel(buffer: ByteBuffer, length: Int) {
        if (length < 2) {
            return
        }
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val lo = buffer.get(i).toInt() and 0xFF
            val hi = buffer.get(i + 1).toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += sample.toDouble() * sample
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / (length / 2)).toFloat()
        setCurrentLevelFromRms(rms / 32768f)
    }

    class Factory(
        private val deviceId: Int,
        private val bridge: NativeUvcBridge,
    ) : IAudioSource.Factory {
        override suspend fun create(context: Context): IAudioSourceInternal =
            UvcAudioSource(deviceId, bridge)

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean =
            (source as? UvcAudioSource)?.deviceId == deviceId
    }

    companion object {
        private const val DEFAULT_MIN_BUFFER_SIZE = 4096
        private const val MIN_BUFFER_SIZE = 1024
        private const val READ_RETRY_MS = 2

        // Never block the encoder input callback for long: a stalled audio read
        // delays the muxer and makes the SRT sender burst, which the receiver
        // drops (broken HEVC references). Pad with silence instead.
        private const val READ_TIMEOUT_MS = 100

        /** Latest audio level in 0..1 (VU meter), process-wide. */
        @Volatile
        private var _level = 0f

        val currentLevel: Float
            get() = _level

        /**
         * Sets the VU level from a 0..1 linear RMS, mapped to a perceptual
         * 0..1 scale (dBFS over -60..0 dB) so normal speech uses the full bar.
         */
        fun setCurrentLevelFromRms(rms: Float) {
            val db = if (rms > 1e-5f) {
                20f * kotlin.math.log10(rms.toDouble()).toFloat()
            } else {
                -60f
            }
            _level = ((db + 60f) / 60f).coerceIn(0f, 1f)
        }

        fun clearLevel() {
            _level = 0f
        }

        /** Output gain multiplier (0..2), process-wide. */
        @Volatile
        var gain: Float = 1f
    }
}
