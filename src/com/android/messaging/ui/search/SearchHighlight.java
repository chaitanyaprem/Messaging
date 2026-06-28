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

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;

/**
 * Builds a highlighted {@link Spanned} from an FTS5 snippet that uses
 * {@link MessageSearchQuery#SNIPPET_START} / {@link MessageSearchQuery#SNIPPET_END}
 * as match delimiters.
 *
 * <p>Walks the snippet linearly and applies a {@link BackgroundColorSpan} plus bold
 * {@link StyleSpan} to each delimited region. The control-character delimiters never appear in
 * real SMS body content, so this is safe without HTML parsing or escaping.
 */
public final class SearchHighlight {
    private SearchHighlight() {
    }

    public static CharSequence toSpannable(final String snippet, final int highlightColor) {
        if (snippet == null || snippet.isEmpty()) {
            return "";
        }
        final SpannableStringBuilder out = new SpannableStringBuilder();
        final int n = snippet.length();
        int i = 0;
        while (i < n) {
            final int start = snippet.indexOf(MessageSearchQuery.SNIPPET_START, i);
            if (start < 0) {
                out.append(snippet, i, n);
                break;
            }
            // Plain prefix.
            if (start > i) {
                out.append(snippet, i, start);
            }
            final int end = snippet.indexOf(MessageSearchQuery.SNIPPET_END, start + 1);
            if (end < 0) {
                // Unterminated marker: drop the marker, append remainder verbatim.
                out.append(snippet, start + 1, n);
                break;
            }
            final int spanStart = out.length();
            out.append(snippet, start + 1, end);
            final int spanEnd = out.length();
            if (spanEnd > spanStart) {
                out.setSpan(new BackgroundColorSpan(highlightColor),
                        spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD),
                        spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = end + 1;
        }
        return out;
    }
}
