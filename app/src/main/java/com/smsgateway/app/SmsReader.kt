package com.smsgateway.app

import android.content.Context
import android.provider.Telephony

data class SmsMessage(
    val id: Long,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val type: String // "inbox" or "sent"
) {
    fun formatAsText(): String {
        val time = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        return "From: $sender\nTime: $time\n$body"
    }

    fun formatForTelegram(): String {
        val time = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        val icon = if (type == "inbox") "📩" else "📤"
        return "$icon *${escapeMarkdown(sender)}*\n🕐 $time\n💬 ${escapeMarkdown(body)}"
    }

    private fun escapeMarkdown(text: String): String {
        return text.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`")
    }
}

object SmsReader {

    fun getInbox(context: Context, limit: Int = 20): List<SmsMessage> {
        return queryMessages(context, Telephony.Sms.Inbox.CONTENT_URI, "inbox", limit)
    }

    fun getSent(context: Context, limit: Int = 20): List<SmsMessage> {
        return queryMessages(context, Telephony.Sms.Sent.CONTENT_URI, "sent", limit)
    }

    fun getLatestMessage(context: Context): SmsMessage? {
        return getInbox(context, 1).firstOrNull()
    }

    private fun queryMessages(context: Context, uri: android.net.Uri, type: String, limit: Int): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            ),
            null, null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )
        cursor?.use {
            while (it.moveToNext()) {
                messages.add(
                    SmsMessage(
                        id = it.getLong(0),
                        sender = it.getString(1) ?: "Unknown",
                        body = it.getString(2) ?: "",
                        timestamp = it.getLong(3),
                        type = type
                    )
                )
            }
        }
        return messages
    }
}
