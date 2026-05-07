package com.android.messaging.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SearchHighlightTest {
    private static final String S = String.valueOf(MessageSearchQuery.SNIPPET_START);
    private static final String E = String.valueOf(MessageSearchQuery.SNIPPET_END);
    private static final int HIGHLIGHT = Color.argb(0x80, 0x68, 0x9F, 0x38);

    @Test
    public void plainTextWithoutMarkersPassesThrough() {
        CharSequence out = SearchHighlight.toSpannable("hello world", HIGHLIGHT);
        assertEquals("hello world", out.toString());
    }

    @Test
    public void markersStripped() {
        // 'meet at <KFC> tomorrow'
        String snip = "meet at " + S + "KFC" + E + " tomorrow";
        CharSequence out = SearchHighlight.toSpannable(snip, HIGHLIGHT);
        assertEquals("meet at KFC tomorrow", out.toString());
    }

    @Test
    public void backgroundSpanOnMatchedRegion() {
        String snip = "meet at " + S + "KFC" + E + " tomorrow";
        SpannableString out = new SpannableString(SearchHighlight.toSpannable(snip, HIGHLIGHT));
        BackgroundColorSpan[] spans =
                out.getSpans(0, out.length(), BackgroundColorSpan.class);
        assertEquals(1, spans.length);
        int start = out.getSpanStart(spans[0]);
        int end = out.getSpanEnd(spans[0]);
        assertEquals("meet at ".length(), start);
        assertEquals("meet at KFC".length(), end);
        assertEquals(HIGHLIGHT, spans[0].getBackgroundColor());
    }

    @Test
    public void boldSpanOnMatchedRegion() {
        String snip = S + "lunch" + E + " plans";
        SpannableString out = new SpannableString(SearchHighlight.toSpannable(snip, HIGHLIGHT));
        StyleSpan[] styleSpans = out.getSpans(0, out.length(), StyleSpan.class);
        assertEquals(1, styleSpans.length);
        assertEquals(android.graphics.Typeface.BOLD, styleSpans[0].getStyle());
    }

    @Test
    public void multipleMatchesEachGetSpan() {
        String snip = S + "KFC" + E + " and " + S + "KFC" + E + " again";
        SpannableString out = new SpannableString(SearchHighlight.toSpannable(snip, HIGHLIGHT));
        BackgroundColorSpan[] spans =
                out.getSpans(0, out.length(), BackgroundColorSpan.class);
        assertEquals(2, spans.length);
        assertEquals("KFC and KFC again", out.toString());
    }

    @Test
    public void emptyMatchedRegionGetsNoSpan() {
        // No real text between markers; should not crash and should not add a span.
        String snip = "abc" + S + E + "def";
        SpannableString out = new SpannableString(SearchHighlight.toSpannable(snip, HIGHLIGHT));
        assertEquals("abcdef", out.toString());
        BackgroundColorSpan[] spans =
                out.getSpans(0, out.length(), BackgroundColorSpan.class);
        assertEquals(0, spans.length);
    }

    @Test
    public void unterminatedMarkerDoesNotCrash() {
        // Defensive: bad input shouldn't throw.
        String snip = "meet at " + S + "KFC tomorrow";
        CharSequence out = SearchHighlight.toSpannable(snip, HIGHLIGHT);
        // The trailing unterminated marker is dropped, remainder kept verbatim.
        assertTrue(out.toString().contains("KFC tomorrow"));
    }

    @Test
    public void nullAndEmptyInputsReturnEmpty() {
        assertEquals("", SearchHighlight.toSpannable(null, HIGHLIGHT).toString());
        assertEquals("", SearchHighlight.toSpannable("", HIGHLIGHT).toString());
    }

    @Test
    public void spanUsesExclusiveExclusiveFlag() {
        String snip = S + "x" + E;
        SpannableString out = new SpannableString(SearchHighlight.toSpannable(snip, HIGHLIGHT));
        BackgroundColorSpan[] spans =
                out.getSpans(0, out.length(), BackgroundColorSpan.class);
        assertEquals(1, spans.length);
        assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, out.getSpanFlags(spans[0]));
    }
}
