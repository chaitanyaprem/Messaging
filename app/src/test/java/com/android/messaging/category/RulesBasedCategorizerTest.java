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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pattern-by-pattern coverage of {@link RulesBasedCategorizer}. Each test gives the classifier
 * a representative real-world SMS body and asserts the expected bucket.
 *
 * <p>If a heuristic is added that flips one of these examples, the test must move with it
 * deliberately — these strings are the contract.
 */
public class RulesBasedCategorizerTest {

    private final RulesBasedCategorizer classifier = new RulesBasedCategorizer();

    /**
     * Default helper assumes a real (international-format) phone number sender so the
     * short-code fallback doesn't kick in; tests that care about short-code behaviour use
     * {@link #classifyFromShortCode} below.
     */
    private MessageCategory classify(final String... bodies) {
        return classifier.classify(
                "+15551234567" /* senderDestination */,
                null /* contactDisplayName */,
                Arrays.asList(bodies),
                false /* isGroup */);
    }

    private MessageCategory classifyFromShortCode(final String senderDestination,
            final String... bodies) {
        return classifier.classify(
                senderDestination,
                null /* contactDisplayName */,
                Arrays.asList(bodies),
                false /* isGroup */);
    }

    // -- PERSONAL ------------------------------------------------------------------------------

    @Test
    public void emptyMessageList_defaultsToPersonal() {
        assertEquals(MessageCategory.PERSONAL,
                classifier.classify(null, null, Collections.emptyList(), false));
    }

    @Test
    public void groupConversation_alwaysPersonal() {
        assertEquals(MessageCategory.PERSONAL,
                classifier.classify("BNKINF", null,
                        Collections.singletonList("Your OTP is 123456"),
                        true /* isGroup */));
    }

    @Test
    public void contactSender_alwaysPersonal_evenIfContentLooksPromotional() {
        assertEquals(MessageCategory.PERSONAL,
                classifier.classify("+15551234567", "Alice Wonderland",
                        Collections.singletonList("Check this out — 50% off at the store!"),
                        false));
    }

    @Test
    public void plainConversationalText_isPersonal() {
        assertEquals(MessageCategory.PERSONAL, classify("Hey, are you free this weekend?"));
    }

    @Test
    public void shortGreeting_isPersonal() {
        assertEquals(MessageCategory.PERSONAL, classify("Happy birthday!"));
    }

    // -- TRANSACTIONS --------------------------------------------------------------------------

    @Test
    public void otpMessage_isTransaction() {
        assertEquals(MessageCategory.TRANSACTIONS,
                classify("Your OTP is 482915. Do not share with anyone."));
    }

    @Test
    public void verificationCodeMessage_isTransaction() {
        assertEquals(MessageCategory.TRANSACTIONS,
                classify("Verification code: 932184"));
    }

    @Test
    public void inrDebitMessage_isTransaction() {
        assertEquals(MessageCategory.TRANSACTIONS,
                classify("Rs 5000 debited from A/c XX1234 on 02-Apr. Avl bal Rs 12345."));
    }

    @Test
    public void usdChargeMessage_isTransaction() {
        assertEquals(MessageCategory.TRANSACTIONS,
                classify("Your card ending 4242 was charged $50.00 at Amazon."));
    }

    @Test
    public void inrSymbolCreditMessage_isTransaction() {
        assertEquals(MessageCategory.TRANSACTIONS,
                classify("₹ 1500 credited to your account."));
    }

    // -- UPDATES -------------------------------------------------------------------------------

    @Test
    public void outForDeliveryMessage_isUpdate() {
        assertEquals(MessageCategory.UPDATES,
                classify("Your package is out for delivery and will arrive today."));
    }

    @Test
    public void shippedMessage_isUpdate() {
        assertEquals(MessageCategory.UPDATES,
                classify("Your order #A-12345 has been shipped. Tracking number 1Z999."));
    }

    @Test
    public void appointmentReminderMessage_isUpdate() {
        assertEquals(MessageCategory.UPDATES,
                classify("Reminder: Your appointment is scheduled for tomorrow at 3pm."));
    }

    @Test
    public void flightStatusMessage_isUpdate() {
        assertEquals(MessageCategory.UPDATES,
                classify("Flight status: On time. Boarding pass attached."));
    }

    // -- PROMOTIONS ----------------------------------------------------------------------------

    @Test
    public void percentOffMessage_isPromotion() {
        assertEquals(MessageCategory.PROMOTIONS,
                classify("Flash sale: 50% off everything this weekend only!"));
    }

    @Test
    public void couponMessage_isPromotion() {
        assertEquals(MessageCategory.PROMOTIONS,
                classify("Use coupon code SAVE20 for an exclusive deal."));
    }

