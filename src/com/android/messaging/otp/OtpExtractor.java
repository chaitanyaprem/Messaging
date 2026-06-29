/*
 * Copyright (C) 2026 The GrapheneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.messaging.otp;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a one-time password / verification code out of an SMS body when one is present.
 * Conservative on purpose: we'd rather surface no OTP action than copy a wrong digit chunk
 * (e.g. a price, year, or PIN-shaped reference number) into the user's clipboard.
 *
 * <p>Matching strategy: require a trigger phrase ({@code OTP}, {@code verification code},
 * {@code verify}, {@code code}, {@code PIN}, {@code password}, etc.) within a short window
 * of a 4–8 digit number. Either direction works ({@code "OTP is 482915"} or {@code "482915 is
 * your OTP"}). A standalone digit chunk with no context never matches.
 */
public final class OtpExtractor {

    /** Reject 4-digit candidates that look like a year (e.g. {@code 2026}). */
    private static final Pattern YEAR_LIKE = Pattern.compile("^(19|20)\\d{2}$");

    /** Currency-shaped tokens that disqualify a candidate when they immediately precede it. */
    private static final Pattern CURRENCY_PREFIX = Pattern.compile(
            "(?:rs\\.?|inr|usd|eur|gbp|aud|cad|\\$|\\u20ac|\\u20b9|\\u00a3)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Trigger phrases near (before or after) the candidate code. */
    private static final String TRIGGER =
            "(?:otp|one[\\s-]?time[\\s-]?(?:password|code|pin)|"
                    + "verification[\\s-]?code|verify[\\s-]?(?:your[\\s-]?)?code|"
                    + "auth(?:entication)?[\\s-]?code|security[\\s-]?code|"
                    // "your code", "your AppName code", "your AppName service code", etc.
                    + "your[\\s-]?(?:[\\w-]+[\\s-]){0,3}code|"
                    // bare "code" only when followed by "is", ":" or "for".
                    + "code\\s*(?:is\\b|:|for\\b)|"
                    + "passcode|pass[\\s-]?code|"
                    + "pin\\b)";

    /** Trigger anchored at a word boundary. We do trigger search and digit search separately
     *  so a rejected digit candidate (e.g. currency-prefixed amount) doesn't prevent us from
     *  finding the real code further along in the same trigger window. */
    private static final Pattern TRIGGER_PATTERN = Pattern.compile(
            "\\b" + TRIGGER, Pattern.CASE_INSENSITIVE);

    /** 4–8 digit number at a word boundary on both sides. */
    private static final Pattern DIGIT_CHUNK = Pattern.compile("\\b(\\d{4,8})\\b");

    /** Maximum distance (in characters) between a trigger phrase and the candidate code. */
    private static final int WINDOW = 30;

    private OtpExtractor() {
    }

    /**
     * @return the digit string of the OTP if a code is clearly present, otherwise null.
     *     The returned value is exactly the digits — no surrounding whitespace, no labels.
     */
    @Nullable
    public static String extract(@Nullable final String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        // Pass 1: each trigger occurrence, look forward up to WINDOW chars for a digit chunk
        // that passes the acceptability filter.
        final Matcher tm = TRIGGER_PATTERN.matcher(body);
        while (tm.find()) {
            final int from = tm.end();
            final int to = Math.min(body.length(), from + WINDOW);
            final String hit = findCodeIn(body, from, to);
            if (hit != null) {
                return hit;
            }
        }
        // Pass 2: each digit chunk, look forward up to WINDOW chars for a trigger occurrence
        // (covers "482915 is your code"-style messages).
        final Matcher dm = DIGIT_CHUNK.matcher(body);
        while (dm.find()) {
            final String candidate = dm.group(1);
            final int codeStart = dm.start(1);
            if (!acceptable(body, codeStart, candidate)) {
                continue;
            }
            final int from = dm.end();
            final int to = Math.min(body.length(), from + WINDOW);
            final Matcher trailingTrigger = TRIGGER_PATTERN.matcher(body).region(from, to);
            if (trailingTrigger.find()) {
                return candidate;
            }
        }
        return null;
    }

    /** Scan {@code body[from..to)} for the first acceptable digit chunk and return it. */
    @Nullable
    private static String findCodeIn(final String body, final int from, final int to) {
        if (from >= to) {
            return null;
        }
        final Matcher dm = DIGIT_CHUNK.matcher(body).region(from, to);
        while (dm.find()) {
            final String candidate = dm.group(1);
            if (acceptable(body, dm.start(1), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean acceptable(final String body, final int codeStart, final String s) {
        if (s == null || s.length() < 4 || s.length() > 8) {
            return false;
        }
        // Year-shaped 4-digit numbers are rarely OTPs; skip them to avoid false positives
        // on text like "Member since 2024, your account...".
        if (s.length() == 4 && YEAR_LIKE.matcher(s).matches()) {
            return false;
        }
        // Reject currency-prefixed numbers ("Rs 5000", "$50") — they're amounts, not codes.
        final int prefixStart = Math.max(0, codeStart - 8);
        final String prefix = body.substring(prefixStart, codeStart);
        if (CURRENCY_PREFIX.matcher(prefix).find()) {
            return false;
        }
        return true;
    }
}
