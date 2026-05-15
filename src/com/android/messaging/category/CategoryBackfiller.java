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

import android.database.Cursor;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.BuglePrefsKeys;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * One-shot sweep that fills in {@code conversations.category} for rows that pre-date the
 * categorization feature. Gated by {@link BuglePrefsKeys#CATEGORY_BACKFILL_PENDING}, which the
 * v3 DB upgrade sets to true; this class clears the flag when it finishes so the sweep never
 * runs twice.
 *
 * <p>Runs on a low-priority daemon thread so app startup isn't blocked. Crash-safe — if the
 * process dies mid-sweep, the next launch finds the flag still set and starts over (the
 * underlying {@link CategoryUpdater#maybeRefresh} is idempotent).
 */
public final class CategoryBackfiller {
    private static final String TAG = LogUtil.BUGLE_DATABASE_TAG;

    private CategoryBackfiller() {
    }

    /**
     * Kicks off a sweep on a background thread if the pending flag is set. Returns immediately
     * either way.
     */
    public static void runIfPending() {
        final BuglePrefs prefs = Factory.get().getApplicationPrefs();
        if (!prefs.getBoolean(
                BuglePrefsKeys.CATEGORY_BACKFILL_PENDING,
                BuglePrefsKeys.CATEGORY_BACKFILL_PENDING_DEFAULT)) {
            return;
        }
        final Thread worker = new Thread(CategoryBackfiller::sweep, "CategoryBackfill");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void sweep() {
        final long startMs = System.currentTimeMillis();
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            final List<String> ids = readConversationIds(db);
            for (final String conversationId : ids) {
                db.beginTransaction();
                try {
                    CategoryUpdater.maybeRefresh(db, conversationId);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            Factory.get().getApplicationPrefs()
                    .putBoolean(BuglePrefsKeys.CATEGORY_BACKFILL_PENDING, false);
            LogUtil.i(TAG, "Category backfill complete: " + ids.size()
                    + " conversations classified in " + (System.currentTimeMillis() - startMs)
                    + " ms");
        } catch (final RuntimeException ex) {
            // Leave the flag set so the sweep retries on next launch.
            LogUtil.e(TAG, "Category backfill failed", ex);
        }
    }

    private static List<String> readConversationIds(final DatabaseWrapper db) {
        try (Cursor c = db.query(DatabaseHelper.CONVERSATIONS_TABLE,
                new String[] { ConversationColumns._ID },
                null, null, null, null,
                ConversationColumns._ID + " ASC")) {
            if (c == null) {
                return new ArrayList<>();
            }
            final List<String> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
            return out;
        }
    }
}
