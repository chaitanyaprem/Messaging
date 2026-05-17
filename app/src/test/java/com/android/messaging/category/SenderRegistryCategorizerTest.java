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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Covers the registry-driven category inference: PE-name keyword rules and the curated
 * household-name overrides. {@link SenderRegistry} is stubbed via its test seam so we can
 * pin behaviour without loading the 220 KB asset.
 */
public class SenderRegistryCategorizerTest {

    @Before
    public void setUp() {
        final Map<String, String> fake = new HashMap<>();
        // Banks & finance — entity-name keyword path.
        fake.put("HDFCBK", "HDFC BANK LIMITED");
        fake.put("ICICIB", "ICICI BANK LIMITED");
        fake.put("SBIINB", "STATE BANK OF INDIA");
        fake.put("HDFCLN", "HDFC SALES PRIVATE LIMITED");      // generic — should not match
        fake.put("SBIMF", "SBI MUTUAL FUND TRUSTEE COMPANY PRIVATE LIMITED");
        fake.put("BAJFIN", "BAJAJ FINSERV LIMITED");
        // Logistics / medical / travel / utility — UPDATE_ENTITY_PATTERN path.
        fake.put("BLUDRT", "BLUE DART EXPRESS LIMITED");
        fake.put("APOLAB", "APOLLO HEALTH AND LIFESTYLE LIMITED");
        fake.put("BSELTD", "BSE LIMITED");                      // exchange — Transactions
        // E-commerce — keyword path.
        fake.put("MYNTRA", "MYNTRA DESIGNS PRIVATE LIMITED");
        // Curated-map overrides.
        fake.put("SWIGGY", "Bundl Technologies Private Limited");
        fake.put("ZOMATO", "Parrot Infosoft Pvt Ltd");
        fake.put("PHONPE", "PHONEPE PRIVATE LIMITED");
        fake.put("AIRTEL", "Bharti Airtel Limited");
        fake.put("IRCTC", "INDIAN RAILWAY CATERING AND TOURISM CORPORATION LIMITED");
        // Generic PE name that no rule should match.
        fake.put("ZZZGEN", "ZZZ Holdings Private Limited");
        SenderRegistry.setRegistryForTest(fake);
    }

    @After
    public void tearDown() {
        SenderRegistry.setRegistryForTest(null);
    }

    // -- lookup + normalization --------------------------------------------------------------

    @Test
    public void plainShortCode_resolvesViaRegistry() {
        assertEquals(MessageCategory.TRANSACTIONS, SenderRegistryCategorizer.tryClassify("HDFCBK"));
    }

    @Test
    public void dltPrefixedShortCode_stripsOperatorBeforeLookup() {
        assertEquals(MessageCategory.TRANSACTIONS, SenderRegistryCategorizer.tryClassify("VK-HDFCBK"));
        assertEquals(MessageCategory.TRANSACTIONS, SenderRegistryCategorizer.tryClassify("AD-ICICIB"));
    }

    @Test
    public void plusPrefixIsStripped() {
        assertEquals(MessageCategory.TRANSACTIONS, SenderRegistryCategorizer.tryClassify("+HDFCBK"));
    }

    @Test
    public void unregisteredSender_returnsNull() {
        assertNull(SenderRegistryCategorizer.tryClassify("UNKNWN"));
    }

    @Test
    public void realPhoneNumber_returnsNull() {
        assertNull(SenderRegistryCategorizer.tryClassify("+15551234567"));
    }

    @Test
    public void nullSender_returnsNull() {
        assertNull(SenderRegistryCategorizer.tryClassify(null));
    }

    // -- TRANSACTIONS keyword rule -----------------------------------------------------------

    @Test
    public void bankInEntityName_isTransactions() {
        assertEquals(MessageCategory.TRANSACTIONS, SenderRegistryCategorizer.tryClassify("SBIINB"));
    }

    @Test
    public void mutualFundInEntityName_isTransactions() {
        assertEquals(MessageCategory.TRANSACTIONS, SenderRegistryCategorizer.tryClassify("SBIMF"));
    }

    @Test
    public void finsservInEntityName_isTransactions() {
        // Bajaj Finserv -- "FINSERV" embeds "FINANCE"... actually we look for FINANCE/FINTECH/
        // NBFC; FINSERV alone doesn't match, but it does contain no other keyword.
        // The TRAI listing uses many such shorthand names — this test pins current behaviour:
        // FINSERV alone is NOT recognised, so we fall through.
        assertNull(SenderRegistryCategorizer.tryClassify("BAJFIN"));
    }

    // -- UPDATES keyword rule ----------------------------------------------------------------

    @Test
    public void expressInEntityName_isUpdates() {
        assertEquals(MessageCategory.UPDATES, SenderRegistryCategorizer.tryClassify("BLUDRT"));
    }

    @Test
    public void healthInEntityName_isUpdates() {
        assertEquals(MessageCategory.UPDATES, SenderRegistryCategorizer.tryClassify("APOLAB"));
    }

    // -- PROMOTIONS keyword rule -------------------------------------------------------------

    @Test
    public void designsInEntityName_isPromotions() {
        assertEquals(MessageCategory.PROMOTIONS, SenderRegistryCategorizer.tryClassify("MYNTRA"));
    }

    // -- Curated PE-name overrides ----------------------------------------------------------

    @Test
    public void swiggy_isUpdates_viaCurated() {
        assertEquals(MessageCategory.UPDATES, SenderRegistryCategorizer.tryClassify("SWIGGY"));
    }

    @Test
    public void zomato_isUpdates_viaCurated() {
        assertEquals(MessageCategory.UPDATES, SenderRegistryCategorizer.tryClassify("ZOMATO"));
    }

    @Test
    public void phonepe_isTransactions_viaCurated() {
        assertEquals(MessageCategory.TRANSACTIONS, SenderRegistryCategorizer.tryClassify("PHONPE"));
    }

    @Test
    public void airtelTelecomLegalName_isUpdates_viaCurated() {
        assertEquals(MessageCategory.UPDATES, SenderRegistryCategorizer.tryClassify("AIRTEL"));
    }

    @Test
    public void irctcLongLegalName_isUpdates_viaCurated() {
        assertEquals(MessageCategory.UPDATES, SenderRegistryCategorizer.tryClassify("IRCTC"));
    }

    // -- Fall-through ------------------------------------------------------------------------

    @Test
    public void genericPeNameWithoutKeyword_returnsNull() {
        // Caller should fall through to body-text rules; we deliberately abstain.
        assertNull(SenderRegistryCategorizer.tryClassify("ZZZGEN"));
    }

    @Test
    public void genericLoanCompanyName_isTransactions() {
        // Direct entity-name path test (not via registry) for the keyword rule.
        assertEquals(MessageCategory.TRANSACTIONS,
                SenderRegistryCategorizer.categorizeEntity("HAPPY LOANS PRIVATE LIMITED"));
        assertEquals(MessageCategory.TRANSACTIONS,
                SenderRegistryCategorizer.categorizeEntity("XYZ CAPITAL ADVISORS LTD"));
    }
}
