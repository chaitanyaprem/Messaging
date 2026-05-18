/*
 * Copyright (C) 2026 The GrapheneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.messaging.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.android.messaging.R
import com.android.messaging.ui.UIIntents

/**
 * Handles the "Copy code" notification action: writes the OTP digits to the system clipboard
 * and shows a brief confirmation toast. Does not dismiss the notification — the user may want
 * to keep it visible until they've used the code.
 */
class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != UIIntents.ACTION_COPY_OTP) return
        val code = intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_OTP_CODE)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (code != null && clipboard != null) {
            val clip = ClipData.newPlainText("OTP", code).apply {
                // Mark the clip as sensitive on API 33+ so clipboard preview UI redacts it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras = android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                }
            }
            clipboard.setPrimaryClip(clip)
            // Android 13+ shows its own "copied to clipboard" overlay, so we don't toast there
            // to avoid duplicate confirmations.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.otp_code_copied, Toast.LENGTH_SHORT).show()
            }
            // Dismiss the notification — matches user expectation when tapping an action.
            cancelOriginatingNotification(context, intent)
        }
    }

    private fun cancelOriginatingNotification(context: Context, intent: Intent) {
        val nmId = intent.getIntExtra(UIIntents.UI_INTENT_EXTRA_NOTIFICATION_ID, -1)
        val tag = intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_NOTIFICATION_TAG)
        if (nmId == -1) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (tag != null) nm.cancel(tag, nmId) else nm.cancel(nmId)
    }
}
