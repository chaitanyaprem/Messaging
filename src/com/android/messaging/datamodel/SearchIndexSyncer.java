/*
 * Copyright (C) 2026 The GrapheneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.messaging.datamodel;

import android.database.Cursor;

import com.android.messaging.util.LogUtil;

/**
 * Drains the {@code search_pending_*_updates} log tables in the main Bugle DB into
 * {@link SearchDatabase}'s FTS5 indices.
 *
 * <p>Capture triggers on the main DB's {@code parts} and {@code participants} tables enqueue
 * one row per write into the pending-updates tables. This syncer reads those rows, applies
 * them to the search DB, and removes the processed entries. Crash-safe: a partially-drained
 * batch leaves the un-processed pending rows in place for the next drain.
 *
 * <p>{@link MessageSearchQuery} calls {@link #drain()} before each search so results never
 * miss recent writes. There's no background thread or scheduler — the cost is paid lazily on
 * the search hot path, which is user-initiated.
 */
public final class SearchIndexSyncer {
    private static final String TAG = LogUtil.BUGLE_DATABASE_TAG;
    private static final int BATCH_SIZE = 500;

    /** Single mutex so concurrent searches don't fight over the same pending rows. */
    private static final Object sLock = new Object();

    private SearchIndexSyncer() {
    }

    /**
     * Apply all pending message and participant updates to the search DB. Returns once the
     * pending tables are empty. Safe to call from any thread; serialized internally.
     */
    public static void drain() {
        synchronized (sLock) {
            final DatabaseWrapper main = DataModel.get().getDatabase();
            final SearchDatabase search = SearchDatabase.get();
            final long start = System.currentTimeMillis();
            final int messagesDrained = drainMessages(main, search);
            final int participantsDrained = drainParticipants(main, search);
            if (messagesDrained > 0 || participantsDrained > 0) {
                LogUtil.i(TAG, "SearchIndexSyncer drained " + messagesDrained
                        + " message + " + participantsDrained + " participant updates in "
                        + (System.currentTimeMillis() - start) + " ms");
            }
        }
    }

    // -- Message updates ----------------------------------------------------------------------

    private static int drainMessages(final DatabaseWrapper main, final SearchDatabase search) {
        int total = 0;
        while (true) {
            final int processed = drainMessageBatch(main, search);
            if (processed == 0) {
                return total;
            }
            total += processed;
        }
    }

    private static int drainMessageBatch(final DatabaseWrapper main, final SearchDatabase search) {
        long lastId = -1;
        int count = 0;
        try (Cursor c = main.rawQuery(
                "SELECT _id, op, part_id, text_value FROM " + DatabaseHelper.SEARCH_PENDING_MESSAGE_UPDATES_TABLE
                        + " ORDER BY _id LIMIT " + BATCH_SIZE, null)) {
            while (c != null && c.moveToNext()) {
                final long id = c.getLong(0);
                final String op = c.getString(1);
                final long partId = c.getLong(2);
                final String text = c.isNull(3) ? null : c.getString(3);
                if ("delete".equals(op)) {
                    search.deleteMessage(partId);
                } else {
                    search.upsertMessage(partId, text);
                }
                lastId = id;
                count++;
            }
        }
        if (lastId >= 0) {
            main.execSQL("DELETE FROM " + DatabaseHelper.SEARCH_PENDING_MESSAGE_UPDATES_TABLE
                    + " WHERE _id <= " + lastId);
        }
        return count;
    }

    // -- Participant updates ------------------------------------------------------------------

    private static int drainParticipants(final DatabaseWrapper main, final SearchDatabase search) {
        int total = 0;
        while (true) {
            final int processed = drainParticipantBatch(main, search);
            if (processed == 0) {
                return total;
            }
            total += processed;
        }
    }

    private static int drainParticipantBatch(final DatabaseWrapper main,
            final SearchDatabase search) {
        long lastId = -1;
        int count = 0;
        try (Cursor c = main.rawQuery(
                "SELECT _id, op, participant_id, full_name, first_name, send_destination, "
                        + "normalized_destination "
                        + "FROM " + DatabaseHelper.SEARCH_PENDING_PARTICIPANT_UPDATES_TABLE
                        + " ORDER BY _id LIMIT " + BATCH_SIZE, null)) {
            while (c != null && c.moveToNext()) {
                final long id = c.getLong(0);
                final String op = c.getString(1);
                final long pid = c.getLong(2);
                if ("delete".equals(op)) {
                    search.deleteParticipant(pid);
                } else {
                    search.upsertParticipant(pid,
                            c.isNull(3) ? null : c.getString(3),
                            c.isNull(4) ? null : c.getString(4),
                            c.isNull(5) ? null : c.getString(5),
                            c.isNull(6) ? null : c.getString(6));
                }
                lastId = id;
                count++;
            }
        }
        if (lastId >= 0) {
            main.execSQL("DELETE FROM " + DatabaseHelper.SEARCH_PENDING_PARTICIPANT_UPDATES_TABLE
                    + " WHERE _id <= " + lastId);
        }
        return count;
    }
}
