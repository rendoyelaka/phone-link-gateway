package com.smsgateway.app

import android.content.Context
import android.provider.Telephony

data class SmsMessage(
    val id: Long,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val type: String
) {
    fun formatForTelegram(): String {
        val time = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        val icon = when (type) {
            "inbox"   -> "📩"
            "sent"    -> "📤"
            "outbox"  -> "📨"
            "failed"  -> "❌"
            else      -> "📩"
        }
        return "$icon *${escapeMarkdown(sender)}*\n🕐 $time\n💬 ${escapeMarkdown(body)}"
    }

    fun formatAsText(): String {
        val time = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        return "From: $sender\nTime: $time\n$body"
    }

    private fun escapeMarkdown(text: String): String {
        return text.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`")
    }
}

object SmsReader {

    const val PAGE_SIZE = 20

    fun getInbox(context: Context, limit: Int = PAGE_SIZE, offset: Int = 0): List<SmsMessage> {
        return queryMessages(context, Telephony.Sms.Inbox.CONTENT_URI, "inbox", limit, offset)
    }

    fun getSent(context: Context, limit: Int = PAGE_SIZE, offset: Int = 0): List<SmsMessage> {
        return queryMessages(context, Telephony.Sms.Sent.CONTENT_URI, "sent", limit, offset)
    }

    fun getOutbox(context: Context, limit: Int = PAGE_SIZE, offset: Int = 0): List<SmsMessage> {
        return queryMessages(context, Telephony.Sms.Outbox.CONTENT_URI, "outbox", limit, offset)
    }

    fun getFailed(context: Context, limit: Int = PAGE_SIZE, offset: Int = 0): List<SmsMessage> {
        return queryMessages(context, Telephony.Sms.CONTENT_URI, "failed", limit, offset,
            extraWhere = "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_FAILED}")
    }

    fun getInboxCount(context: Context): Int = getCount(context, Telephony.Sms.Inbox.CONTENT_URI)
    fun getSentCount(context: Context): Int = getCount(context, Telephony.Sms.Sent.CONTENT_URI)
    fun getOutboxCount(context: Context): Int = getCount(context, Telephony.Sms.Outbox.CONTENT_URI)
    fun getFailedCount(context: Context): Int = getCount(context, Telephony.Sms.CONTENT_URI,
        extraWhere = "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_FAILED}")

    fun getLatestMessage(context: Context): SmsMessage? {
        return getInbox(context, 1, 0).firstOrNull()
    }

    private fun getCount(context: Context, uri: android.net.Uri, extraWhere: String? = null): Int {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("COUNT(*)"),
            extraWhere, null, null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
    }

    private fun queryMessages(
        context: Context,
        uri: android.net.Uri,
        type: String,
        limit: Int,
        offset: Int,
        extraWhere: String? = null
    ): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            ),
            extraWhere, null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit OFFSET $offset"
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
