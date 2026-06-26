/*
 * Copyright (C) 2026 The GrapheneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.messaging.ui.search;

import android.database.Cursor;

import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;
import com.android.messaging.datamodel.DatabaseHelper.ConversationParticipantsColumns;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseHelper.ParticipantColumns;
import com.android.messaging.datamodel.DatabaseHelper.PartColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.SearchDatabase;
import com.android.messaging.datamodel.SearchIndexSyncer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-text message search across the Requery-backed {@link SearchDatabase} sidecar.
 *
 * <p>Two passes, both starting in the FTS sidecar then joining metadata from the main DB:
 * <ol>
 *   <li>{@code messages_fts} → matched {@code parts._id}s → JOIN main DB's
 *       {@code parts → messages → conversations} for per-message snippets.</li>
 *   <li>{@code participants_fts} → matched {@code participants._id}s → JOIN main DB's
 *       {@code conversation_participants → conversations} for contact/phone-number hits.</li>
 * </ol>
 * The two databases live in separate files (framework SQLite for main, Requery's bundled
 * FTS5-enabled SQLite for the sidecar) and aren't joined at the SQL layer; we collect the
 * matched ids from the FTS query, then do a single follow-up IN-clause query in the main DB.
 *
 * <p>Body hits are preferred when both kinds match the same conversation, since the snippet is
 * more informative than a plain contact name.
 */
public final class MessageSearchQuery {
    /** Snippet delimiters; chosen to never collide with real SMS body content. */
    static final char SNIPPET_START = '';
    static final char SNIPPET_END = '';
    static final String SNIPPET_ELLIPSIS = "…";

    private static final int DEFAULT_LIMIT = 200;
    private static final int MIN_QUERY_LENGTH = 2;

    /** Per-conversation result cap: only the most recent matching message is shown. */
    private static final int RESULTS_PER_CONVERSATION = 1;

    private MessageSearchQuery() {
    }

