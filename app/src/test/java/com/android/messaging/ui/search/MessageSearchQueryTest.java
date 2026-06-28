package com.android.messaging.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pure-JVM tests for the FTS5 query builder. These cover input sanitization without standing up
 * a database; the SQL it emits is exercised end-to-end by {@link MessageSearchQueryE2eTest}.
 */
public class MessageSearchQueryTest {
    @Test
    public void nullInputReturnsNull() {
        assertNull(MessageSearchQuery.toFtsQuery(null));
    }

    @Test
    public void shortInputReturnsNull() {
        assertNull(MessageSearchQuery.toFtsQuery(""));
        assertNull(MessageSearchQuery.toFtsQuery(" "));
        assertNull(MessageSearchQuery.toFtsQuery("a"));
    }

    @Test
    public void singleTokenIsQuotedAndPrefixed() {
        assertEquals("\"kfc\"*", MessageSearchQuery.toFtsQuery("kfc"));
    }

    @Test
    public void multipleTokensImplicitlyAnd() {
        assertEquals("\"meet\"* \"kfc\"*", MessageSearchQuery.toFtsQuery("meet kfc"));
    }

    @Test
    public void interiorWhitespaceCollapses() {
        assertEquals("\"meet\"* \"kfc\"*",
                MessageSearchQuery.toFtsQuery("  meet   kfc  "));
    }

    @Test
    public void embeddedDoubleQuotesStripped() {
        // SMS search has no use for literal-quote phrases; quotes are dropped from the input.
        assertEquals("\"sayhi\"*", MessageSearchQuery.toFtsQuery("say\"hi\""));
    }

    @Test
    public void ftsControlCharactersStripped() {
        // Parentheses and column-filter colon must not let the user steer the query language.
        assertEquals("\"kfc\"*", MessageSearchQuery.toFtsQuery("(kfc)"));
        // ':' is stripped, so 'body:kfc' collapses to one quoted token 'bodykfc'.
        assertEquals("\"bodykfc\"*", MessageSearchQuery.toFtsQuery("body:kfc"));
    }

    @Test
    public void onlyWhitespaceTokensReturnNull() {
        assertNull(MessageSearchQuery.toFtsQuery("()"));
        assertNull(MessageSearchQuery.toFtsQuery("\"\""));
    }

    @Test
    public void unicodeIsPassedThrough() {
        // Tokenization happens server-side by FTS5; the builder just quotes the phrase.
        final String out = MessageSearchQuery.toFtsQuery("café");
        assertNotNull(out);
        assertEquals("\"café\"*", out);
    }
}
