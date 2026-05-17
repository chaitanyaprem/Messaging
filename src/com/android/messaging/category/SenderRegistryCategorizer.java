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

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Categorizes a conversation by looking up its sender ID in {@link SenderRegistry} and then
 * deciding a bucket from the Principal Entity name. Two layers:
 *
 * <ol>
 *   <li>A hand-curated map of household-name PEs whose business is unambiguous but whose legal
 *       name doesn't include a category keyword (e.g. Bundl Technologies = Swiggy = Updates).
 *   </li>
 *   <li>Word-boundary regex over the entity name in priority order: Transactions → Updates →
 *       Promotions. The regexes are conservative: a PE name without one of the listed nouns
 *       returns null, letting the caller fall through to body-text rules.
 *   </li>
 * </ol>
 *
 * <p>Returns null (not a default bucket) when nothing matches. Composition with the body-text
 * classifier is the caller's job.
 */
public final class SenderRegistryCategorizer {

    private SenderRegistryCategorizer() {
    }

    /**
     * Hand-curated mapping for well-known PEs whose name doesn't match the keyword rules below
     * (or whose name matches the wrong rule). Keys are normalized: upper-cased, single-spaced,
     * trailing legal suffixes preserved so a substring check is unambiguous.
     */
    private static final Map<String, MessageCategory> CURATED_PE_CATEGORIES = buildCuratedMap();

    private static Map<String, MessageCategory> buildCuratedMap() {
        final Map<String, MessageCategory> m = new HashMap<>();
        // Payments / wallets — the legal names rarely include "bank" or "payments".
        put(m, "PHONEPE PRIVATE LIMITED", MessageCategory.TRANSACTIONS);
        put(m, "PHONEPE", MessageCategory.TRANSACTIONS);
        put(m, "PAYTM PAYMENTS BANK LIMITED", MessageCategory.TRANSACTIONS);
        put(m, "ONE 97 COMMUNICATIONS LIMITED", MessageCategory.TRANSACTIONS); // Paytm parent
        put(m, "GOOGLE INDIA DIGITAL SERVICES PRIVATE LIMITED", MessageCategory.TRANSACTIONS);
        put(m, "RAZORPAY SOFTWARE PRIVATE LIMITED", MessageCategory.TRANSACTIONS);
        put(m, "MOBIKWIK SYSTEMS PRIVATE LIMITED", MessageCategory.TRANSACTIONS);
        put(m, "BHARATPE", MessageCategory.TRANSACTIONS);
        put(m, "RESILIENT INNOVATIONS PRIVATE LIMITED", MessageCategory.TRANSACTIONS); // BharatPe legal

        // Food / hyperlocal delivery — name doesn't hint at logistics.
        put(m, "BUNDL TECHNOLOGIES PRIVATE LIMITED", MessageCategory.UPDATES); // Swiggy
        put(m, "PARROT INFOSOFT PVT LTD", MessageCategory.UPDATES); // Zomato (older)
        put(m, "ZOMATO LIMITED", MessageCategory.UPDATES);
        put(m, "ZOMATO PRIVATE LIMITED", MessageCategory.UPDATES);
        put(m, "ANI TECHNOLOGIES PRIVATE LIMITED", MessageCategory.UPDATES); // Ola
        put(m, "UBER INDIA SYSTEMS PRIVATE LIMITED", MessageCategory.UPDATES);

        // Telecom — legal names use "BHARTI"/"RELIANCE"/etc., none in the keyword list.
        put(m, "BHARTI AIRTEL LIMITED", MessageCategory.UPDATES);
        put(m, "RELIANCE JIO INFOCOMM LIMITED", MessageCategory.UPDATES);
        put(m, "RELIANCE COMMUNICATIONS LIMITED", MessageCategory.UPDATES);
        put(m, "VODAFONE IDEA LIMITED", MessageCategory.UPDATES);
        put(m, "BHARAT SANCHAR NIGAM LIMITED", MessageCategory.UPDATES); // BSNL

        // Gas / utility — "HPGAS", "BPCL" etc.
        put(m, "HINDUSTAN PETROLEUM CORPORATION LIMITED", MessageCategory.UPDATES);
        put(m, "BHARAT PETROLEUM CORPORATION LIMITED", MessageCategory.UPDATES);
        put(m, "INDIAN OIL CORPORATION LIMITED", MessageCategory.UPDATES);

        // E-commerce — these legal names match the FASHION/RETAIL rules already, but pin them
        // explicitly so a future rule tweak can't accidentally re-bucket them.
        put(m, "AMAZON SELLER SERVICES PRIVATE LIMITED", MessageCategory.PROMOTIONS);
        put(m, "FLIPKART INTERNET PRIVATE LIMITED", MessageCategory.PROMOTIONS);
        put(m, "MEESHO PRIVATE LIMITED", MessageCategory.PROMOTIONS);

        // Travel.
        put(m, "INDIAN RAILWAY CATERING AND TOURISM CORPORATION LIMITED",
                MessageCategory.UPDATES); // IRCTC
        put(m, "MAKEMYTRIP INDIA PRIVATE LIMITED", MessageCategory.UPDATES);
        put(m, "IBIBO GROUP PRIVATE LIMITED", MessageCategory.UPDATES); // Goibibo

        return m;
    }