    /**
     * Translates a free-form user query into FTS5 syntax.
     *
     * <p>Each whitespace-separated token is wrapped in double quotes (with embedded quotes
     * doubled per FTS5 escaping) and suffixed with {@code *} so the user gets prefix matching
     * while typing. Multiple tokens are implicitly AND-ed by FTS5.
     *
     * <p>Returns {@code null} if the input is too short or yields no usable tokens.
     */
    public static String toFtsQuery(final String userInput) {
        if (userInput == null) {
            return null;
        }
        // Lowercase with ROOT locale so we match what the unicode61 tokenizer stored at index
        // time. ROOT avoids Turkish-style "I → ı" surprises that would break ASCII queries.
        final String trimmed = userInput.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            return null;
        }
        final String[] tokens = trimmed.split("\\s+");
        final StringBuilder sb = new StringBuilder();
        for (final String raw : tokens) {
            if (raw.isEmpty()) {
                continue;
            }
            // Strip FTS-syntax characters that we never want to honour from end-user input
            // (e.g. column filters, NEAR/AND/OR, parentheses). Quotes are dropped outright
            // because SMS search has no use for literal-quote phrases. The remainder is then
            // quoted as a literal phrase and prefix-matched.
            final String safe = raw
                    .replace("\"", "")
                    .replace("(", "")
                    .replace(")", "")
                    .replace(":", "");
            if (safe.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('"').append(safe).append('"').append('*');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public static List<MessageSearchResult> run(final String userInput) {
        return run(userInput, DEFAULT_LIMIT);
    }

    public static List<MessageSearchResult> run(final String userInput, final int limit) {
        final String ftsQuery = toFtsQuery(userInput);
        if (ftsQuery == null) {
            return Collections.emptyList();
        }
        // Apply any buffered writes from main DB triggers before we query the FTS index.
        SearchIndexSyncer.drain();

        final SearchDatabase searchDb = SearchDatabase.get();
        final DatabaseWrapper mainDb = DataModel.get().getDatabase();
        // Over-fetch then collapse per conversation so users don't see a flood of hits from a
        // single noisy thread.
        final int rawLimit = Math.max(limit, limit * 4);
        final Map<String, MessageSearchResult> byConv = new LinkedHashMap<>();

        collectBodyMatches(searchDb, mainDb, ftsQuery, rawLimit, limit, byConv);
        if (byConv.size() < limit) {
            collectParticipantMatches(searchDb, mainDb, ftsQuery, rawLimit, limit, byConv);
        }
        return new ArrayList<>(byConv.values());
    }

    /**
     * Run the FTS body query in the sidecar, then look up metadata for matched parts in the
     * main DB. Newest matches go into {@code byConv} first.
     */
    private static void collectBodyMatches(final SearchDatabase searchDb,
            final DatabaseWrapper mainDb, final String ftsQuery, final int rawLimit,
            final int convLimit, final Map<String, MessageSearchResult> byConv) {
        final Map<Long, String> partIdToSnippet = new LinkedHashMap<>();
        try (Cursor c = searchDb.queryMessageMatches(
                ftsQuery, SNIPPET_START, SNIPPET_END, SNIPPET_ELLIPSIS, rawLimit)) {
            while (c != null && c.moveToNext()) {
                partIdToSnippet.put(c.getLong(0), c.getString(1));
            }
        }
        if (partIdToSnippet.isEmpty()) {
            return;
        }
        final List<Long> partIds = new ArrayList<>(partIdToSnippet.keySet());
        final String inClause = buildInClause(partIds.size());
        final String sql =
                "SELECT m." + MessageColumns.CONVERSATION_ID + " AS conversation_id, "
                        + "c." + ConversationColumns.NAME + " AS conversation_name, "
                        + "c." + ConversationColumns.ICON + " AS conversation_icon, "
                        + "m." + MessageColumns.RECEIVED_TIMESTAMP + " AS received_timestamp, "
                        + "p." + PartColumns._ID + " AS part_id, "
                        + "m." + MessageColumns._ID + " AS matched_message_id "
                        + "FROM " + DatabaseHelper.PARTS_TABLE + " p "
                        + "INNER JOIN " + DatabaseHelper.MESSAGES_TABLE + " m "
                        + " ON m." + MessageColumns._ID + " = p." + PartColumns.MESSAGE_ID + " "
                        + "INNER JOIN " + DatabaseHelper.CONVERSATIONS_TABLE + " c "
                        + " ON c." + ConversationColumns._ID + " = m."
                        + MessageColumns.CONVERSATION_ID + " "
                        + "WHERE p." + PartColumns._ID + " IN (" + inClause + ") "
                        + "ORDER BY m." + MessageColumns.RECEIVED_TIMESTAMP + " DESC";
        try (Cursor c = mainDb.rawQuery(sql, toStringArray(partIds))) {
            collectInto(c, partIdToSnippet, byConv, convLimit);
        }
    }

    /**
     * Run the FTS participant query in the sidecar, then look up the matching conversations in
     * the main DB. Each surviving conversation's most recent message snippet is taken straight
     * from the matched participant's display name / destination.
     */
    private static void collectParticipantMatches(final SearchDatabase searchDb,
            final DatabaseWrapper mainDb, final String ftsQuery, final int rawLimit,
            final int convLimit, final Map<String, MessageSearchResult> byConv) {
        final List<Long> participantIds = new ArrayList<>();
        try (Cursor c = searchDb.queryParticipantMatches(ftsQuery, rawLimit)) {
            while (c != null && c.moveToNext()) {
                participantIds.add(c.getLong(0));
            }
        }
        if (participantIds.isEmpty()) {
            return;
        }
        final String inClause = buildInClause(participantIds.size());
        final String sql =
                "SELECT cp." + ConversationParticipantsColumns.CONVERSATION_ID
                        + " AS conversation_id, "
                        + "c." + ConversationColumns.NAME + " AS conversation_name, "
                        + "c." + ConversationColumns.ICON + " AS conversation_icon, "
                        + "COALESCE(c." + ConversationColumns.SORT_TIMESTAMP + ", 0) "
                        + "AS received_timestamp, "
                        + "COALESCE(NULLIF(p." + ParticipantColumns.FULL_NAME + ", ''), "
                        + "p." + ParticipantColumns.SEND_DESTINATION + ", "
                        + "p." + ParticipantColumns.NORMALIZED_DESTINATION + ", '') "
                        + "AS body_snippet "
                        + "FROM " + DatabaseHelper.CONVERSATION_PARTICIPANTS_TABLE + " cp "
                        + "INNER JOIN " + DatabaseHelper.PARTICIPANTS_TABLE + " p "
                        + " ON p." + ParticipantColumns._ID + " = cp."
                        + ConversationParticipantsColumns.PARTICIPANT_ID + " "
                        + "INNER JOIN " + DatabaseHelper.CONVERSATIONS_TABLE + " c "
                        + " ON c." + ConversationColumns._ID + " = cp."
                        + ConversationParticipantsColumns.CONVERSATION_ID + " "
                        + "WHERE p." + ParticipantColumns._ID + " IN (" + inClause + ") "
                        + "ORDER BY c." + ConversationColumns.SORT_TIMESTAMP + " DESC";
        try (Cursor c = mainDb.rawQuery(sql, toStringArray(participantIds))) {
            collectParticipantInto(c, byConv, convLimit);
        }
    }

    /** Collect body-match cursor rows into the per-conversation map, attaching the snippet. */
    private static void collectInto(final Cursor c, final Map<Long, String> partIdToSnippet,
            final Map<String, MessageSearchResult> byConv, final int limit) {
        if (c == null) {
            return;
        }
        final int colConvId = c.getColumnIndexOrThrow("conversation_id");
        final int colConvName = c.getColumnIndexOrThrow("conversation_name");
        final int colConvIcon = c.getColumnIndexOrThrow("conversation_icon");
        final int colTs = c.getColumnIndexOrThrow("received_timestamp");
        final int colPartId = c.getColumnIndexOrThrow("part_id");
        final int colMatchMsg = c.getColumnIndexOrThrow("matched_message_id");

        while (c.moveToNext() && byConv.size() < limit) {
            final String convId = c.getString(colConvId);
            if (convId == null || byConv.containsKey(convId)) {
                continue;
            }
            final long partId = c.getLong(colPartId);
            final String snippet = partIdToSnippet.get(partId);
            byConv.put(convId, new MessageSearchResult(
                    convId,
                    c.getString(colConvName),
                    c.getString(colConvIcon),
                    c.getLong(colTs),
                    snippet,
                    c.isNull(colMatchMsg) ? null : c.getString(colMatchMsg)));
            if (RESULTS_PER_CONVERSATION != 1) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "unexpected RESULTS_PER_CONVERSATION=%d", RESULTS_PER_CONVERSATION));
            }
        }
    }

    /** Same as {@link #collectInto} but for participant-match rows (no matched message id). */
    private static void collectParticipantInto(final Cursor c,
            final Map<String, MessageSearchResult> byConv, final int limit) {
        if (c == null) {
            return;
        }
        final int colConvId = c.getColumnIndexOrThrow("conversation_id");
        final int colConvName = c.getColumnIndexOrThrow("conversation_name");
        final int colConvIcon = c.getColumnIndexOrThrow("conversation_icon");
        final int colTs = c.getColumnIndexOrThrow("received_timestamp");
        final int colSnip = c.getColumnIndexOrThrow("body_snippet");

        while (c.moveToNext() && byConv.size() < limit) {
            final String convId = c.getString(colConvId);
            if (convId == null || byConv.containsKey(convId)) {
                continue;
            }
            byConv.put(convId, new MessageSearchResult(
                    convId,
                    c.getString(colConvName),
                    c.getString(colConvIcon),
                    c.getLong(colTs),
                    c.getString(colSnip),
                    null /* matchedMessageId */));
        }
    }

    private static String buildInClause(final int n) {
        final StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    private static String[] toStringArray(final List<Long> ids) {
        final String[] out = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            out[i] = Long.toString(ids.get(i));
        }
        return out;
    }
}
