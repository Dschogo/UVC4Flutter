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
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import io.github.thibaultbee.streampack.core.utils.InternalStreamPackApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StreamPack video source backed by the native UVC GL fan-out.
 *
 * The native layer owns the fan-out and pushes frames to both the Flutter
 * preview surface and the encoder input surface. This class only forwards the
 * StreamPack surface to native and starts/stops the fan-out.
 */
@OptIn(InternalStreamPackApi::class)
class UvcVideoSource(
    val deviceId: Int,
    private val bridge: NativeUvcBridge,
    rotationDegrees: Int = 0,
) : IVideoSourceInternal, ISurfaceSourceInternal {

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow.asStateFlow()

    private val provider = UvcSourceInfoProvider(rotationDegrees)
    private val _infoProviderFlow = MutableStateFlow<ISourceInfoProvider>(provider)
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> =
        _infoProviderFlow.asStateFlow()

    // The native fan-out stamps frames with the UVC presentation timestamp, which
    // is based on System.nanoTime() (uptime). Declaring REALTIME made StreamPack
    // log "System time diverged, detected timebase UPTIME ..." and mishandle PTS.
    override val timebase: Timebase = Timebase.UPTIME

    private var output: Surface? = null

    fun setUserRotation(degrees: Int) {
        provider.relativeRotationDegrees = degrees
    }

    override suspend fun configure(config: VideoSourceConfig) {
        output?.let { bridge.fanoutSetEncode(deviceId, it) }
    }

    override suspend fun getOutput(): Surface? = output

    override suspend fun setOutput(surface: Surface) {
        output = surface
        bridge.fanoutSetEncode(deviceId, surface)
    }

    override suspend fun resetOutput() {
        output = null
        bridge.fanoutSetEncode(deviceId, null)
    }

    override suspend fun startStream() {
        if (_isStreamingFlow.value) {
            return
        }
        bridge.fanoutSetEncodeActive(deviceId, true)
        bridge.fanoutStart(deviceId)
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        if (!_isStreamingFlow.value) {
            return
        }
        bridge.fanoutSetEncodeActive(deviceId, false)
        _isStreamingFlow.value = false
    }

    override suspend fun release() {
        bridge.fanoutSetEncodeActive(deviceId, false)
        bridge.fanoutStop(deviceId)
        _isStreamingFlow.value = false
    }

    class Factory(
        private val deviceId: Int,
        private val bridge: NativeUvcBridge,
        private val rotationDegrees: Int = 0,
    ) : IVideoSource.Factory {
        override suspend fun create(
            context: Context,
            dispatcherProvider: IVideoDispatcherProvider,
        ): IVideoSourceInternal = UvcVideoSource(deviceId, bridge, rotationDegrees)

        override fun isSourceEquals(source: IVideoSourceInternal?): Boolean =
            (source as? UvcVideoSource)?.deviceId == deviceId
    }
}
