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
import android.content.Context
import android.content.Intent
import com.android.messaging.datamodel.action.DeleteMessageAction
import com.android.messaging.ui.UIIntents

/**
 * Handles the "Delete" notification action: deletes the message that the notification was
 * referring to (the latest message in the conversation when the notification was built) and
 * dismisses the notification so the user lands back at a clean shade.
 */
class MessageDeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != UIIntents.ACTION_DELETE_MESSAGE) return
        val messageId = intent.getStringExtra(UIIntents.UI_INTENT_EXTRA_MESSAGE_ID_TO_DELETE)
        if (messageId != null) {
            DeleteMessageAction.deleteMessage(messageId)
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
