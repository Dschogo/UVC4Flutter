/**
 * uvc_stream
 * Copyright (c) 2020-2026 saki t_saki@serenegiant.com
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
package com.serenegiant.uvc_stream

import android.app.Activity
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.util.SparseArray
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.core.util.forEach
import com.serenegiant.uvc_stream.stream.AudioSourceType
import com.serenegiant.uvc_stream.stream.NativeUvcBridge
import com.serenegiant.uvc_stream.stream.StreamSession
import com.serenegiant.usb.DeviceDetector
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

@Keep
class UvcStreamPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, NativeUvcBridge {
    private lateinit var mChannel: MethodChannel
    private lateinit var mTextureRegistry: TextureRegistry
    private lateinit var mActivity: WeakReference<Activity>

    private val mSurfaceProducers = SparseArray<TextureRegistry.SurfaceProducer>()
    private val mStreamProducers = SparseArray<TextureRegistry.SurfaceProducer>()

    private var mNeedInitialize = false
    private var mSession: StreamSession? = null

    private val mScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        if (DEBUG) Log.v(TAG, "onAttachedToEngine:")
        NativeLibLoader.loadNative()
        nativeInit()
        mTextureRegistry = flutterPluginBinding.textureRegistry
        mChannel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME)
        mChannel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        if (DEBUG) Log.v(TAG, "onDetachedFromEngine:")
        mChannel.setMethodCallHandler(null)
        mScope.cancel()
        nativeRelease()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        if (DEBUG) Log.v(TAG, "onAttachedToActivity:")
        mActivity = WeakReference(binding.activity)
        if (mNeedInitialize) {
            mNeedInitialize = false
            DeviceDetector.initUVCDeviceDetector(binding.activity)
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        if (DEBUG) Log.v(TAG, "onDetachedFromActivityForConfigChanges:")
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        if (DEBUG) Log.v(TAG, "onReattachedToActivityForConfigChanges:")
        mActivity = WeakReference(binding.activity)
    }

    override fun onDetachedFromActivity() {
        if (DEBUG) Log.v(TAG, "onDetachedFromActivity:")
        mNeedInitialize = false
        releaseTextureAll()
        releaseStreamTextureAll()
        val activity = mActivity.get()
        if (activity != null) {
            DeviceDetector.releaseDeviceDetector(activity)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (DEBUG) Log.v(TAG, "onMethodCall:${call.method}")
        when (call.method) {
            "initialize" -> {
                val activity = mActivity.get()
                if (activity != null) {
                    DeviceDetector.initUVCDeviceDetector(activity)
                } else {
                    mNeedInitialize = true
                }
            }

            "createTexture" -> {
                val deviceId: Int? = call.argument("deviceId")
                val width: Int? = call.argument("width")
                val height: Int? = call.argument("height")
                if ((deviceId != null) && (width != null) && (height != null)) {
                    result.success(createTexture(deviceId, width, height))
                } else {
                    result.error("failed to get deviceId/width/height", null, null)
                }
            }

            "releaseTexture" -> {
                val deviceId: Int? = call.argument("deviceId")
                val textureId = call.argument<Number>("textureId")?.toLong()
                if ((deviceId != null) && (textureId != null)) {
                    releaseTexture(deviceId, textureId)
                }
                result.success(null)
            }

            "createStreamPreviewTexture" -> {
                val deviceId: Int? = call.argument("deviceId")
                val width: Int? = call.argument("width")
                val height: Int? = call.argument("height")
                if ((deviceId != null) && (width != null) && (height != null)) {
                    // TextureRegistry.SurfaceProducer is @UiThread; create the
                    // producer here (onMethodCall runs on the main thread) and
                    // only the native fan-out work is moved off the UI thread.
                    result.success(setUpStreamPreviewTexture(deviceId, width, height))
                } else {
                    result.error("failed to get deviceId/width/height", null, null)
                }
            }

            "releaseStreamPreviewTexture" -> {
                val deviceId: Int? = call.argument("deviceId")
                if (deviceId != null) {
                    tearDownStreamPreviewTexture(deviceId)
                }
                result.success(null)
            }

            "keepScreenOn" -> handleKeepScreenOn(call, result)

            "stream.open" -> {
                val deviceId: Int? = call.argument("deviceId")
                if (deviceId == null) {
                    result.error("invalid deviceId", null, null)
                    return
                }
                val activity = mActivity.get()
                if (activity == null) {
                    result.error("no activity", null, null)
                    return
                }
                val session = StreamSession(activity.applicationContext, this)
                session.open(deviceId)
                mSession = session
                result.success(null)
            }

            "stream.close" -> launchAsync(result) { mSession?.close(); mSession = null }

            "stream.setVideo" -> {
                @Suppress("UNCHECKED_CAST")
                val settings = call.argument<Map<String, Any?>>("settings")
                if (settings == null) {
                    result.error("invalid settings", null, null)
                } else {
                    launchAsync(result) { mSession?.setVideo(settings) }
                }
            }

            "stream.setAudio" -> {
                @Suppress("UNCHECKED_CAST")
                val settings = call.argument<Map<String, Any?>>("settings")
                if (settings == null) {
                    result.error("invalid settings", null, null)
                } else {
                    launchAsync(result) { mSession?.setAudio(settings) }
                }
            }

            "stream.setAudioSource" -> {
                mSession?.setAudioSource(
                    AudioSourceType.fromName(call.argument("source"))
                )
                result.success(null)
            }

            "stream.setEndpoint" -> {
                val protocol: String? = call.argument("protocol")
                val url: String? = call.argument("url")
                if (url == null) {
                    result.error("invalid url", null, null)
                    return
                }
                mSession?.setEndpoint(protocol ?: "srt", url)
                result.success(null)
            }

            "stream.setRegulator" -> {
                val type: String? = call.argument("type")
                val min: Int = call.argument<Number>("minBitrate")?.toInt() ?: 500_000
                val max: Int = call.argument<Number>("maxBitrate")?.toInt() ?: 10_000_000
                mSession?.setRegulator(type ?: "none", min, max)
                result.success(null)
            }

            "stream.setRotation" -> {
                val rotation: Int = call.argument<Number>("rotation")?.toInt()
                    ?: Surface.ROTATION_0
                launchAsync(result) { mSession?.setRotation(rotation) }
            }

            "stream.setTargetBitrate" -> {
                val bitrate: Int = call.argument<Number>("bitrate")?.toInt() ?: 0
                mSession?.setTargetBitrate(bitrate)
                result.success(null)
            }

            "stream.setAudioMuted" -> {
                val muted: Boolean = call.argument<Boolean>("muted") ?: false
                mSession?.setAudioMuted(muted)
                result.success(null)
            }

            "stream.setVideoMuted" -> {
                val muted: Boolean = call.argument<Boolean>("muted") ?: false
                mSession?.setVideoMuted(muted)
                result.success(null)
            }

            "stream.start" -> launchAsync(result) { mSession?.start() }

            "stream.stop" -> launchAsync(result) { mSession?.stop() }

            "stream.getStats" -> result.success(
                try {
                    mSession?.stats() ?: emptyMap<String, Any?>()
                } catch (t: Throwable) {
                    if (DEBUG) Log.w(TAG, t)
                    mapOf<String, Any?>("error" to (t.message ?: "stats error"))
                }
            )

            "stream.getSupportedVideoEncoders" -> result.success(supportedVideoEncoders())

            "stream.startPreview" -> {
                val deviceId: Int? = call.argument("deviceId")
                if (deviceId != null) {
                    launchAsync(result) { nativeFanoutStart(deviceId) }
                } else {
                    result.error("invalid deviceId", null, null)
                }
            }

            "stream.stopPreview" -> {
                val deviceId: Int? = call.argument("deviceId")
                if (deviceId != null) {
                    launchAsync(result) { nativeFanoutStop(deviceId) }
                } else {
                    result.error("invalid deviceId", null, null)
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun handleKeepScreenOn(call: MethodCall, result: Result) {
        val activity = mActivity.get()
        if (activity == null) {
            result.error("No Activity", null, null)
            return
        }
        val window = activity.window
        val onoff: Boolean? = call.argument("onoff")
        if (window != null) {
            activity.runOnUiThread {
                if (onoff == true) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        result.success(null)
    }

    private fun <T> launchAsync(result: Result, block: suspend () -> T) {
        mScope.launch {
            try {
                val value = withContext(Dispatchers.Default) { block() }
                // Channel responses cannot encode kotlin.Unit.
                result.success(if (value is Unit) null else value)
            } catch (t: Throwable) {
                if (DEBUG) Log.w(TAG, t)
                result.error("stream_error", t.message ?: t.javaClass.simpleName, null)
            }
        }
    }

    private fun createTexture(deviceId: Int, width: Int, height: Int): Long {
        if (DEBUG) Log.v(TAG, "createTexture:deviceId=$deviceId/(${width}x$height)")
        val producer = mSurfaceProducers[deviceId] ?: mTextureRegistry.createSurfaceProducer()
        producer.setSize(width, height)
        mSurfaceProducers[deviceId] = producer
        nativeSetSurface(deviceId, producer.id(), producer.surface)
        return producer.id()
    }

    private fun releaseTexture(deviceId: Int, textureId: Long) {
        nativeSetSurface(deviceId, -1, null)
        mSurfaceProducers.get(deviceId)?.release()
        mSurfaceProducers.remove(deviceId)
    }

    private fun releaseTextureAll() {
        mSurfaceProducers.forEach { _, producer -> producer.release() }
        mSurfaceProducers.clear()
    }

    private fun setUpStreamPreviewTexture(deviceId: Int, width: Int, height: Int): Long {
        if (DEBUG) Log.v(TAG, "setUpStreamPreviewTexture:deviceId=$deviceId")
        // Producer APIs must run on the UI thread (onMethodCall is on it).
        val producer = mStreamProducers[deviceId] ?: mTextureRegistry.createSurfaceProducer()
        producer.setSize(width, height)
        mStreamProducers[deviceId] = producer
        val surface = producer.surface
        val textureId = producer.id()
        // Native fan-out (uvc_start) is moved off the UI thread.
        mScope.launch {
            withContext(Dispatchers.Default) {
                nativeFanoutSetPreview(deviceId, surface)
                nativeFanoutStart(deviceId)
            }
        }
        return textureId
    }

    private fun tearDownStreamPreviewTexture(deviceId: Int) {
        val producer = mStreamProducers.get(deviceId)
        mStreamProducers.remove(deviceId)
        mScope.launch {
            withContext(Dispatchers.Default) {
                nativeFanoutStop(deviceId)
                nativeFanoutSetPreview(deviceId, null)
            }
            // Release the producer on the UI thread after the fan-out stopped.
            withContext(Dispatchers.Main) {
                producer?.release()
            }
        }
    }

    private fun supportedVideoEncoders(): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        val seen = mutableSetOf<String>()
        try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in infos) {
                if (!info.isEncoder) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !info.isHardwareAccelerated
                ) {
                    continue
                }
                for (type in info.supportedTypes) {
                    if (!type.startsWith("video/")) continue
                    if (seen.add(type)) {
                        result.add(mapOf("mime" to type, "name" to info.name))
                    }
                }
            }
        } catch (t: Throwable) {
            if (DEBUG) Log.w(TAG, t)
        }
        return result
    }

    private fun releaseStreamTextureAll() {
        mStreamProducers.forEach { _, producer -> producer.release() }
        mStreamProducers.clear()
    }

    //--------------------------------------------------------------------------------
    // NativeUvcBridge implementation

    override fun setVideoSize(deviceId: Int, frameType: Int, width: Int, height: Int): Int =
        nativeSetVideoSize(deviceId, frameType, width, height)

    override fun fanoutStart(deviceId: Int): Int = nativeFanoutStart(deviceId)

    override fun fanoutStop(deviceId: Int): Int = nativeFanoutStop(deviceId)

    override fun fanoutSetPreview(deviceId: Int, surface: Surface?): Int =
        nativeFanoutSetPreview(deviceId, surface)

    override fun fanoutSetEncode(deviceId: Int, surface: Surface?): Int =
        nativeFanoutSetEncode(deviceId, surface)

    override fun fanoutSetEncodeActive(deviceId: Int, active: Boolean): Int =
        nativeFanoutSetEncodeActive(deviceId, active)

    override fun fanoutSetMvp(deviceId: Int, mvp: FloatArray): Int =
        nativeFanoutSetMvp(deviceId, mvp)

    override fun startUacRead(deviceId: Int): Int = nativeStartUacRead(deviceId)

    override fun readUacFrame(deviceId: Int, buffer: ByteBuffer, outLen: IntArray): Long =
        nativeReadUacFrame(deviceId, buffer, outLen)

    //--------------------------------------------------------------------------------

    @Keep
    external fun nativeInit(): Int

    @Keep
    external fun nativeRelease(): Int

    @Keep
    external fun nativeSetSurface(deviceId: Int, texId: Long, surface: Surface?): Int

    @Keep
    external fun nativeSetVideoSize(
        deviceId: Int,
        frameType: Int,
        width: Int,
        height: Int,
    ): Int

    @Keep
    external fun nativeFanoutStart(deviceId: Int): Int

    @Keep
    external fun nativeFanoutStop(deviceId: Int): Int

    @Keep
    external fun nativeFanoutSetPreview(deviceId: Int, surface: Surface?): Int

    @Keep
    external fun nativeFanoutSetEncode(deviceId: Int, surface: Surface?): Int

    @Keep
    external fun nativeFanoutSetEncodeActive(deviceId: Int, active: Boolean): Int

    @Keep
    external fun nativeFanoutSetMvp(deviceId: Int, mvp: FloatArray): Int

    @Keep
    external fun nativeStartUacRead(deviceId: Int): Int

    @Keep
    external fun nativeReadUacFrame(
        deviceId: Int,
        buffer: ByteBuffer,
        outLen: IntArray,
    ): Long

    companion object {
        private const val DEBUG = false
        private val TAG = UvcStreamPlugin::class.java.simpleName

        private const val METHOD_CHANNEL_NAME = "com.serenegiant.flutter/aandusb_method"
    }
}
