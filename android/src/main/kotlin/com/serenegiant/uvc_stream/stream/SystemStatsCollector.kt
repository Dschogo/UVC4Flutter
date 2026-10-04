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
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

/**
 * Best-effort device telemetry used by the stats overlay:
 * - GPU: read from common SoC sysfs nodes when available (null otherwise,
 *   Android has no portable GPU load API).
 * - Power: instantaneous current and power from [BatteryManager].
 */
class SystemStatsCollector(private val context: Context) {
    fun sample(): Map<String, Any?> {
        val (amps, watts) = samplePower()
        return mapOf(
            "gpu" to sampleGpu(),
            "powerAmps" to amps,
            "powerWatts" to watts,
        )
    }

    private fun samplePower(): Pair<Double?, Double?> {
        return try {
            val manager = context.getSystemService(BatteryManager::class.java)
            val currentUa = manager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?: return null to null
            val batteryIntent: Intent? = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val amps = currentUa / 1_000_000.0
            val watts = if (voltageMv > 0) {
                abs(amps) * (voltageMv / 1000.0)
            } else {
                null
            }
            amps to watts
        } catch (t: Throwable) {
            null to null
        }
    }

    private fun sampleGpu(): Double? {
        readText("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.let { value ->
            value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let { return it }
        }
        readText("/sys/class/kgsl/kgsl-3d0/gpubusy")?.let { value ->
            val parts = value.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val busy = parts[0].toDoubleOrNull()
                val total = parts[1].toDoubleOrNull()
                if ((busy != null) && (total != null) && (total > 0.0)) {
                    return (busy / total * 100.0).coerceIn(0.0, 100.0)
                }
            }
        }
        readText("/sys/kernel/gpu/gpu_busy")?.let { value ->
            value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let { return it }
        }
        readText("/sys/class/misc/mali0/device/utilisation")?.let { value ->
            value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun readText(path: String): String? = try {
        File(path).readText().trim().ifEmpty { null }
    } catch (t: Throwable) {
        null
    }
}
