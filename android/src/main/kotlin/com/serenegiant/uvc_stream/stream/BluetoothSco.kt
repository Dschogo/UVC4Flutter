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
import android.media.AudioManager
import androidx.core.content.getSystemService

/**
 * Minimal Bluetooth SCO helper used when capturing audio from a Bluetooth
 * headset. SCO must be enabled before [android.media.AudioRecord] is created
 * with [android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION].
 */
class BluetoothSco(private val context: Context) {
    private val audioManager: AudioManager?
        get() = context.getSystemService()

    fun start(): Boolean {
        val manager = audioManager ?: return false
        @Suppress("DEPRECATION")
        manager.startBluetoothSco()
        @Suppress("DEPRECATION")
        manager.isBluetoothScoOn = true
        return true
    }

    fun stop() {
        val manager = audioManager ?: return
        @Suppress("DEPRECATION")
        manager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        manager.stopBluetoothSco()
    }
}
