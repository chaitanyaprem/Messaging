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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.messaging.util.LogUtil;

import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteOpenHelper;

/**
 * Requery-backed sidecar SQLite database that holds the FTS5 indices for message search.
 *
 * <p>The main Bugle database stays on framework SQLite so upstream's data layer + test
 * infrastructure are unaffected (framework SQLite has no FTS5 module on many Android builds,
 * which is why the index lives over here instead of inside the main schema).
 *
 * <p>This sidecar uses content-storing FTS5 (no {@code content=} clause): the index owns its
 * copy of the indexed text. Cross-database content-external FTS isn't supported by SQLite, so
 * we explicitly set {@code rowid = parts._id} (or {@code participants._id}) on insert so the
 * search results can be JOINed back to the main DB by id.
 *
 * <p>Writes here flow through {@link SearchIndexSyncer}, which drains the
 * {@code search_pending_*_updates} tables that capture-triggers in the main DB populate.
 */
public final class SearchDatabase extends SQLiteOpenHelper {
    private static final String TAG = LogUtil.BUGLE_DATABASE_TAG;
    private static final String DB_NAME = "bugle_search.db";
    private static final int VERSION = 1;

    public static final String MESSAGES_FTS_TABLE = "messages_fts";
    public static final String PARTICIPANTS_FTS_TABLE = "participants_fts";

    public static final class MessagesFtsColumns {
        public static final String TEXT = "text";
        private MessagesFtsColumns() {}
    }

    public static final class ParticipantsFtsColumns {
        public static final String FULL_NAME = "full_name";
        public static final String FIRST_NAME = "first_name";
        public static final String SEND_DESTINATION = "send_destination";
        public static final String NORMALIZED_DESTINATION = "normalized_destination";
        private ParticipantsFtsColumns() {}
    }

    private static final String CREATE_MESSAGES_FTS_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS " + MESSAGES_FTS_TABLE + " USING fts5("
                    + MessagesFtsColumns.TEXT + ", "
                    + "tokenize=\"unicode61 remove_diacritics 2\")";

    private static final String CREATE_PARTICIPANTS_FTS_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS " + PARTICIPANTS_FTS_TABLE + " USING fts5("
                    + ParticipantsFtsColumns.FULL_NAME + ", "
                    + ParticipantsFtsColumns.FIRST_NAME + ", "
                    + ParticipantsFtsColumns.SEND_DESTINATION + ", "
                    + ParticipantsFtsColumns.NORMALIZED_DESTINATION + ", "
                    + "tokenize=\"unicode61 remove_diacritics 2\")";

    @Nullable
    private static volatile SearchDatabase sInstance;
    private final Object mLock = new Object();

    public static SearchDatabase get() {
        SearchDatabase local = sInstance;
        if (local != null) {
            return local;
        }
        synchronized (SearchDatabase.class) {
            if (sInstance == null) {
                sInstance = new SearchDatabase(
                        com.android.messaging.Factory.get().getApplicationContext());
            }
            return sInstance;
        }
    }

    private SearchDatabase(final Context context) {
        super(context, DB_NAME, null, VERSION, null);
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        db.execSQL(CREATE_MESSAGES_FTS_SQL);
        db.execSQL(CREATE_PARTICIPANTS_FTS_SQL);
        LogUtil.i(TAG, "SearchDatabase created (FTS5 ready)");
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        // No upgrade paths yet; on a downgrade or unknown state we rebuild from scratch since
        // the search index is derived state and the syncer will re-populate it.
        if (oldVersion != newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + MESSAGES_FTS_TABLE);
            db.execSQL("DROP TABLE IF EXISTS " + PARTICIPANTS_FTS_TABLE);
            onCreate(db);
        }
    }

    @Override
    public void onDowngrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }

    // -- Mutators (called by SearchIndexSyncer) ------------------------------------------------

