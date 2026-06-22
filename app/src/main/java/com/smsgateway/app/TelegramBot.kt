package com.smsgateway.app

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TelegramBot(
    private val context: Context,
    private val botToken: String,
    private val ownerChatId: String
) {
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    private var lastUpdateId = 0L
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State tracking per user session
    private val userState = mutableMapOf<String, String>()
    private val userTempData = mutableMapOf<String, String>()

    // ─── Polling ────────────────────────────────────────────────────────────

    fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    fetchUpdates()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(1000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        scope.cancel()
    }

    // ─── Fetch Updates ───────────────────────────────────────────────────────

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

    // ─── Handle Incoming Updates ─────────────────────────────────────────────

    private fun handleUpdate(update: JSONObject) {
        when {
            update.has("message") -> handleMessage(update.getJSONObject("message"))
            update.has("callback_query") -> handleCallback(update.getJSONObject("callback_query"))
        }
    }

    private fun handleMessage(message: JSONObject) {
        val chatId = message.getJSONObject("chat").getString("id")
        val text = message.optString("text", "")

        // Only respond to owner
        if (chatId != ownerChatId) return

        val state = userState[chatId]

        when {
            // ── Awaiting phone number to send SMS ──
            state == "awaiting_send_number" -> {
                userTempData[chatId] = text
                userState[chatId] = "awaiting_send_body"
                sendMessage(chatId, "✉️ Now type your message to send:", buildCancelButton())
            }
            // ── Awaiting SMS body ──
            state == "awaiting_send_body" -> {
                val number = userTempData[chatId] ?: ""
                val success = SmsSender.sendSms(number, text)
                userState.remove(chatId)
                userTempData.remove(chatId)
                if (success) {
                    sendMessage(chatId, "✅ Message sent to $number!", buildMainMenu())
                } else {
                    sendMessage(chatId, "❌ Failed to send. Check the number and try again.", buildMainMenu())
                }
            }
            // ── Awaiting Telegram forward target ──
            state == "awaiting_tg_target" -> {
                val address = text.trim()
                val target = ForwardTarget(
                    id = ForwardManager.generateId(),
                    address = address,
                    label = address,
                    type = ForwardTarget.Type.TELEGRAM
                )
                ForwardManager.addTarget(context, target)
                userState.remove(chatId)
                sendMessage(chatId, "✅ Added Telegram target: $address\nAll new messages will be forwarded here.", buildForwardMenu())
            }
            // ── Awaiting phone number for SMS forwarding ──
            state == "awaiting_sms_target" -> {
                val number = text.trim()
                val target = ForwardTarget(
                    id = ForwardManager.generateId(),
                    address = number,
                    label = number,
                    type = ForwardTarget.Type.SMS
                )
                ForwardManager.addTarget(context, target)
                userState.remove(chatId)
                sendMessage(chatId, "✅ Added SMS forward number: $number", buildForwardMenu())
            }
            // ── Default: show main menu ──
            else -> {
                sendMessage(chatId, "👋 Welcome to *SMS Gateway*\nChoose an option below:", buildMainMenu())
            }
        }
    }

    private fun handleCallback(callbackQuery: JSONObject) {
        val chatId = callbackQuery.getJSONObject("message").getJSONObject("chat").getString("id")
        val data = callbackQuery.getString("data")
        val messageId = callbackQuery.getJSONObject("message").getLong("message_id")

        if (chatId != ownerChatId) return

        // Acknowledge button tap
        answerCallback(callbackQuery.getString("id"))

        when (data) {

            // ── Main Menu ──
            "menu_inbox" -> showInbox(chatId)
            "menu_sent" -> showSent(chatId)
            "menu_send" -> {
                userState[chatId] = "awaiting_send_number"
                sendMessage(chatId, "📞 Enter the phone number to message:\n(Example: +919876543210)", buildCancelButton())
            }
            "menu_forwards" -> sendMessage(chatId, "📋 *Auto-Forward Settings*\nChoose an option:", buildForwardMenu())
            "menu_settings" -> sendMessage(chatId, "⚙️ *Settings*", buildSettingsMenu())
            "menu_main" -> sendMessage(chatId, "📱 *SMS Gateway*\nChoose an option:", buildMainMenu())

            // ── Inbox Pagination ──
            "inbox_refresh" -> showInbox(chatId)
            "sent_refresh" -> showSent(chatId)

            // ── Forward Menu ──
            "fwd_add_telegram" -> {
                userState[chatId] = "awaiting_tg_target"
                sendMessage(chatId,
                    "📲 Send the Telegram username, channel link or group link:\n\nExamples:\n• @username\n• @channelname\n• -1001234567890",
                    buildCancelButton()
                )
            }
            "fwd_add_sms" -> {
                userState[chatId] = "awaiting_sms_target"
                sendMessage(chatId, "📞 Enter the phone number to forward via SMS:\n(Example: +919876543210)", buildCancelButton())
            }
            "fwd_list" -> showForwardList(chatId)
            "fwd_clear" -> {
                ForwardManager.clearAll(context)
                sendMessage(chatId, "🗑️ All forward targets removed.", buildForwardMenu())
            }
            "fwd_back" -> sendMessage(chatId, "📱 *SMS Gateway*\nChoose an option:", buildMainMenu())

            // ── Cancel ──
            "action_cancel" -> {
                userState.remove(chatId)
                userTempData.remove(chatId)
                sendMessage(chatId, "❌ Cancelled. Back to main menu.", buildMainMenu())
            }

            // ── Remove specific forward target ──
            else -> {
                if (data.startsWith("remove_")) {
                    val id = data.removePrefix("remove_")
                    ForwardManager.removeTarget(context, id)
                    sendMessage(chatId, "✅ Removed successfully.", buildForwardMenu())
                }
            }
        }
    }

    // ─── Show Inbox ──────────────────────────────────────────────────────────

    private fun showInbox(chatId: String) {
        val messages = SmsReader.getInbox(context, 10)
        if (messages.isEmpty()) {
            sendMessage(chatId, "📭 Inbox is empty.", buildBackToMenuButton())
            return
        }
        val sb = StringBuilder("📥 *Inbox* — Latest 10 messages\n\n")
        messages.forEach { sb.append(it.formatForTelegram()).append("\n─────────────\n") }

        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🔄 Refresh"); put("callback_data", "inbox_refresh") })
                    put(JSONObject().apply { put("text", "🔙 Back"); put("callback_data", "menu_main") })
                })
            })
        }
        sendMessage(chatId, sb.toString(), keyboard)
    }

    // ─── Show Sent ───────────────────────────────────────────────────────────

    private fun showSent(chatId: String) {
        val messages = SmsReader.getSent(context, 10)
        if (messages.isEmpty()) {
            sendMessage(chatId, "📭 Sent box is empty.", buildBackToMenuButton())
            return
        }
        val sb = StringBuilder("📤 *Sent* — Latest 10 messages\n\n")
        messages.forEach { sb.append(it.formatForTelegram()).append("\n─────────────\n") }

        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🔄 Refresh"); put("callback_data", "sent_refresh") })
                    put(JSONObject().apply { put("text", "🔙 Back"); put("callback_data", "menu_main") })
                })
            })
        }
        sendMessage(chatId, sb.toString(), keyboard)
    }

    // ─── Show Forward List ───────────────────────────────────────────────────

    private fun showForwardList(chatId: String) {
        val targets = ForwardManager.getForwardTargets(context)
        if (targets.isEmpty()) {
            sendMessage(chatId, "📭 No forward targets added yet.", buildForwardMenu())
            return
        }
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
        rows.put(JSONArray().apply {
            put(JSONObject().apply { put("text", "🔙 Back"); put("callback_data", "menu_forwards") })
        })

        sendMessage(chatId, sb.toString(), JSONObject().apply { put("inline_keyboard", rows) })
    }

    // ─── Notify New Message ──────────────────────────────────────────────────

    fun notifyNewMessage(message: SmsMessage) {
        val text = "📨 *New Message Received!*\n\n${message.formatForTelegram()}"
        val keyboard = JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "↩️ Reply"); put("callback_data", "menu_send") })
                    put(JSONObject().apply { put("text", "📥 Inbox"); put("callback_data", "menu_inbox") })
                })
            })
        }
        sendMessage(ownerChatId, text, keyboard)
    }

    // ─── Forward Message to Target ───────────────────────────────────────────

    fun forwardMessage(targetChatId: String, message: SmsMessage) {
        val text = "📨 *Forwarded Message*\n\n${message.formatForTelegram()}"
        sendMessage(targetChatId, text, null)
    }

    // ─── Button Builders ─────────────────────────────────────────────────────

    private fun buildMainMenu(): JSONObject {
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("📥 Inbox", "menu_inbox"))
                    put(btn("📤 Sent", "menu_sent"))
                })
                put(JSONArray().apply {
                    put(btn("✉️ Send Message", "menu_send"))
                    put(btn("⚙️ Settings", "menu_settings"))
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
                put(JSONArray().apply {
                    put(btn("➕ Add Telegram Target", "fwd_add_telegram"))
                })
                put(JSONArray().apply {
                    put(btn("➕ Add Phone Number", "fwd_add_sms"))
                })
                put(JSONArray().apply {
                    put(btn("📃 View All Targets", "fwd_list"))
                    put(btn("🗑️ Clear All", "fwd_clear"))
                })
                put(JSONArray().apply {
                    put(btn("🔙 Back", "fwd_back"))
                })
            })
        }
    }

    private fun buildSettingsMenu(): JSONObject {
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("🔙 Back to Menu", "menu_main"))
                })
            })
        }
    }

    private fun buildCancelButton(): JSONObject {
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("❌ Cancel", "action_cancel"))
                })
            })
        }
    }

    private fun buildBackToMenuButton(): JSONObject {
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(btn("🔙 Back to Menu", "menu_main"))
                })
            })
        }
    }

    private fun btn(text: String, data: String) = JSONObject().apply {
        put("text", text)
        put("callback_data", data)
    }

    // ─── HTTP Helpers ────────────────────────────────────────────────────────

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
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
