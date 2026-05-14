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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs full-text search against the FTS5 indexes added in db v3 (messages) and v4 (participants).
 *
 * <p>Two passes:
 * <ol>
 *   <li>{@code messages_fts} → {@code parts} → {@code messages} → {@code conversations} for body
 *       hits, returning a per-message snippet with the matched word delimited.</li>
 *   <li>{@code participants_fts} → {@code conversation_participants} → {@code conversations} for
 *       contact-name and phone-number hits, returning one row per matching conversation with the
 *       matched participant's display name as the snippet.</li>
 * </ol>
 * Body hits are preferred when both kinds match the same conversation, since they tell the user
 * exactly which message was hit. Participant hits fill in conversations that the body pass missed.
 */
public final class MessageSearchQuery {
    /** Snippet delimiters; chosen to never collide with real SMS body content. */
    static final char SNIPPET_START = '\u0001';
    static final char SNIPPET_END = '\u0002';
    static final String SNIPPET_ELLIPSIS = "…";

    private static final int DEFAULT_LIMIT = 200;
    private static final int MIN_QUERY_LENGTH = 2;

    /** Per-conversation result cap: only the most recent matching message is shown. */
    private static final int RESULTS_PER_CONVERSATION = 1;

    private static final String SEARCH_SQL =
            "SELECT m." + MessageColumns.CONVERSATION_ID + " AS conversation_id, "
                    + "c." + ConversationColumns.NAME + " AS conversation_name, "
                    + "c." + ConversationColumns.ICON + " AS conversation_icon, "
                    + "m." + MessageColumns.RECEIVED_TIMESTAMP + " AS received_timestamp, "
                    + "snippet(" + DatabaseHelper.MESSAGES_FTS_TABLE + ", 0, ?, ?, ?, 8) "
                    + "AS body_snippet "
                    + "FROM " + DatabaseHelper.MESSAGES_FTS_TABLE + " f "
                    + "INNER JOIN " + DatabaseHelper.PARTS_TABLE + " p "
                    + " ON p." + PartColumns._ID + " = f.rowid "
                    + "INNER JOIN " + DatabaseHelper.MESSAGES_TABLE + " m "
                    + " ON m." + MessageColumns._ID + " = p." + PartColumns.MESSAGE_ID + " "
                    + "INNER JOIN " + DatabaseHelper.CONVERSATIONS_TABLE + " c "
                    + " ON c." + ConversationColumns._ID + " = m."
                    + MessageColumns.CONVERSATION_ID + " "
                    + "WHERE " + DatabaseHelper.MESSAGES_FTS_TABLE + " MATCH ? "
                    + "ORDER BY m." + MessageColumns.RECEIVED_TIMESTAMP + " DESC "
                    + "LIMIT ?";

    // Returns one row per (conversation, matching participant). Snippet is the participant's
    // display name (or destination if unnamed); timestamp comes from the conversation's
    // sort_timestamp so a contact match floats the conversation to the same position it would
    // hold in the inbox.
    private static final String PARTICIPANT_SEARCH_SQL =
            "SELECT cp." + ConversationParticipantsColumns.CONVERSATION_ID + " AS conversation_id, "
                    + "c." + ConversationColumns.NAME + " AS conversation_name, "
                    + "c." + ConversationColumns.ICON + " AS conversation_icon, "
                    + "COALESCE(c." + ConversationColumns.SORT_TIMESTAMP + ", 0) "
                    + "AS received_timestamp, "
                    + "COALESCE(NULLIF(p." + ParticipantColumns.FULL_NAME + ", ''), "
                    + "p." + ParticipantColumns.SEND_DESTINATION + ", "
                    + "p." + ParticipantColumns.NORMALIZED_DESTINATION + ", '') "
                    + "AS body_snippet "
                    + "FROM " + DatabaseHelper.PARTICIPANTS_FTS_TABLE + " pf "
                    + "INNER JOIN " + DatabaseHelper.CONVERSATION_PARTICIPANTS_TABLE + " cp "
                    + " ON cp." + ConversationParticipantsColumns.PARTICIPANT_ID + " = pf.rowid "
                    + "INNER JOIN " + DatabaseHelper.PARTICIPANTS_TABLE + " p "
                    + " ON p." + ParticipantColumns._ID + " = pf.rowid "
                    + "INNER JOIN " + DatabaseHelper.CONVERSATIONS_TABLE + " c "
                    + " ON c." + ConversationColumns._ID + " = cp."
                    + ConversationParticipantsColumns.CONVERSATION_ID + " "
                    + "WHERE " + DatabaseHelper.PARTICIPANTS_FTS_TABLE + " MATCH ? "
                    + "ORDER BY c." + ConversationColumns.SORT_TIMESTAMP + " DESC "
                    + "LIMIT ?";

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
        final String trimmed = userInput.trim();
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
        final DatabaseWrapper db = DataModel.get().getDatabase();
        // Over-fetch then collapse per conversation so users don't see a flood of hits from a
        // single noisy thread.
        final int rawLimit = Math.max(limit, limit * 4);
        final Map<String, MessageSearchResult> byConv = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery(SEARCH_SQL, new String[] {
                String.valueOf(SNIPPET_START),
                String.valueOf(SNIPPET_END),
                SNIPPET_ELLIPSIS,
                ftsQuery,
                String.valueOf(rawLimit),
        })) {
            collectInto(c, byConv, limit);
        }
        if (byConv.size() < limit) {
            // Fill remaining slots with conversations whose participant (name or phone) matched
            // but whose body text didn't. Body matches keep their slot since the snippet is
            // more informative than a plain contact name.
            try (Cursor c = db.rawQuery(PARTICIPANT_SEARCH_SQL, new String[] {
                    ftsQuery,
                    String.valueOf(rawLimit),
            })) {
                collectInto(c, byConv, limit);
            }
        }
        return new ArrayList<>(byConv.values());
    }

    private static void collectInto(final Cursor c, final Map<String, MessageSearchResult> byConv,
            final int limit) {
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
                    c.getString(colSnip)));
            if (RESULTS_PER_CONVERSATION != 1) {
                // Reserved for future tuning; current spec is one row per conversation.
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "unexpected RESULTS_PER_CONVERSATION=%d", RESULTS_PER_CONVERSATION));
            }
        }
    }
}
