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

/**
 * Bucket a conversation gets sorted into for inbox triage. Stored on
 * {@code conversations.category} as an integer code; the codes are part of the persisted
 * schema, so values must never be renumbered or removed once shipped.
 */
public enum MessageCategory {
    PERSONAL(0),
    TRANSACTIONS(1),
    PROMOTIONS(2),
    UPDATES(3),
    SPAM(4);

    private final int code;

    MessageCategory(final int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static MessageCategory fromCode(final int code) {
        for (final MessageCategory c : values()) {
            if (c.code == code) {
                return c;
            }
        }
        // Unknown codes can only come from a downgraded DB; fall back to the safest bucket.
        return PERSONAL;
    }
}
