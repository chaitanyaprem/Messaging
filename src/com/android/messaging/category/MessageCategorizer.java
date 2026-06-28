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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Assigns a {@link MessageCategory} to a conversation based on its sender and recent message
 * content. Implementations are expected to be deterministic and cheap (~ms) so the result can
 * be computed inline on every new-conversation insert and during bulk backfill.
 *
 * <p>The seam exists so a later on-device ML classifier can plug in without disturbing schema
 * or callers — the rules-based implementation ({@link RulesBasedCategorizer}) is the v1 default.
 */
public interface MessageCategorizer {

    /**
     * @param senderDestination the raw destination string (phone number, short code, email,
     *     etc.) that the conversation is with. May be null for outgoing-only drafts.
     * @param contactDisplayName a resolved contact name if the sender matches a saved contact;
     *     null otherwise. A non-null value is a strong signal to keep the conversation in
     *     {@link MessageCategory#PERSONAL} regardless of message content.
     * @param sampleMessageTexts up to a handful of recent message bodies (most recent first).
     *     Callers should pass at least one entry where available; an empty list is acceptable
     *     and results in {@link MessageCategory#PERSONAL}.
     * @param isGroup true for multi-participant conversations; these are always personal.
     */
    @NonNull
    MessageCategory classify(@Nullable String senderDestination,
                             @Nullable String contactDisplayName,
                             @NonNull List<String> sampleMessageTexts,
                             boolean isGroup);
}
