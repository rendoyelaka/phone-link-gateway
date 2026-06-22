package com.smsgateway.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony

object SmsDeleteHelper {

    // ─── Master Delete — tries all methods in order ───────────────────────────

    fun deleteMessage(context: Context, messageId: Long): DeleteResult {
        // Method 1: Direct URI delete (works on all Android versions for most ROMs)
        val result1 = tryDirectDelete(context, messageId)
        if (result1.success) return result1

        // Method 2: Try alternate URI formats used by manufacturer ROMs
        val result2 = tryAlternateUriDelete(context, messageId)
        if (result2.success) return result2

        // Method 3: Try Telephony content URI with type selection
        val result3 = tryTelephonyDelete(context, messageId)
        if (result3.success) return result3

        // Method 4: Try MMS-SMS combined URI (works on some Android 11+ ROMs)
        val result4 = tryMmsSmsDelete(context, messageId)
        if (result4.success) return result4

        // Method 5: Mark as read + archive (soft delete — hides from inbox)
        val result5 = trySoftDelete(context, messageId)
        if (result5.success) return result5

        return DeleteResult(false, "⚠️ This message is protected by your device. Cannot delete.")
    }

    // ─── Method 1: Direct content://sms/ID ───────────────────────────────────

    private fun tryDirectDelete(context: Context, messageId: Long): DeleteResult {
        return try {
            val uri = Uri.parse("content://sms/$messageId")
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted > 0) DeleteResult(true, "✅ Deleted successfully!")
            else DeleteResult(false, "")
        } catch (e: Exception) {
            DeleteResult(false, "")
        }
    }

    // ─── Method 2: Alternate URI formats for manufacturer ROMs ───────────────

    private fun tryAlternateUriDelete(context: Context, messageId: Long): DeleteResult {
        val uriFormats = listOf(
            "content://sms/inbox/$messageId",
            "content://sms/sent/$messageId",
            "content://sms/outbox/$messageId",
            "content://sms/failed/$messageId",
            "content://mms-sms/$messageId"
        )
        for (uriStr in uriFormats) {
            try {
                val uri = Uri.parse(uriStr)
                val deleted = context.contentResolver.delete(uri, null, null)
                if (deleted > 0) return DeleteResult(true, "✅ Deleted successfully!")
            } catch (e: Exception) {
                continue
            }
        }
        return DeleteResult(false, "")
    }

    // ─── Method 3: Telephony URI with _id selection ───────────────────────────

    private fun tryTelephonyDelete(context: Context, messageId: Long): DeleteResult {
        return try {
            val uri = Telephony.Sms.CONTENT_URI
            val deleted = context.contentResolver.delete(
                uri,
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId.toString())
            )
            if (deleted > 0) DeleteResult(true, "✅ Deleted successfully!")
            else DeleteResult(false, "")
        } catch (e: Exception) {
            DeleteResult(false, "")
        }
    }

    // ─── Method 4: MMS-SMS combined URI ──────────────────────────────────────

    private fun tryMmsSmsDelete(context: Context, messageId: Long): DeleteResult {
        return try {
            val uri = Uri.parse("content://mms-sms/conversations")
            val deleted = context.contentResolver.delete(
                uri,
                "_id = ?",
                arrayOf(messageId.toString())
            )
            if (deleted > 0) DeleteResult(true, "✅ Deleted successfully!")
            else DeleteResult(false, "")
        } catch (e: Exception) {
            DeleteResult(false, "")
        }
    }

    // ─── Method 5: Soft delete — mark as read and archive ────────────────────
    // Used as last resort — hides message from inbox view

    private fun trySoftDelete(context: Context, messageId: Long): DeleteResult {
        return try {
            val uri = Uri.parse("content://sms/$messageId")
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.READ, 1)       // Mark as read
                put(Telephony.Sms.SEEN, 1)        // Mark as seen
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    put("archived", 1)            // Archive on older Android
                }
            }
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated > 0) DeleteResult(true,
                "📦 Message archived & marked read\n(Your device restricts full delete)")
            else DeleteResult(false, "")
        } catch (e: Exception) {
            DeleteResult(false, "")
        }
    }

    // ─── Bulk delete all messages from a sender ───────────────────────────────

    fun deleteAllFromSender(context: Context, senderAddress: String): DeleteResult {
        return try {
            val uri = Telephony.Sms.CONTENT_URI
            val deleted = context.contentResolver.delete(
                uri,
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(senderAddress)
            )
            if (deleted > 0) DeleteResult(true, "✅ Deleted $deleted messages from $senderAddress")
            else DeleteResult(false, "No messages found from $senderAddress")
        } catch (e: Exception) {
            DeleteResult(false, "⚠️ Could not delete messages from $senderAddress")
        }
    }

    data class DeleteResult(
        val success: Boolean,
        val message: String
    )
}
