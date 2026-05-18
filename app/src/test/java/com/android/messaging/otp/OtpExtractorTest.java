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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Examples pulled from real-world OTP/2FA messages. If a regex tweak changes the verdict on
 * any of these, the test must move with it deliberately.
 */
public class OtpExtractorTest {

    @Test
    public void plainOtpMessage_isExtracted() {
        assertEquals("482915", OtpExtractor.extract("Your OTP is 482915. Do not share."));
    }

    @Test
    public void otpWithColon_isExtracted() {
        assertEquals("932184", OtpExtractor.extract("OTP: 932184"));
    }

    @Test
    public void verificationCodeMessage_isExtracted() {
        assertEquals("123456",
                OtpExtractor.extract("Verification code: 123456 - valid for 5 minutes."));
    }

    @Test
    public void codeFollowedByTrigger_isExtracted() {
        assertEquals("482915",
                OtpExtractor.extract("482915 is your verification code for ExampleApp."));
    }

    @Test
    public void webotpStyleMessage_isExtracted() {
        // Android's autofill-friendly OTP convention starts with "<#>".
        assertEquals("482915",
                OtpExtractor.extract("<#> 482915 is your ExampleApp code\nQX0NfA4TwAv"));
    }

    @Test
    public void useThisCodePattern_isExtracted() {
        assertEquals("99000",
                OtpExtractor.extract("Use 99000 as your one-time code to sign in."));
    }

    @Test
    public void bankOtpMessage_isExtracted() {
        assertEquals("847362",
                OtpExtractor.extract(
                        "HDFCBK: OTP for txn of Rs 5000 is 847362. Valid for 15 min."));
    }

    @Test
    public void upiPinMessage_isExtracted() {
        assertEquals("482915",
                OtpExtractor.extract("PIN 482915 has been generated for your UPI request."));
    }

    @Test
    public void eightDigitCode_isExtracted() {
        assertEquals("48291503",
                OtpExtractor.extract("Your verification code is 48291503"));
    }

    @Test
    public void fourDigitCode_isExtracted() {
        assertEquals("4829", OtpExtractor.extract("Your OTP is 4829"));
    }

    @Test
    public void emptyMessage_returnsNull() {
        assertNull(OtpExtractor.extract(""));
    }

    @Test
    public void nullMessage_returnsNull() {
        assertNull(OtpExtractor.extract(null));
    }

    @Test
    public void purelyConversationalMessage_returnsNull() {
        assertNull(OtpExtractor.extract("Hey, are you free this weekend?"));
    }

    @Test
    public void priceWithoutContext_returnsNull() {
        // No OTP trigger phrase nearby — the 5000 is a price, not a code.
        assertNull(OtpExtractor.extract("Get this offer for just 5000!"));
    }

    @Test
    public void yearLooking4DigitNumber_isNotExtracted() {
        // "2024" in a non-OTP context is most likely a year, not an OTP. We need at least a
        // weak trigger phrase. Without one, return null. (With trigger, year-shape filter
        // still rejects 4-digit years.)
        assertNull(OtpExtractor.extract("Member since 2024, your account is in good standing."));
    }

    @Test
    public void yearShape4DigitWithOtpContext_skipsTheYearAndFindsRealCode() {
        // First number looks like a year and falls back; second 6-digit one is the real OTP.
        assertEquals("847362",
                OtpExtractor.extract("Code generated on 2024. OTP is 847362."));
    }

    @Test
    public void multipleCodesPicksFirstWithTrigger() {
        // The second number is a transaction reference, not the OTP. We pick the one with
        // OTP context, which appears first.
        assertEquals("123456",
                OtpExtractor.extract("Your OTP is 123456. Ref no 9876543210 for support."));
    }

    @Test
    public void threeDigitNumberAlone_isNotExtracted() {
        assertNull(OtpExtractor.extract("Your OTP is 123."));
    }

    @Test
    public void nineDigitNumberAlone_isNotExtracted() {
        assertNull(OtpExtractor.extract("Your code is 123456789"));
    }

    @Test
    public void deliveryMessageWithDigits_returnsNull() {
        assertNull(OtpExtractor.extract(
                "Your package 12345 is out for delivery and will arrive by 6pm."));
    }
}
