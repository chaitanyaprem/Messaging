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
import java.util.regex.Pattern;

/**
 * Pattern-based first pass at message categorization.
 *
 * <p>The rules look at the most recent message body texts and check them against a small set
 * of pre-compiled regex patterns per bucket. Buckets are evaluated in priority order
 * (spam &gt; transactions &gt; updates &gt; promotions); the first bucket whose pattern fires
 * wins. Conversations with a resolved contact name or with multiple participants are kept in
 * {@link MessageCategory#PERSONAL} regardless of content — friends sometimes paste a
 * coupon code at us, and we don't want that to evict the thread from the personal bucket.
 *
 * <p>Patterns are word-boundary-aware where appropriate so a bank message containing "OTP"
 * doesn't get out-matched by a friend whose surname happens to contain "otp" as a substring.
 */
public final class RulesBasedCategorizer implements MessageCategorizer {

    // -- Transactions --------------------------------------------------------------------------
    // OTP / verification codes, account debits/credits, balance alerts, currency amounts.
    private static final Pattern TRANSACTION_PATTERN = Pattern.compile(
            "\\b(otp|one[\\s-]?time[\\s-]?(password|code|pin)|"
                    + "verification[\\s-]?code|verify[\\s-]?(your[\\s-]?)?code|"
                    + "auth(entication)?[\\s-]?code|security[\\s-]?code|"
                    + "your[\\s-]?code[\\s:]|"
                    + "debited|credited|deducted|transferred|"
                    + "transaction|txn|"
                    + "balance|bal[\\s.:]|a/?c[\\s\\.]|account[\\s\\.](ending|number|balance)|"
                    + "card[\\s\\.]ending|card[\\s\\.]ending[\\s\\.]in|"
                    + "(inr|usd|eur|gbp|aud|cad)[\\s\\.]?\\d|"
                    + "rs\\.?[\\s]?\\d|"
                    + "\\u20b9[\\s]?\\d|"          // INR symbol
                    + "\\$[\\s]?\\d{2,}|"          // dollar amount (2+ digits to avoid $5)
                    + "\\u20ac[\\s]?\\d|"          // euro symbol
                    + "\\u00a3[\\s]?\\d)\\b",      // pound symbol
            Pattern.CASE_INSENSITIVE);

    // -- Updates --------------------------------------------------------------------------------
    // Shipping, deliveries, appointments, bookings.
    private static final Pattern UPDATE_PATTERN = Pattern.compile(
            "\\b(out[\\s-]?for[\\s-]?delivery|"
                    + "shipped|dispatched|in[\\s-]?transit|"
                    + "(has[\\s-]?been[\\s-]?)?delivered|"
                    + "tracking[\\s-]?(number|id|code)?|"
                    + "your[\\s-]?(order|package|parcel|shipment)|order[\\s-]?#|"
                    + "appointment|booking[\\s-]?(confirmed|cancelled|id)|"
                    + "scheduled[\\s-]?for|reminder[\\s:]|"
                    + "flight[\\s-]?(status|delayed|cancelled|on[\\s-]?time)|"
                    + "pnr|boarding[\\s-]?pass|"
                    + "arrived|expected[\\s-]?(delivery|arrival))\\b",
            Pattern.CASE_INSENSITIVE);

    // -- Promotions -----------------------------------------------------------------------------
    // Marketing copy: discounts, deals, contests, coupon codes.
    private static final Pattern PROMOTION_PATTERN = Pattern.compile(
            "(\\b\\d{1,3}\\s*%[\\s-]?off\\b|"        // 50% off
                    + "\\bup[\\s-]?to[\\s-]?\\d+\\s*%|"
                    + "\\b(sale|discount|offer|deal|exclusive|limited[\\s-]?time|"
                    + "buy[\\s-]?one[\\s-]?get|bogo|"
                    + "coupon[\\s-]?code|promo[\\s-]?code|"
                    + "flash[\\s-]?sale|mega[\\s-]?sale|"
                    + "save[\\s-]?big|hurry[\\s-]?up|"
                    + "won[\\s-]?(a[\\s-]?)?prize|congratulations[\\s,!]|"
                    + "cashback|loyalty[\\s-]?points|"
                    + "membership[\\s-]?renewal)\\b)",
            Pattern.CASE_INSENSITIVE);

    // -- Spam -----------------------------------------------------------------------------------
    // Obvious unsolicited / scam patterns. Kept conservative — false positives are worse than
    // false negatives here, since a Spam-misclassified bank OTP can cost the user real money.
    private static final Pattern SPAM_PATTERN = Pattern.compile(
            "\\b(click[\\s-]?here[\\s-]?(to|now)|"
                    + "reply[\\s-]?stop[\\s-]?to[\\s-]?(unsubscribe|opt[\\s-]?out)|"
                    + "you[\\s-]?have[\\s-]?(won|been[\\s-]?selected)[\\s-]?(an?[\\s-]?)?(iphone|ipad|car|trip|prize)|"
                    + "claim[\\s-]?(your[\\s-]?)?(prize|reward|bonus)[\\s-]?now|"
                    + "free[\\s-]?(iphone|ipad|gift[\\s-]?card)|"
                    + "urgent[\\s,!:]+[\\s\\S]{0,40}(suspended|compromised|verify))\\b",
            Pattern.CASE_INSENSITIVE);

    @NonNull
    @Override
    public MessageCategory classify(@Nullable final String senderDestination,
                                    @Nullable final String contactDisplayName,
                                    @NonNull final List<String> sampleMessageTexts,
                                    final boolean isGroup) {
        if (isGroup) {
            return MessageCategory.PERSONAL;
        }
        if (contactDisplayName != null && !contactDisplayName.trim().isEmpty()) {
            // The sender is in the user's contacts. Even if the latest message superficially
            // matches a promo regex, the relationship dominates.
            return MessageCategory.PERSONAL;
        }
        if (sampleMessageTexts.isEmpty()) {
            return MessageCategory.PERSONAL;
        }

        // Concatenate samples so a single classify() call sees the conversation's "feel" — a
        // single OTP texted to your friend's number shouldn't outweigh ten other personal lines.
        final StringBuilder combined = new StringBuilder();
        for (final String text : sampleMessageTexts) {
            if (text == null) {
                continue;
            }
            combined.append(text).append('\n');
        }
        final String haystack = combined.toString();
        if (haystack.isEmpty()) {
            return MessageCategory.PERSONAL;
        }

        if (SPAM_PATTERN.matcher(haystack).find()) {
            return MessageCategory.SPAM;
        }
        if (TRANSACTION_PATTERN.matcher(haystack).find()) {
            return MessageCategory.TRANSACTIONS;
        }
        if (UPDATE_PATTERN.matcher(haystack).find()) {
            return MessageCategory.UPDATES;
        }
        if (PROMOTION_PATTERN.matcher(haystack).find()) {
            return MessageCategory.PROMOTIONS;
        }
        return MessageCategory.PERSONAL;
    }
}
