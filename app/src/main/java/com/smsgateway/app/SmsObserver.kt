package com.smsgateway.app

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Telephony

class SmsObserver(
    private val context: Context,
    handler: Handler,
    private val telegramBot: TelegramBot
) : ContentObserver(handler) {

    private var lastKnownId: Long = getLatestSmsId()

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        checkForNewMessages()
    }

    private fun checkForNewMessages() {
        val latestId = getLatestSmsId()
        if (latestId > lastKnownId) {
            lastKnownId = latestId
            val message = SmsReader.getLatestMessage(context) ?: return
            // Notify owner on Telegram
            telegramBot.notifyNewMessage(message)
            // Auto-forward to all saved targets
            ForwardManager.getForwardTargets(context).forEach { target ->
                when (target.type) {
                    ForwardTarget.Type.TELEGRAM -> telegramBot.forwardMessage(target.address, message)
                    ForwardTarget.Type.SMS -> SmsSender.sendSms(target.address, message.formatAsText())
                }
            }
        }
    }

    private fun getLatestSmsId(): Long {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            null, null,
            "${Telephony.Sms._ID} DESC LIMIT 1"
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
    }
}
