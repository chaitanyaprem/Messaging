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

import android.content.Context;

import androidx.annotation.Nullable;

import com.android.messaging.Factory;
import com.android.messaging.util.LogUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * In-memory lookup for India's TRAI-published DLT sender-header registry. Maps a 6-character
 * commercial SMS sender ID (e.g. {@code HDFCBK}) to its registered Principal Entity name
 * (e.g. {@code "HDFC BANK LIMITED"}).
 *
 * <p>The source data is the {@code List_SMS_Headers_*.xlsx} published at
 * <a href="http://www.trai.gov.in/node/7411">trai.gov.in/node/7411</a>; we ship it as a gzipped
 * TSV asset and decompress lazily on first lookup so app startup pays nothing for users who
 * never receive a commercial SMS.
 *
 * <p>Lookups are normalized: the leading {@code +} of an E.164 number is stripped, and a
 * 2-letter operator prefix like {@code VK-} (Vodafone-Karnataka), {@code AD-}, {@code TM-}
 * etc. is removed before consulting the registry. Senders that are real phone numbers or
 * unregistered short codes return null.
 */
public final class SenderRegistry {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String ASSET_NAME = "sender_registry.tsv.gz";

    @Nullable
    private static volatile Map<String, String> sHeaderToEntity;

    private SenderRegistry() {
    }

    /**
     * @return the registered Principal Entity name for {@code senderDestination}, or null if
     *     not in the registry. Safe to call from any thread.
     */
    @Nullable
    public static String lookup(@Nullable final String senderDestination) {
        if (senderDestination == null) {
            return null;
        }
        final String key = normalize(senderDestination);
        if (key == null) {
            return null;
        }
        return load().get(key);
    }

    /** Visible for tests that want to inject a smaller map. */
    static void setRegistryForTest(@Nullable final Map<String, String> map) {
        sHeaderToEntity = map;
    }

    @Nullable
    static String normalize(final String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.charAt(0) == '+') {
            s = s.substring(1);
        }
        // Strip "XX-" operator prefixes common on Indian DLT senders.
        if (s.length() > 3 && s.charAt(2) == '-'
                && Character.isLetter(s.charAt(0))
                && Character.isLetter(s.charAt(1))) {
            s = s.substring(3);
        }
        if (s.isEmpty()) {
            return null;
        }
        return s.toUpperCase();
    }

    private static Map<String, String> load() {
        Map<String, String> cached = sHeaderToEntity;
        if (cached != null) {
            return cached;
        }
        synchronized (SenderRegistry.class) {
            cached = sHeaderToEntity;
            if (cached != null) {
                return cached;
            }
            cached = readFromAsset();
            sHeaderToEntity = cached;
            return cached;
        }
    }

    private static Map<String, String> readFromAsset() {
        final Context context = Factory.get().getApplicationContext();
        final Map<String, String> out = new HashMap<>(24000);
        try (InputStream raw = context.getAssets().open(ASSET_NAME);
                GZIPInputStream gz = new GZIPInputStream(raw);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(gz, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                out.put(line.substring(0, tab), line.substring(tab + 1));
            }
            LogUtil.i(TAG, "SenderRegistry loaded " + out.size() + " entries");
            return out;
        } catch (final IOException ex) {
            LogUtil.e(TAG, "SenderRegistry failed to load asset " + ASSET_NAME, ex);
            return Collections.emptyMap();
        }
    }
}
