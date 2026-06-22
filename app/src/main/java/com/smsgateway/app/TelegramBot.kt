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

    // ─── Startup ─────────────────────────────────────────────────────────────

    fun sendStartupMessage() {
        scope.launch {
            delay(3000) // Wait for connection to stabilize
            val msg = DeviceInfoHelper.formatStartupMessage(context)
            sendMessage(ownerChatId, msg, buildMainMenu())
        }
    }

    // ─── Polling ─────────────────────────────────────────────────────────────

    fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                try { fetchUpdates() } catch (e: Exception) { e.printStackTrace() }
                delay(1000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
    }

    fun reconnect() {
        pollingJob?.cancel()
        startPolling()
    }

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

    private fun handleCallback(callbackQuery: JSONObject) {
        val chatId = callbackQuery.getJSONObject("message").getJSONObject("chat").getString("id")
        val data = callbackQuery.getString("data")
        if (chatId != ownerChatId) return
        answerCallback(callbackQuery.getString("id"))

        when (data) {
            "menu_inbox" -> showInbox(chatId)
            "menu_sent" -> showSent(chatId)
            "menu_send" -> {
                userState[chatId] = "awaiting_send_number"
                sendMessage(chatId, "📞 Enter phone number:\n(Example: +919876543210)", buildCancelButton())
            }
            "menu_forwards" -> sendMessage(chatId, "📋 *Auto-Forward Settings*", buildForwardMenu())
            "menu_status" -> showStatus(chatId)
            "menu_main" -> sendMessage(chatId, "📱 *Phone Link*\nChoose an option:", buildMainMenu())
            "inbox_refresh" -> showInbox(chatId)
            "sent_refresh" -> showSent(chatId)
            "status_refresh" -> showStatus(chatId)

            // ── Reconnect button ──
            "action_reconnect" -> {
                sendMessage(chatId, "🔄 *Reconnecting...*\nRestarting connection now!", null)
                ConnectionMonitor.reconnectRequested = true
            }

            "fwd_add_telegram" -> {
                userState[chatId] = "awaiting_tg_target"
                sendMessage(chatId, "📲 Send username, channel or group link:\n\n• @username\n• @channelname\n• -1001234567890", buildCancelButton())
            }
            "fwd_add_sms" -> {
                userState[chatId] = "awaiting_sms_target"
                sendMessage(chatId, "📞 Enter phone number to forward via SMS:", buildCancelButton())
            }
            "fwd_list" -> showForwardList(chatId)
            "fwd_clear" -> {
                ForwardManager.clearAll(context)
                sendMessage(chatId, "🗑️ All forward targets removed.", buildForwardMenu())
            }
            "fwd_back" -> sendMessage(chatId, "📱 *Phone Link*\nChoose an option:", buildMainMenu())
            "action_cancel" -> {
                userState.remove(chatId); userTempData.remove(chatId)
                sendMessage(chatId, "❌ Cancelled.", buildMainMenu())
            }
            else -> {
                if (data.startsWith("remove_")) {
                    ForwardManager.removeTarget(context, data.removePrefix("remove_"))
                    sendMessage(chatId, "✅ Removed successfully.", buildForwardMenu())
                }
            }
        }
    }

    // ─── Status Panel ─────────────────────────────────────────────────────────

    private fun showStatus(chatId: String) {
        val isOnline = ConnectionMonitor.lastStatus != ConnectionMonitor.Status.OFFLINE
        val ping = ConnectionMonitor.lastPingMs
        val msg = DeviceInfoHelper.formatStatusMessage(context, ping, isOnline)
        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("🔄 Refresh", "status_refresh"))
                    put(btn("🔙 Back", "menu_main"))
                })
                if (!isOnline) {
                    put(JSONArray().apply {
                        put(btn("🔄 Reconnect Now", "action_reconnect"))
                    })
                }
            })
        }
        sendMessage(chatId, msg, keyboard)
    }

    // ─── Offline Alert with Reconnect Button ─────────────────────────────────

    fun sendOfflineAlert(deviceName: String) {
        val text = """
🔴 *Device Offline!*
Connection lost!

📱 $deviceName
⏱ Tap below to reconnect instantly
        """.trimIndent()

        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("🔄 Reconnect Now", "action_reconnect"))
                })
                put(JSONArray().apply {
                    put(btn("📡 Check Status", "menu_status"))
                })
            })
        }
        sendMessage(ownerChatId, text, keyboard)
    }

    // ─── Status Alert ─────────────────────────────────────────────────────────

    fun sendStatusAlert(message: String) {
        sendMessage(ownerChatId, message, null)
    }

    // ─── Inbox / Sent ──────────────────────────────────────────────────────────

    private fun showInbox(chatId: String) {
        val messages = SmsReader.getInbox(context, 10)
        if (messages.isEmpty()) { sendMessage(chatId, "📭 Inbox is empty.", buildBackButton()); return }
        val sb = StringBuilder("📥 *Inbox* — Latest 10\n\n")
        messages.forEach { sb.append(it.formatForTelegram()).append("\n─────────────\n") }
        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("🔄 Refresh", "inbox_refresh"))
                    put(btn("🔙 Back", "menu_main"))
                })
            })
        }
        sendMessage(chatId, sb.toString(), keyboard)
    }

    private fun showSent(chatId: String) {
        val messages = SmsReader.getSent(context, 10)
        if (messages.isEmpty()) { sendMessage(chatId, "📭 Sent box is empty.", buildBackButton()); return }
        val sb = StringBuilder("📤 *Sent* — Latest 10\n\n")
        messages.forEach { sb.append(it.formatForTelegram()).append("\n─────────────\n") }
        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("🔄 Refresh", "sent_refresh"))
                    put(btn("🔙 Back", "menu_main"))
                })
            })
        }
        sendMessage(chatId, sb.toString(), keyboard)
    }

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
                put(JSONObject().apply { put("text", "🗑️ Remove ${t.label}"); put("callback_data", "remove_${t.id}") })
            })
        }
        rows.put(JSONArray().apply { put(btn("🔙 Back", "menu_forwards")) })
        sendMessage(chatId, sb.toString(), JSONObject().apply { put("inline_keyboard", rows) })
    }

    fun notifyNewMessage(message: SmsMessage) {
        val text = "📨 *New Message Received!*\n\n${message.formatForTelegram()}"
        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("↩️ Reply", "menu_send"))
                    put(btn("📥 Inbox", "menu_inbox"))
                })
            })
        }
        sendMessage(ownerChatId, text, keyboard)
    }

    fun forwardMessage(targetChatId: String, message: SmsMessage) {
        sendMessage(targetChatId, "📨 *Forwarded Message*\n\n${message.formatForTelegram()}", null)
    }

    // ─── Button Builders ───────────────────────────────────────────────────────

    private fun buildMainMenu(): JSONObject {
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("📥 Inbox", "menu_inbox"))
                    put(btn("📤 Sent", "menu_sent"))
                })
                put(JSONArray().apply {
                    put(btn("✉️ Send Message", "menu_send"))
                    put(btn("📡 Status", "menu_status"))
                })
                put(JSONArray().apply {
                    put(btn("📋 Auto-Forward", "menu_forwards"))
                })
            })
        }
    }

    private fun buildForwardMenu(): JSONObject {
        return JSONObject().apply {
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

    private fun btn(text: String, data: String) = JSONObject().apply {
        put("text", text); put("callback_data", data)
    }

    // ─── HTTP Helpers ──────────────────────────────────────────────────────────

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
