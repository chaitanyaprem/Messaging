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

/**
 * Single row returned by {@link MessageSearchQuery}.
 */
public final class MessageSearchResult {
    public final String conversationId;
    public final String conversationName;
    public final String iconUri;
    public final long messageReceivedTimestamp;
    /**
     * Body excerpt with FTS5 snippet markers. Use {@link SearchHighlight#toSpannable} to render.
     */
    public final String snippet;
    /**
     * {@code messages._id} of the message whose body matched, or {@code null} for
     * participant-only matches (where there's no single message to point at). The activity
     * uses this to compute a scroll-to position before launching the conversation.
     */
    public final String matchedMessageId;

    public MessageSearchResult(final String conversationId,
            final String conversationName, final String iconUri,
            final long messageReceivedTimestamp, final String snippet,
            final String matchedMessageId) {
        this.conversationId = conversationId;
        this.conversationName = conversationName;
        this.iconUri = iconUri;
        this.messageReceivedTimestamp = messageReceivedTimestamp;
        this.snippet = snippet;
        this.matchedMessageId = matchedMessageId;
    }
}
