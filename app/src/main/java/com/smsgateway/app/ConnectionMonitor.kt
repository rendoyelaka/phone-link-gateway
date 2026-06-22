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

    companion object {
        var lastStatus = Status.OFFLINE
        var lastPingMs = 0L
        var reconnectRequested = false
    }

    fun start() {
        monitorJob = scope.launch {
            while (isActive) {
                if (reconnectRequested) {
                    reconnectRequested = false
                    telegramBot.reconnect()
                    delay(2000)
                }
                checkConnection()
                delay(1000)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
    }

    private fun checkConnection() {
        val pingMs = pingTelegram()
        val newStatus = when {
            pingMs < 0 -> Status.OFFLINE
            pingMs > 2000L -> Status.WEAK
            else -> Status.ONLINE
        }
        lastPingMs = if (pingMs < 0) 0L else pingMs
        currentStatus = newStatus
        lastStatus = newStatus
        // Silent — no automatic alerts sent anywhere
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
}
