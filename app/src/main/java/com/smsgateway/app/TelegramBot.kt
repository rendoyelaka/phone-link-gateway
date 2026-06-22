package com.smsgateway.app

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TelegramBot(
    private val context: Context,
    private val botToken: String,
    private val ownerChatId: String
) {
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    private var lastUpdateId = 0L
    private var pollingJob: Job? = null
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val userState = mutableMapOf<String, String>()
    private val userTempData = mutableMapOf<String, String>()

    // Track current page per folder per user
    private val pageMap = mutableMapOf<String, Int>() // key: "chatId_folder"

    // ─── Startup ──────────────────────────────────────────────────────────────

    fun sendStartupMessage() {
        scope.launch {
            delay(3000)
            val info = DeviceInfoHelper.getInfo(context)
            val chargingText = if (info.isCharging) "⚡ Charging" else "Not charging"
            val text = """
✅ *Phone Link Started!*

📱 ${info.brandModel}
🤖 Android: ${info.androidVersion}
${DeviceInfoHelper.getBatteryEmoji(info.batteryPercent)} Battery: ${info.batteryPercent}% ($chargingText)
🌐 Dashboard: http://${info.ipAddress}:8080
            """.trimIndent()
            sendMessage(ownerChatId, text, buildMainMenu())
        }
    }

    // ─── Polling ──────────────────────────────────────────────────────────────

    fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                try { fetchUpdates() } catch (e: Exception) { e.printStackTrace() }
                delay(1000)
            }
        }
    }

    fun stopPolling() { pollingJob?.cancel() }
    fun reconnect() { pollingJob?.cancel(); startPolling() }

    // ─── Fetch Updates ────────────────────────────────────────────────────────

    private fun fetchUpdates() {
        val url = "$baseUrl/getUpdates?offset=${lastUpdateId + 1}&timeout=5"
        val response = httpGet(url) ?: return
        val json = JSONObject(response)
        if (!json.getBoolean("ok")) return
        val updates = json.getJSONArray("result")
        for (i in 0 until updates.length()) {
            val update = updates.getJSONObject(i)
            lastUpdateId = update.getLong("update_id")
            handleUpdate(update)
        }
    }

    private fun handleUpdate(update: JSONObject) {
        when {
            update.has("message") -> handleMessage(update.getJSONObject("message"))
            update.has("callback_query") -> handleCallback(update.getJSONObject("callback_query"))
        }
    }

    // ─── Handle Messages ──────────────────────────────────────────────────────

    private fun handleMessage(message: JSONObject) {
        val chatId = message.getJSONObject("chat").getString("id")
        val text = message.optString("text", "")
        if (chatId != ownerChatId) return
        val state = userState[chatId]
        when {
            state == "awaiting_send_number" -> {
                userTempData[chatId] = text
                userState[chatId] = "awaiting_send_body"
                sendMessage(chatId, "✉️ Now type your message:", buildCancelButton())
            }
            state == "awaiting_send_body" -> {
                val number = userTempData[chatId] ?: ""
                val success = SmsSender.sendSms(number, text)
                userState.remove(chatId); userTempData.remove(chatId)
                if (success) sendMessage(chatId, "✅ Message sent to $number!", buildMainMenu())
                else sendMessage(chatId, "❌ Failed to send. Check the number.", buildMainMenu())
            }
            state == "awaiting_tg_target" -> {
                val target = ForwardTarget(ForwardManager.generateId(), text.trim(), text.trim(), ForwardTarget.Type.TELEGRAM)
                ForwardManager.addTarget(context, target)
                userState.remove(chatId)
                sendMessage(chatId, "✅ Added Telegram target: ${text.trim()}", buildForwardMenu())
            }
            state == "awaiting_sms_target" -> {
                val target = ForwardTarget(ForwardManager.generateId(), text.trim(), text.trim(), ForwardTarget.Type.SMS)
                ForwardManager.addTarget(context, target)
                userState.remove(chatId)
                sendMessage(chatId, "✅ Added SMS forward: ${text.trim()}", buildForwardMenu())
            }
            else -> sendMessage(chatId, "👋 Welcome to *Phone Link*\nChoose an option:", buildMainMenu())
        }
    }

    // ─── Handle Callbacks ─────────────────────────────────────────────────────

    private fun handleCallback(callbackQuery: JSONObject) {
        val chatId = callbackQuery.getJSONObject("message").getJSONObject("chat").getString("id")
        val data = callbackQuery.getString("data")
        if (chatId != ownerChatId) return
        answerCallback(callbackQuery.getString("id"))

        when {
            data == "menu_inbox"    -> showInbox(chatId, getPage(chatId, "inbox"))
            data == "menu_sent"     -> showSent(chatId, getPage(chatId, "sent"))
            data == "menu_send"     -> {
                userState[chatId] = "awaiting_send_number"
                sendMessage(chatId, "📞 Enter phone number:\n(Example: +919876543210)", buildCancelButton())
            }
            data == "menu_forwards" -> sendMessage(chatId, "📋 *Auto-Forward Settings*", buildForwardMenu())
            data == "menu_devices"  -> showManageDevices(chatId)
            data == "menu_main"     -> sendMessage(chatId, "📱 *Phone Link*\nChoose an option:", buildMainMenu())
            data == "device_sms"    -> showSmsManager(chatId)
            data == "devices_refresh" -> showManageDevices(chatId)

            // ── SMS Manager folders ──
            data == "sms_inbox"   -> { setPage(chatId, "inbox", 0);   showInbox(chatId, 0) }
            data == "sms_sent"    -> { setPage(chatId, "sent", 0);    showSent(chatId, 0) }
            data == "sms_outbox"  -> { setPage(chatId, "outbox", 0);  showOutbox(chatId, 0) }
            data == "sms_failed"  -> { setPage(chatId, "failed", 0);  showFailed(chatId, 0) }
            data == "sms_forward" -> sendMessage(chatId, "📋 *Auto-Forward Settings*", buildForwardMenu())
            data == "sms_back"    -> showSmsManager(chatId)

            // ── Pagination ──
            data.startsWith("page_") -> handlePagination(chatId, data)

            // ── Reconnect ──
            data == "action_reconnect" -> {
                ConnectionMonitor.reconnectRequested = true
                sendMessage(chatId, "🔄 *Reconnecting...*\nRestarting connection now!", null)
            }

            // ── Forward management ──
            data == "fwd_add_telegram" -> {
                userState[chatId] = "awaiting_tg_target"
                sendMessage(chatId, "📲 Send username, channel or group link:\n\n• @username\n• @channelname\n• -1001234567890", buildCancelButton())
            }
            data == "fwd_add_sms" -> {
                userState[chatId] = "awaiting_sms_target"
                sendMessage(chatId, "📞 Enter phone number to forward via SMS:", buildCancelButton())
            }
            data == "fwd_list"  -> showForwardList(chatId)
            data == "fwd_clear" -> {
                ForwardManager.clearAll(context)
                sendMessage(chatId, "🗑️ All forward targets removed.", buildForwardMenu())
            }
            data == "fwd_back" -> sendMessage(chatId, "📱 *Phone Link*\nChoose an option:", buildMainMenu())

            data == "action_cancel" -> {
                userState.remove(chatId); userTempData.remove(chatId)
                sendMessage(chatId, "❌ Cancelled.", buildMainMenu())
            }

            data.startsWith("remove_") -> {
                ForwardManager.removeTarget(context, data.removePrefix("remove_"))
                sendMessage(chatId, "✅ Removed successfully.", buildForwardMenu())
            }
        }
    }

    // ─── Manage Devices Panel ─────────────────────────────────────────────────

    private fun showManageDevices(chatId: String) {
        val info = DeviceInfoHelper.getInfo(context)
        val status = ConnectionMonitor.lastStatus
        val ping = ConnectionMonitor.lastPingMs
        val statusDot = when (status) {
            ConnectionMonitor.Status.ONLINE  -> "🟢 Online"
            ConnectionMonitor.Status.WEAK    -> "🟡 Weak"
            ConnectionMonitor.Status.OFFLINE -> "🔴 Offline"
        }
        val pingText = if (ping > 0) " — ${ping}ms" else ""
        val chargingText = if (info.isCharging) "⚡ Charging" else "Not charging"
        val text = """
📱 *Manage Devices*

*Device 1 — ${info.brandModel}*
─────────────────
📶 $statusDot$pingText
${DeviceInfoHelper.getBatteryEmoji(info.batteryPercent)} Battery: ${info.batteryPercent}% ($chargingText)
⚙️ Service: ${if (GatewayForegroundService.isRunning) "✅ Running" else "❌ Stopped"}
        """.trimIndent()

        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("💬 SMS Manager", "device_sms"))
                })
                put(JSONArray().apply {
                    put(btn("🔄 Refresh", "devices_refresh"))
                    put(btn("🔙 Back", "menu_main"))
                })
                if (status == ConnectionMonitor.Status.OFFLINE) {
                    put(JSONArray().apply { put(btn("🔄 Reconnect Now", "action_reconnect")) })
                }
            })
        }
        sendMessage(chatId, text, keyboard)
    }

    // ─── SMS Manager Panel ────────────────────────────────────────────────────

    private fun showSmsManager(chatId: String) {
        val inboxCount   = SmsReader.getInboxCount(context)
        val sentCount    = SmsReader.getSentCount(context)
        val outboxCount  = SmsReader.getOutboxCount(context)
        val failedCount  = SmsReader.getFailedCount(context)
        val fwdCount     = ForwardManager.getForwardTargets(context).size

        val text = """
💬 *SMS Manager*

📥 Inbox      — $inboxCount messages
📤 Sent       — $sentCount messages
📨 Outbox     — $outboxCount messages
❌ Failed     — $failedCount messages
🔀 Forwards   — $fwdCount targets
        """.trimIndent()

        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("📥 Inbox ($inboxCount)", "sms_inbox"))
                    put(btn("📤 Sent ($sentCount)", "sms_sent"))
                })
                put(JSONArray().apply {
                    put(btn("📨 Outbox ($outboxCount)", "sms_outbox"))
                    put(btn("❌ Failed ($failedCount)", "sms_failed"))
                })
                put(JSONArray().apply {
                    put(btn("🔀 Forward Settings", "sms_forward"))
                })
                put(JSONArray().apply {
                    put(btn("🔙 Back", "menu_devices"))
                })
            })
        }
        sendMessage(chatId, text, keyboard)
    }

    // ─── Inbox with Pagination ────────────────────────────────────────────────

    private fun showInbox(chatId: String, page: Int) {
        setPage(chatId, "inbox", page)
        val total = SmsReader.getInboxCount(context)
        val messages = SmsReader.getInbox(context, SmsReader.PAGE_SIZE, page * SmsReader.PAGE_SIZE)
        showFolder(chatId, "📥 Inbox", messages, page, total, "inbox", "sms_inbox", "sms_back")
    }

    private fun showSent(chatId: String, page: Int) {
        setPage(chatId, "sent", page)
        val total = SmsReader.getSentCount(context)
        val messages = SmsReader.getSent(context, SmsReader.PAGE_SIZE, page * SmsReader.PAGE_SIZE)
        showFolder(chatId, "📤 Sent", messages, page, total, "sent", "sms_sent", "sms_back")
    }

    private fun showOutbox(chatId: String, page: Int) {
        setPage(chatId, "outbox", page)
        val total = SmsReader.getOutboxCount(context)
        val messages = SmsReader.getOutbox(context, SmsReader.PAGE_SIZE, page * SmsReader.PAGE_SIZE)
        showFolder(chatId, "📨 Outbox", messages, page, total, "outbox", "sms_outbox", "sms_back")
    }

    private fun showFailed(chatId: String, page: Int) {
        setPage(chatId, "failed", page)
        val total = SmsReader.getFailedCount(context)
        val messages = SmsReader.getFailed(context, SmsReader.PAGE_SIZE, page * SmsReader.PAGE_SIZE)
        showFolder(chatId, "❌ Failed", messages, page, total, "failed", "sms_failed", "sms_back")
    }

    private fun showFolder(
        chatId: String,
        title: String,
        messages: List<SmsMessage>,
        page: Int,
        total: Int,
        folder: String,
        refreshCallback: String,
        backCallback: String
    ) {
        if (messages.isEmpty() && page == 0) {
            sendMessage(chatId, "$title\n\n📭 No messages found.", buildSmsBackButton())
            return
        }

        val totalPages = if (total == 0) 1 else ((total + SmsReader.PAGE_SIZE - 1) / SmsReader.PAGE_SIZE)
        val sb = StringBuilder("$title — Page ${page + 1} of $totalPages\n\n")
        messages.forEach { sb.append(it.formatForTelegram()).append("\n─────────────\n") }

        val navRow = JSONArray()
        if (page > 0) navRow.put(btn("⬅️ Previous", "page_${folder}_${page - 1}"))
        navRow.put(btn("🔄 Refresh", refreshCallback))
        if ((page + 1) < totalPages) navRow.put(btn("➡️ Next", "page_${folder}_${page + 1}"))

        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(navRow)
                put(JSONArray().apply { put(btn("🔙 Back", backCallback)) })
            })
        }
        sendMessage(chatId, sb.toString(), keyboard)
    }

    // ─── Pagination Handler ───────────────────────────────────────────────────

    private fun handlePagination(chatId: String, data: String) {
        // data format: page_folder_pagenumber  e.g. page_inbox_1
        val parts = data.split("_")
        if (parts.size < 3) return
        val folder = parts[1]
        val page = parts[2].toIntOrNull() ?: 0
        when (folder) {
            "inbox"  -> showInbox(chatId, page)
            "sent"   -> showSent(chatId, page)
            "outbox" -> showOutbox(chatId, page)
            "failed" -> showFailed(chatId, page)
        }
    }

    // ─── Page Tracking ────────────────────────────────────────────────────────

    private fun getPage(chatId: String, folder: String) = pageMap["${chatId}_$folder"] ?: 0
    private fun setPage(chatId: String, folder: String, page: Int) { pageMap["${chatId}_$folder"] = page }

    // ─── Forward List ─────────────────────────────────────────────────────────

    private fun showForwardList(chatId: String) {
        val targets = ForwardManager.getForwardTargets(context)
        if (targets.isEmpty()) { sendMessage(chatId, "📭 No targets added yet.", buildForwardMenu()); return }
        val sb = StringBuilder("📃 *Active Forward Targets:*\n\n")
        targets.forEachIndexed { i, t ->
            val icon = if (t.type == ForwardTarget.Type.TELEGRAM) "💬" else "📱"
            sb.append("${i + 1}. $icon ${t.label}\n")
        }
        val rows = JSONArray()
        targets.forEach { t ->
            rows.put(JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "🗑️ Remove ${t.label}")
                    put("callback_data", "remove_${t.id}")
                })
            })
        }
        rows.put(JSONArray().apply { put(btn("🔙 Back", "menu_forwards")) })
        sendMessage(chatId, sb.toString(), JSONObject().apply { put("inline_keyboard", rows) })
    }

    // ─── New SMS Notification ─────────────────────────────────────────────────

    fun notifyNewMessage(message: SmsMessage) {
        val text = "📨 *New Message Received!*\n\n${message.formatForTelegram()}"
        sendMessage(ownerChatId, text, JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("↩️ Reply", "menu_send"))
                    put(btn("📥 Inbox", "sms_inbox"))
                })
            })
        })
    }

    fun forwardMessage(targetChatId: String, message: SmsMessage) {
        sendMessage(targetChatId, "📨 *Forwarded Message*\n\n${message.formatForTelegram()}", null)
    }

    // ─── Button Builders ──────────────────────────────────────────────────────

    private fun buildMainMenu() = JSONObject().apply {
        put("inline_keyboard", JSONArray().apply {
            put(JSONArray().apply {
                put(btn("📥 Inbox", "menu_inbox"))
                put(btn("📤 Sent", "menu_sent"))
            })
            put(JSONArray().apply {
                put(btn("✉️ Send Message", "menu_send"))
                put(btn("📱 Manage Devices", "menu_devices"))
            })
            put(JSONArray().apply {
                put(btn("📋 Auto-Forward", "menu_forwards"))
            })
        })
    }

    private fun buildForwardMenu() = JSONObject().apply {
        put("inline_keyboard", JSONArray().apply {
            put(JSONArray().apply { put(btn("➕ Add Telegram Target", "fwd_add_telegram")) })
            put(JSONArray().apply { put(btn("➕ Add Phone Number", "fwd_add_sms")) })
            put(JSONArray().apply {
                put(btn("📃 View All", "fwd_list"))
                put(btn("🗑️ Clear All", "fwd_clear"))
            })
            put(JSONArray().apply { put(btn("🔙 Back", "fwd_back")) })
        })
    }

    private fun buildCancelButton() = JSONObject().apply {
        put("inline_keyboard", JSONArray().apply {
            put(JSONArray().apply { put(btn("❌ Cancel", "action_cancel")) })
        })
    }

    private fun buildBackButton() = JSONObject().apply {
        put("inline_keyboard", JSONArray().apply {
            put(JSONArray().apply { put(btn("🔙 Back to Menu", "menu_main")) })
        })
    }

    private fun buildSmsBackButton() = JSONObject().apply {
        put("inline_keyboard", JSONArray().apply {
            put(JSONArray().apply { put(btn("🔙 Back", "sms_back")) })
        })
    }

    private fun btn(text: String, data: String) = JSONObject().apply {
        put("text", text); put("callback_data", data)
    }

    // ─── HTTP Helpers ─────────────────────────────────────────────────────────

    fun sendMessage(chatId: String, text: String, replyMarkup: JSONObject? = null) {
        scope.launch {
            try {
                val url = URL("$baseUrl/sendMessage")
                val body = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "Markdown")
                    if (replyMarkup != null) put("reply_markup", replyMarkup)
                }
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write(body.toString().toByteArray())
                conn.inputStream.bufferedReader().readText()
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun answerCallback(callbackId: String) {
        scope.launch {
            try {
                val url = URL("$baseUrl/answerCallbackQuery")
                val body = JSONObject().apply { put("callback_query_id", callbackId) }
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write(body.toString().toByteArray())
                conn.inputStream.bufferedReader().readText()
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val result = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            result
        } catch (e: Exception) { e.printStackTrace(); null }
    }
}
