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

import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.processing.audio.IConsumerAudioEffect
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi

/**
 * Consumer audio effect that keeps [UvcAudioSource.currentLevel] up to date for
 * the VU meter. It is attached to the StreamPack audio processor so it works for
 * every audio source (UVC/UAC, phone mic, Bluetooth).
 */
@OptIn(InternalStreamPackApi::class)
class AudioLevelEffect : IConsumerAudioEffect {
    override fun consume(isMuted: Boolean, data: RawFrame) {
        val buffer = data.rawBuffer.duplicate()
        buffer.rewind()
        var sum = 0.0
        var count = 0
        while (buffer.remaining() >= 2) {
            val lo = buffer.get().toInt() and 0xFF
            val hi = buffer.get().toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
        }
        if (count == 0) {
            return
        }
        val rms = kotlin.math.sqrt(sum / count) / 32768.0
        UvcAudioSource.setCurrentLevelFromRms(rms.toFloat())
    }

    override fun close() {
        UvcAudioSource.clearLevel()
    }
}
