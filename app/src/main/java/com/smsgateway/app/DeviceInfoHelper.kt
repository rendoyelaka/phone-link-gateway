package com.smsgateway.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceInfoHelper {

    data class DeviceInfo(
        val brandModel: String,
        val model: String,
        val androidVersion: String,
        val batteryPercent: Int,
        val isCharging: Boolean,
        val ipAddress: String
    )

    fun getInfo(context: Context): DeviceInfo {
        return DeviceInfo(
            brandModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            batteryPercent = getBatteryPercent(context),
            isCharging = isCharging(context),
            ipAddress = getLocalIpAddress(context)
        )
    }

    fun getBatteryPercent(context: Context): Int {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    }

    fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun getLocalIpAddress(context: Context): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown"
    }

    fun getBatteryEmoji(percent: Int): String {
        return when {
            percent >= 80 -> "🔋"
            percent >= 50 -> "🔋"
            percent >= 20 -> "🪫"
            else -> "🔴"
        }
    }

    fun formatStartupMessage(context: Context): String {
        val info = getInfo(context)
        val chargingText = if (info.isCharging) "⚡ Charging" else "🔋 Not charging"
        return """
✅ *Phone Link Started!*

📱 Device: ${info.brandModel}
🔧 Model: ${info.model}
🤖 Android: ${info.androidVersion}
${getBatteryEmoji(info.batteryPercent)} Battery: ${info.batteryPercent}%
$chargingText
🌐 Dashboard: http://${info.ipAddress}:8080
        """.trimIndent()
    }

    fun formatStatusMessage(context: Context, pingMs: Long, isOnline: Boolean): String {
        val info = getInfo(context)
        val statusDot = when {
            !isOnline -> "🔴 Offline"
            pingMs > 2000 -> "🟡 Weak Connection"
            else -> "🟢 Online"
        }
        val pingText = if (pingMs > 0) " — ${pingMs}ms" else ""
        val chargingText = if (info.isCharging) "⚡ Charging" else "Not charging"
        return """
📡 *Device Status*

$statusDot$pingText
📱 ${info.brandModel}
${getBatteryEmoji(info.batteryPercent)} Battery: ${info.batteryPercent}% ($chargingText)
🌐 http://${info.ipAddress}:8080
✅ Service running
        """.trimIndent()
    }
}
