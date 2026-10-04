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

/**
 * UVC capture frame formats (mirrors uvc_raw_frame in aandusb_native.h).
 */
object UvcFrameType {
    const val UNKNOWN = 0x00000000
    const val UNCOMPRESSED_YUYV = 0x00010005
    const val UNCOMPRESSED_NV21 = 0x00050005
    const val UNCOMPRESSED_NV12 = 0x000b0005
    const val UNCOMPRESSED_RGB565 = 0x000d0005
    const val UNCOMPRESSED_RGBX = 0x00100005
    const val MJPEG = 0x00000007
    const val H264 = 0x00000014

    fun fromName(name: String?): Int = when (name?.lowercase()) {
        "yuyv", "yuy2" -> UNCOMPRESSED_YUYV
        "mjpeg", "mjpg" -> MJPEG
        "h264" -> H264
        else -> MJPEG
    }
}
