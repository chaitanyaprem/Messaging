/*
 * Copyright (C) 2026 The GrapheneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.messaging.category;

import android.content.ContentValues;
import android.database.Cursor;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseHelper.PartColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bridge between {@link MessageCategorizer} and the {@code conversations} table.
 *
 * <p>Reads the inputs the classifier needs (sender, resolved contact name, sample message
 * bodies, group flag) from the DB, runs the classifier, and writes the result back into
 * {@code conversations.category} — but never if the user has set
 * {@code category_override = 1}, since at that point the bucket is theirs to own.
 *
 * <p>Designed to be cheap enough to invoke after every incoming message: it does at most two
 * small SELECTs, runs a handful of regex checks, and skips the UPDATE when the new category
 * matches the old. Callers must hold a transaction.
 */
public final class CategoryUpdater {
    private static final String TAG = LogUtil.BUGLE_DATABASE_TAG;

    /** Most recent message bodies to feed the classifier. More gives stability; fewer is faster. */
    private static final int SAMPLE_MESSAGE_LIMIT = 5;

    private static final MessageCategorizer DEFAULT_CATEGORIZER = new RulesBasedCategorizer();

    private CategoryUpdater() {
    }

    /**
     * Re-evaluates a conversation's category. No-op when the user has set an override or when
     * the categorizer's verdict matches the value already stored.
     */
    public static void maybeRefresh(@NonNull final DatabaseWrapper db,
            @NonNull final String conversationId) {
        maybeRefresh(db, conversationId, DEFAULT_CATEGORIZER);
    }

    /**
     * Stamps a user-chosen category onto a conversation and sets {@code category_override = 1}
     * so the automatic classifier won't reshuffle it later. Notifies the conversation list URI
     * so the chip-filtered view updates immediately.
     */
    public static void applyManualOverride(@NonNull final DatabaseWrapper db,
            @NonNull final String conversationId,
            @NonNull final MessageCategory category) {
        final ContentValues values = new ContentValues(2);
        values.put(ConversationColumns.CATEGORY, category.getCode());
        values.put(ConversationColumns.CATEGORY_OVERRIDE, 1);
        db.update(DatabaseHelper.CONVERSATIONS_TABLE, values,
                ConversationColumns._ID + "=?",
                new String[] { conversationId });
        MessagingContentProvider.notifyConversationListChanged();
    }

    /** Test seam — lets unit tests inject a deterministic categorizer. */
    static void maybeRefresh(@NonNull final DatabaseWrapper db,
            @NonNull final String conversationId,
            @NonNull final MessageCategorizer categorizer) {
        final ConversationFacts facts = readFacts(db, conversationId);
        if (facts == null) {
            return;
        }
        if (facts.overridden) {
            return;
        }

        final List<String> samples = readRecentBodies(db, conversationId);
        final MessageCategory next = categorizer.classify(
                facts.senderDestination,
                facts.contactDisplayName,
                samples,
                facts.isGroup);

        if (next.getCode() == facts.currentCategoryCode) {
            return;
        }

        final ContentValues values = new ContentValues(1);
        values.put(ConversationColumns.CATEGORY, next.getCode());
        db.update(DatabaseHelper.CONVERSATIONS_TABLE, values,
                ConversationColumns._ID + "=?",
                new String[] { conversationId });

        if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
            LogUtil.d(TAG, "Category " + MessageCategory.fromCode(facts.currentCategoryCode)
                    + " -> " + next + " for conversation " + conversationId
                    + " (sender=" + facts.senderDestination + ")");
        }
        // Make the conversation list re-query so the new bucket assignment is visible without
        // requiring a manual chip tap or activity recreation.
        MessagingContentProvider.notifyConversationListChanged();
    }

    @Nullable
    private static ConversationFacts readFacts(final DatabaseWrapper db,
            final String conversationId) {
        try (Cursor c = db.query(DatabaseHelper.CONVERSATIONS_TABLE,
                new String[] {
                        ConversationColumns.CATEGORY,
                        ConversationColumns.CATEGORY_OVERRIDE,
                        ConversationColumns.NAME,
                        ConversationColumns.PARTICIPANT_CONTACT_ID,
                        ConversationColumns.PARTICIPANT_COUNT,
                        ConversationColumns.OTHER_PARTICIPANT_NORMALIZED_DESTINATION,
                },
                ConversationColumns._ID + "=?",
                new String[] { conversationId },
                null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            final ConversationFacts f = new ConversationFacts();
            f.currentCategoryCode = c.getInt(0);
            f.overridden = c.getInt(1) != 0;
            final String name = c.isNull(2) ? null : c.getString(2);
            final long contactId = c.isNull(3) ? 0L : c.getLong(3);
            f.isGroup = c.getInt(4) > 1;
            f.senderDestination = c.isNull(5) ? null : c.getString(5);
            // Only treat the conversation name as a resolved contact name when the row was
            // matched against a real Contacts entry; otherwise it's a pretty-printed phone
            // number and shouldn't short-circuit the classifier to Personal.
            f.contactDisplayName = (contactId > ParticipantData.PARTICIPANT_CONTACT_ID_NOT_RESOLVED
                    && !TextUtils.isEmpty(name)) ? name : null;
            return f;
        }
    }

    private static List<String> readRecentBodies(final DatabaseWrapper db,
            final String conversationId) {
        final String sql = "SELECT p." + PartColumns.TEXT + " "
                + "FROM " + DatabaseHelper.PARTS_TABLE + " p "
                + "INNER JOIN " + DatabaseHelper.MESSAGES_TABLE + " m "
                + " ON m." + MessageColumns._ID + " = p." + PartColumns.MESSAGE_ID + " "
                + "WHERE m." + MessageColumns.CONVERSATION_ID + " = ? "
                + " AND p." + PartColumns.TEXT + " IS NOT NULL "
                + " AND p." + PartColumns.TEXT + " <> '' "
                + "ORDER BY m." + MessageColumns.RECEIVED_TIMESTAMP + " DESC "
                + "LIMIT " + SAMPLE_MESSAGE_LIMIT;
        try (Cursor c = db.rawQuery(sql, new String[] { conversationId })) {
            if (c == null) {
                return Collections.emptyList();
            }
            final List<String> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
            return out;
        }
    }

    private static final class ConversationFacts {
        int currentCategoryCode;
        boolean overridden;
        @Nullable String contactDisplayName;
        @Nullable String senderDestination;
        boolean isGroup;
    }
}
