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
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSource

enum class AudioSourceType {
    UVC,
    PHONE_MIC,
    BLUETOOTH_MIC;

    companion object {
        fun fromName(name: String?): AudioSourceType = when (name?.lowercase()) {
            "uvc" -> UVC
            "phone", "phonemic", "phone_mic" -> PHONE_MIC
            "bluetooth", "bluetoothmic", "bluetooth_mic" -> BLUETOOTH_MIC
            else -> UVC
        }
    }
}

/**
 * Resolves the selected [AudioSourceType] to a StreamPack [IAudioSource.Factory]
 * and manages the Bluetooth SCO lifecycle.
 */
class AudioSourceSelector(
    private val context: Context,
    private val deviceId: Int,
    private val bridge: NativeUvcBridge,
) {
    private val sco = BluetoothSco(context)
    private var scoActive = false

    var type: AudioSourceType = AudioSourceType.UVC

    fun factory(): IAudioSource.Factory = when (type) {
        AudioSourceType.UVC -> UvcAudioSource.Factory(deviceId, bridge)
        AudioSourceType.PHONE_MIC -> SystemAudioFactory.phoneMic()
        AudioSourceType.BLUETOOTH_MIC -> SystemAudioFactory.bluetoothMic()
    }

    fun acquire() {
        if ((type == AudioSourceType.BLUETOOTH_MIC) && !scoActive) {
            scoActive = sco.start()
        }
    }

    fun release() {
        if (scoActive) {
            sco.stop()
            scoActive = false
        }
    }
}