    /**
     * INSERT-OR-REPLACE the message-body row whose rowid is {@code partId}. {@code text} may
     * be null or empty — we still write so the row exists with empty content; the caller is
     * expected to filter blanks out of the pending log if it cares about index size.
     */
    public void upsertMessage(final long partId, @Nullable final String text) {
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM " + MESSAGES_FTS_TABLE + " WHERE rowid = ?",
                    new Object[] { partId });
            final ContentValues values = new ContentValues(2);
            values.put("rowid", partId);
            values.put(MessagesFtsColumns.TEXT, text == null ? "" : text);
            db.insert(MESSAGES_FTS_TABLE, null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Remove the FTS row keyed by {@code partId}. No-op if the row isn't present. */
    public void deleteMessage(final long partId) {
        final SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM " + MESSAGES_FTS_TABLE + " WHERE rowid = ?",
                new Object[] { partId });
    }

    /** INSERT-OR-REPLACE the participant row whose rowid is {@code participantId}. */
    public void upsertParticipant(final long participantId,
            @Nullable final String fullName, @Nullable final String firstName,
            @Nullable final String sendDestination,
            @Nullable final String normalizedDestination) {
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM " + PARTICIPANTS_FTS_TABLE + " WHERE rowid = ?",
                    new Object[] { participantId });
            final ContentValues values = new ContentValues(5);
            values.put("rowid", participantId);
            values.put(ParticipantsFtsColumns.FULL_NAME, fullName == null ? "" : fullName);
            values.put(ParticipantsFtsColumns.FIRST_NAME, firstName == null ? "" : firstName);
            values.put(ParticipantsFtsColumns.SEND_DESTINATION,
                    sendDestination == null ? "" : sendDestination);
            values.put(ParticipantsFtsColumns.NORMALIZED_DESTINATION,
                    normalizedDestination == null ? "" : normalizedDestination);
            db.insert(PARTICIPANTS_FTS_TABLE, null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Remove the participant FTS row keyed by {@code participantId}. */
    public void deleteParticipant(final long participantId) {
        final SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM " + PARTICIPANTS_FTS_TABLE + " WHERE rowid = ?",
                new Object[] { participantId });
    }

    // -- Readers (called by MessageSearchQuery) ------------------------------------------------

    /**
     * @return raw cursor over {@code rowid}, snippet for body matches against {@code ftsQuery}.
     *     Caller must close. {@code rowid} corresponds to {@code parts._id} in the main DB.
     */
    @NonNull
    public Cursor queryMessageMatches(final String ftsQuery, final char snippetStart,
            final char snippetEnd, final String snippetEllipsis, final int limit) {
        final SQLiteDatabase db = getReadableDatabase();
        return db.rawQuery(
                "SELECT rowid AS part_id, "
                        + "snippet(" + MESSAGES_FTS_TABLE + ", 0, ?, ?, ?, 8) AS body_snippet "
                        + "FROM " + MESSAGES_FTS_TABLE
                        + " WHERE " + MESSAGES_FTS_TABLE + " MATCH ? "
                        + "LIMIT ?",
                new String[] {
                        String.valueOf(snippetStart),
                        String.valueOf(snippetEnd),
                        snippetEllipsis,
                        ftsQuery,
                        Integer.toString(limit),
                });
    }

    /**
     * @return cursor over rowids matching {@code ftsQuery} on participant fields. Caller closes.
     *     {@code rowid} corresponds to {@code participants._id} in the main DB.
     */
    @NonNull
    public Cursor queryParticipantMatches(final String ftsQuery, final int limit) {
        final SQLiteDatabase db = getReadableDatabase();
        return db.rawQuery(
                "SELECT rowid AS participant_id FROM " + PARTICIPANTS_FTS_TABLE
                        + " WHERE " + PARTICIPANTS_FTS_TABLE + " MATCH ? "
                        + "LIMIT ?",
                new String[] {
                        ftsQuery,
                        Integer.toString(limit),
                });
    }
}