    private static void put(final Map<String, MessageCategory> m, final String pe,
            final MessageCategory cat) {
        m.put(pe.toUpperCase(), cat);
    }

    // -- Entity-name keyword rules (evaluated in this order) ----------------------------------

    private static final Pattern TRANSACTION_ENTITY_PATTERN = Pattern.compile(
            "\\b(BANK|BANKING|FINANCE|FINANCIAL|FINTECH|NBFC|"
                    + "SECURITIES|INSURANCE|INSURERS?|INVESTMENT|"
                    + "MUTUAL\\s*FUND|ASSET\\s*MANAGEMENT|CAPITAL|"
                    + "CREDIT|LENDING|LOAN|LOANS|"
                    + "BROKERAGE|EXCHANGE|"
                    + "PAYMENT|PAYMENTS|CARDS|FORTUNE\\s*(FUND|FINANCE))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_ENTITY_PATTERN = Pattern.compile(
            "\\b(LOGISTICS?|EXPRESS|COURIER|COURIERS?|DELIVERY|DELIVERIES|"
                    + "SHIPPING|CARGO|POSTAL|POST|PARCEL|"
                    + "HOSPITAL|HOSPITALS|MEDICAL|HEALTHCARE|HEALTH|CLINIC|CLINICS|"
                    + "DIAGNOSTICS?|LABORATORY|LABORATORIES|LABS?|PHARMACY|PHARMACEUTICALS?|"
                    + "AIRLINES?|AIRWAYS?|AVIATION|"
                    + "HOTELS?|RESORTS?|RAILWAYS?|TRAVEL|TRAVELS?|TOURISM|"
                    + "EDUCATION|SCHOOL|SCHOOLS|COLLEGE|COLLEGES|UNIVERSITY|UNIVERSITIES|"
                    + "ACADEMY|ACADEMIES|"
                    + "TELECOM|TELECOMMUNICATIONS?|BROADBAND|NETWORKS?|"
                    + "POWER|ELECTRICITY|GAS|WATER|MUNICIPAL|"
                    + "PETROLEUM|REFINERIES?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PROMOTION_ENTITY_PATTERN = Pattern.compile(
            "\\b(FASHION|APPAREL|APPARELS|DESIGNS?|DESIGNERS?|"
                    + "RETAIL|RETAILERS?|STORES?|MARTS?|COMMERCE|"
                    + "BEAUTY|COSMETICS?|JEWELLERY|JEWELLERS?|JEWELRY|"
                    + "BRANDS?|CONSUMER\\s*PRODUCTS?|CONSUMER\\s*GOODS?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * @return the inferred category for {@code senderDestination}, or null if the sender isn't
     *     in the registry or its entity name doesn't match any rule.
     */
    @Nullable
    public static MessageCategory tryClassify(@Nullable final String senderDestination) {
        final String entityName = SenderRegistry.lookup(senderDestination);
        if (entityName == null) {
            return null;
        }
        return categorizeEntity(entityName);
    }

    /** Visible for tests. */
    @Nullable
    static MessageCategory categorizeEntity(final String entityName) {
        if (entityName == null || entityName.isEmpty()) {
            return null;
        }
        final MessageCategory curated = CURATED_PE_CATEGORIES.get(entityName.toUpperCase());
        if (curated != null) {
            return curated;
        }
        if (TRANSACTION_ENTITY_PATTERN.matcher(entityName).find()) {
            return MessageCategory.TRANSACTIONS;
        }
        if (UPDATE_ENTITY_PATTERN.matcher(entityName).find()) {
            return MessageCategory.UPDATES;
        }
        if (PROMOTION_ENTITY_PATTERN.matcher(entityName).find()) {
            return MessageCategory.PROMOTIONS;
        }
        return null;
    }
}
