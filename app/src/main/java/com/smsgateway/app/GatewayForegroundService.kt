package com.smsgateway.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat

class GatewayForegroundService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "sms_gateway_channel"
        const val NOTIFICATION_ID = 1
    }

    private lateinit var smsObserver: SmsObserver
    private lateinit var telegramBot: TelegramBot
    private lateinit var webServer: WebServer
    private lateinit var connectionMonitor: ConnectionMonitor
    private var observerHandler: Handler? = null
    private var observerThread: HandlerThread? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val prefs = getSharedPreferences("gateway_config", Context.MODE_PRIVATE)
        val botToken = prefs.getString("bot_token", "") ?: ""
        val ownerChatId = prefs.getString("owner_chat_id", "") ?: ""

        telegramBot = TelegramBot(this, botToken, ownerChatId)
        webServer = WebServer(this, 8080)
        connectionMonitor = ConnectionMonitor(this, telegramBot)

        // Start SMS observer on background thread
        observerThread = HandlerThread("SmsObserverThread").also { it.start() }
        observerHandler = Handler(observerThread!!.looper)
        smsObserver = SmsObserver(this, observerHandler!!, telegramBot)
        contentResolver.registerContentObserver(
            android.provider.Telephony.Sms.CONTENT_URI, true, smsObserver
        )

        telegramBot.startPolling()
        webServer.start()
        connectionMonitor.start()

        // Send startup message to Telegram
        telegramBot.sendStartupMessage()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        contentResolver.unregisterContentObserver(smsObserver)
        telegramBot.stopPolling()
        webServer.stop()
        connectionMonitor.stop()
        observerThread?.quit()
        // Auto restart
        val restartIntent = Intent(this, GatewayForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📱 Phone Link")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Phone Link Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps Phone Link running in background" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
