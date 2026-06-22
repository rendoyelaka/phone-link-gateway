package com.smsgateway.app

import android.content.Context
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class ConnectionMonitor(
    private val context: Context,
    private val telegramBot: TelegramBot
) {
    enum class Status { ONLINE, WEAK, OFFLINE }

    private var currentStatus = Status.OFFLINE
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Battery alert tracking — alert once per threshold
    private var alerted20 = false
    private var alerted10 = false
    private var wasCharging = false
    private var chargingCheckDone = false

    companion object {
        var lastStatus = Status.OFFLINE
        var lastPingMs = 0L
        const val WEAK_THRESHOLD_MS = 2000L
        const val CHECK_INTERVAL_MS = 1000L

        // Reconnect trigger — TelegramBot sets this to true
        var reconnectRequested = false
    }

    fun start() {
        monitorJob = scope.launch {
            while (isActive) {
                // Handle reconnect request from Telegram button
                if (reconnectRequested) {
                    reconnectRequested = false
                    telegramBot.sendStatusAlert("🔄 *Reconnecting...*\nRestarting connection now!")
                    telegramBot.reconnect()
                    delay(2000)
                }

                checkConnection()
                checkBattery()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
    }

    private suspend fun checkConnection() {
        val pingMs = pingTelegram()
        val newStatus = when {
            pingMs < 0 -> Status.OFFLINE
            pingMs > WEAK_THRESHOLD_MS -> Status.WEAK
            else -> Status.ONLINE
        }

        lastPingMs = if (pingMs < 0) 0L else pingMs

        if (newStatus != currentStatus) {
            val previous = currentStatus
            currentStatus = newStatus
            lastStatus = newStatus
            notifyStatusChange(previous, newStatus)
        }

        lastStatus = newStatus
    }

    private fun checkBattery() {
        val battery = DeviceInfoHelper.getBatteryPercent(context)
        val charging = DeviceInfoHelper.isCharging(context)

        // Reset alerts when charged back up
        if (battery > 25) { alerted20 = false }
        if (battery > 15) { alerted10 = false }

        // Low battery alerts
        if (battery <= 10 && !alerted10) {
            alerted10 = true
            telegramBot.sendStatusAlert(
                "🔴 *Critical Battery!*\n🪫 Only ${battery}% remaining — charge immediately!"
            )
        } else if (battery <= 20 && !alerted20) {
            alerted20 = true
            telegramBot.sendStatusAlert(
                "🪫 *Battery Low!*\n${battery}% remaining — please charge soon."
            )
        }

        // Charging status change alert
        if (chargingCheckDone && charging != wasCharging) {
            if (charging) {
                telegramBot.sendStatusAlert(
                    "🔌 *Charger Connected*\nDevice is now charging. Battery: ${battery}%"
                )
            } else {
                telegramBot.sendStatusAlert(
                    "🔋 *Charger Disconnected*\nRunning on battery. Battery: ${battery}%"
                )
            }
        }

        wasCharging = charging
        chargingCheckDone = true
    }

    private fun pingTelegram(): Long {
        return try {
            val start = System.currentTimeMillis()
            val prefs = context.getSharedPreferences("gateway_config", Context.MODE_PRIVATE)
            val token = prefs.getString("bot_token", "") ?: ""
            val url = URL("https://api.telegram.org/bot$token/getMe")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - start
            conn.disconnect()
            if (code == 200) elapsed else -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun notifyStatusChange(previous: Status, new: Status) {
        val info = DeviceInfoHelper.getInfo(context)
        val message = when (new) {
            Status.ONLINE -> {
                """
🟢 *Device Online!*
Connection restored!

📱 ${info.brandModel}
${DeviceInfoHelper.getBatteryEmoji(info.batteryPercent)} Battery: ${info.batteryPercent}%
⚡ Ping: ${lastPingMs}ms
🌐 http://${info.ipAddress}:8080
                """.trimIndent()
            }
            Status.WEAK -> {
                """
🟡 *Weak Connection*
Network is slow — messages may be delayed.

📱 ${info.brandModel}
⚡ Ping: ${lastPingMs}ms
                """.trimIndent()
            }
            Status.OFFLINE -> {
                // Offline alert includes Reconnect button — sent via TelegramBot
                telegramBot.sendOfflineAlert(info.brandModel)
                return
            }
        }
        telegramBot.sendStatusAlert(message)
    }
}
