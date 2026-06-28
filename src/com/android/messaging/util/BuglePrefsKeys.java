/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.messaging.util;

/**
 * List of shared preferences keys and default values. These are all internal
 * (not user-visible) preferences. Preferences that are exposed via the Settings
 * activity should be defined in the constants.xml resource file instead.
 */
public final class BuglePrefsKeys {
    private BuglePrefsKeys() {}   // do not instantiate

    /**
     * Bugle's shared preferences version
     */
    public static final String SHARED_PREFERENCES_VERSION =
            "shared_preferences_version";
    public static final int SHARED_PREFERENCES_VERSION_DEFAULT =
            BuglePrefs.NO_SHARED_PREFERENCES_VERSION;

    /**
     * Last time that we ran a a sync (in millis)
     */
    public static final String LAST_SYNC_TIME
            = "last_sync_time_millis";
    public static final long LAST_SYNC_TIME_DEFAULT
            = -1;

    /**
     * Last time that we ran a full sync (in millis)
     */
    public static final String LAST_FULL_SYNC_TIME
            = "last_full_sync_time_millis";
    public static final long LAST_FULL_SYNC_TIME_DEFAULT
            = -1;

    /**
     * Timestamp of the message for which we last did a message notification.
     */
    public static final String LATEST_NOTIFICATION_MESSAGE_TIMESTAMP
        = "latest_notification_message_timestamp";

    /**
     * The last selected chooser index in the media picker.
     */
    public static final String SELECTED_MEDIA_PICKER_CHOOSER_INDEX
            = "selected_media_picker_chooser_index";
    public static final int SELECTED_MEDIA_PICKER_CHOOSER_INDEX_DEFAULT
            = -1;

    /**
     * The attempt number when retrying ProcessPendingMessagesAction
     */
    public static final String PROCESS_PENDING_MESSAGES_RETRY_COUNT
            = BuglePrefs.SHARED_PREFERENCES_PER_SUBSCRIPTION_PREFIX + "process_pending_retry";

    /**
     * True while a one-shot background categorization sweep of existing conversations is still
     * owed (set by the v3 DB upgrade so we don't try to backfill on the upgrade thread). Cleared
     * once {@link com.android.messaging.category.CategoryBackfiller} finishes its sweep.
     */
    public static final String CATEGORY_BACKFILL_PENDING = "category_backfill_pending";
    public static final boolean CATEGORY_BACKFILL_PENDING_DEFAULT = false;

    /**
     * Version of {@link com.android.messaging.category.MessageCategorizer} rules whose verdicts
     * are currently reflected in the conversations table. Used to trigger a re-classification
     * sweep when the classifier code changes meaningfully (e.g. a new pattern is added).
     */
    public static final String CATEGORY_CLASSIFIER_VERSION = "category_classifier_version";
    public static final int CATEGORY_CLASSIFIER_VERSION_DEFAULT = 0;
}
