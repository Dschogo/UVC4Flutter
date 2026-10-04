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

import android.util.Size
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider

/**
 * Source info for a UVC camera: the sensor has no orientation, only the user
 * selected rotation is applied. Returning it from [getRelativeRotationDegrees]
 * lets StreamPack rotate the encoded frames (and swap the encoder resolution)
 * without the fan-out having to distort the preview.
 */
class UvcSourceInfoProvider(
    @Volatile var relativeRotationDegrees: Int = 0,
) : ISourceInfoProvider {
    override val rotationDegrees: Int
        get() = 0

    override val isMirror: Boolean
        get() = false

    override fun getRelativeRotationDegrees(
        targetRotation: Int,
        requiredMirroring: Boolean,
    ): Int = relativeRotationDegrees.within360()

    override fun getSurfaceSize(targetResolution: Size): Size = targetResolution

    override fun toString(): String =
        "UvcSourceInfoProvider(relative=$relativeRotationDegrees)"

    private fun Int.within360(): Int = ((this % 360) + 360) % 360
}