    @Test
    public void bogoMessage_isPromotion() {
        assertEquals(MessageCategory.PROMOTIONS,
                classify("Buy one get one free on all jeans, limited time offer."));
    }

    // -- SPAM ----------------------------------------------------------------------------------

    @Test
    public void clickHerePrizeMessage_isSpam() {
        assertEquals(MessageCategory.SPAM,
                classify("Congratulations! You have won an iPhone. Click here to claim."));
    }

    @Test
    public void replyStopMarketingMessage_isPromotionNotSpam() {
        // "Reply STOP to unsubscribe" is a regulatory boilerplate found in legitimate
        // marketing texts, not a reliable spam signal. The "great deals" cue makes this
        // a promotion.
        assertEquals(MessageCategory.PROMOTIONS,
                classify("Visit our site for great deals. Reply STOP to unsubscribe."));
    }

    @Test
    public void urgentAccountCompromisedMessage_isSpam() {
        assertEquals(MessageCategory.SPAM,
                classify("URGENT! Your account has been compromised, verify now."));
    }

    @Test
    public void hospitalReportLinkWithClickHere_isNotSpam() {
        // Regression: hospitals, banks and delivery services use "click here to view" all
        // the time. The phrase alone must not flip the conversation into Spam.
        assertEquals(MessageCategory.UPDATES,
                classifyFromShortCode("APOLAB",
                        "Dear customer, your test report is ready. Click here to view."));
    }

    @Test
    public void bankClickHereStatementLink_isNotSpam() {
        // A bank notification with "click here" plus a currency amount should land in
        // Transactions, not Spam.
        assertEquals(MessageCategory.TRANSACTIONS,
                classifyFromShortCode("HDFCBK",
                        "Statement: Rs 25000 debited. Click here to view details."));
    }

    // -- PRIORITY -------------------------------------------------------------------------------

    @Test
    public void spamSignalBeatsTransactionSignal() {
        // A scam impersonating a bank: the OTP-looking content shouldn't override the spam flag.
        assertEquals(MessageCategory.SPAM,
                classify("URGENT! Your account is suspended, verify with code 123456."));
    }

    @Test
    public void transactionBeatsPromotion_whenBothPresent() {
        // Bank promo: real bank message mentioning a discount. Money trumps marketing.
        assertEquals(MessageCategory.TRANSACTIONS,
                classify("Rs 100 cashback credited on your card. 10% off on next purchase."));
    }

    @Test
    public void multipleSamplesAreConcatenated() {
        // No single sample matches, but the combined text does.
        assertEquals(MessageCategory.TRANSACTIONS,
                classify("Thanks for shopping with us.",
                        "Your OTP is 999000 — do not share."));
    }

    // -- SHORT-CODE FALLBACK --------------------------------------------------------------------
    // Senders that aren't real phone numbers shouldn't land in Personal just because their
    // recent messages happen not to match a keyword pattern.

    @Test
    public void plainTextFromAlphaShortCode_fallsBackToUpdates() {
        assertEquals(MessageCategory.UPDATES,
                classifyFromShortCode("SBIMF",
                        "NAV of Equity Fund as on date is 250.34"));
    }

    @Test
    public void plainTextFromPrefixedShortCode_fallsBackToUpdates() {
        // Indian DLT-format senders that arrive with a 2-letter operator prefix.
        assertEquals(MessageCategory.UPDATES,
                classifyFromShortCode("VK-HPGAS", "Dear customer, your booking is being processed."));
    }

    @Test
    public void plainTextFromAllCapsShortCode_fallsBackToUpdates() {
        assertEquals(MessageCategory.UPDATES,
                classifyFromShortCode("APOLAB",
                        "Dear customer, your report is ready for collection."));
    }

    @Test
    public void plainTextFromNumericShortCode_fallsBackToUpdates() {
        // 5-digit US-style short codes are common for transactional notifications.
        assertEquals(MessageCategory.UPDATES,
                classifyFromShortCode("32665", "Reply Y to confirm"));
    }

    @Test
    public void shortCodeWithTransactionContent_stillTransactions() {
        // Keyword rules win over the fallback so a clearly transactional short-code message
        // doesn't slip into Updates.
        assertEquals(MessageCategory.TRANSACTIONS,
                classifyFromShortCode("SBIMF", "Dividend Rs 500 credited to your folio."));
    }

    @Test
    public void realPhoneNumberWithNeutralText_staysPersonal() {
        // A full international-format number isn't a short code; without keyword hits it
        // remains in the Personal bucket.
        assertEquals(MessageCategory.PERSONAL,
                classifyFromShortCode("+447700900000", "Heading home, see you soon."));
    }
}
