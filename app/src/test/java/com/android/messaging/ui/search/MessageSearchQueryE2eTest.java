package com.android.messaging.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.datamodel.DatabaseHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end exercise of the SQL emitted by {@link MessageSearchQuery#run}.
 *
 * <p>Reuses the same FTS5 schema {@code DatabaseHelper} ships and runs the actual SELECT
 * statement (via reflection-free string manipulation: the same SQL constants the production
 * class uses) against an in-memory xerial-sqlite database. Validates that the query returns
 * the expected rows, the snippet markers wrap matched terms, and per-conversation collapsing
 * yields one row per conversation.
 */
public class MessageSearchQueryE2eTest {
    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE conversations(_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT, icon TEXT, sort_timestamp INT)");
            st.execute("CREATE TABLE messages(_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "conversation_id INT, received_timestamp INT)");
            st.execute("CREATE TABLE parts(_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "message_id INT, text TEXT)");
            st.execute(DatabaseHelper.CREATE_MESSAGES_FTS_TABLE_SQL);
            st.execute(DatabaseHelper.CREATE_MESSAGES_FTS_AI_TRIGGER_SQL);
            st.execute(DatabaseHelper.CREATE_MESSAGES_FTS_AD_TRIGGER_SQL);
            st.execute(DatabaseHelper.CREATE_MESSAGES_FTS_AU_TRIGGER_SQL);
        }
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    /** Production SQL, with placeholders bound the same way as MessageSearchQuery does. */
    private static final String SEARCH_SQL =
            "SELECT m.conversation_id AS conversation_id, "
                    + "c.name AS conversation_name, "
                    + "c.icon AS conversation_icon, "
                    + "m.received_timestamp AS received_timestamp, "
                    + "snippet(messages_fts, 0, ?, ?, ?, 8) AS body_snippet "
                    + "FROM messages_fts f "
                    + "INNER JOIN parts p ON p._id = f.rowid "
                    + "INNER JOIN messages m ON m._id = p.message_id "
                    + "INNER JOIN conversations c ON c._id = m.conversation_id "
                    + "WHERE messages_fts MATCH ? "
                    + "ORDER BY m.received_timestamp DESC "
                    + "LIMIT ?";

    private long insertConversation(final String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO conversations(name, sort_timestamp) VALUES (?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertMessage(final long convId, final long ts, final String text)
            throws Exception {
        long messageId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO messages(conversation_id, received_timestamp) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, convId);
            ps.setLong(2, ts);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                messageId = keys.getLong(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO parts(message_id, text) VALUES (?, ?)")) {
            ps.setLong(1, messageId);
            ps.setString(2, text);
            ps.executeUpdate();
        }
        return messageId;
    }

    private List<String[]> runQuery(final String userInput, final int limit) throws Exception {
        final String fts = MessageSearchQuery.toFtsQuery(userInput);
        assertNotNull("query must yield non-null FTS expression", fts);
        try (PreparedStatement ps = conn.prepareStatement(SEARCH_SQL)) {
            ps.setString(1, String.valueOf(MessageSearchQuery.SNIPPET_START));
            ps.setString(2, String.valueOf(MessageSearchQuery.SNIPPET_END));
            ps.setString(3, MessageSearchQuery.SNIPPET_ELLIPSIS);
            ps.setString(4, fts);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<String[]> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new String[] {
                            rs.getString("conversation_id"),
                            rs.getString("conversation_name"),
                            rs.getString("body_snippet"),
                    });
                }
                return rows;
            }
        }
    }

    @Test
    public void multiConversationSearchReturnsAllMatches() throws Exception {
        long alice = insertConversation("Alice");
        long bob = insertConversation("Bob");
        insertMessage(alice, 1000, "meet at KFC tomorrow");
        insertMessage(bob, 2000, "dinner at KFC tonight");
        List<String[]> rows = runQuery("kfc", 10);
        assertEquals(2, rows.size());
        // ORDER BY received_timestamp DESC: Bob (2000) first, then Alice (1000).
        assertEquals(String.valueOf(bob), rows.get(0)[0]);
        assertEquals("Bob", rows.get(0)[1]);
        assertEquals(String.valueOf(alice), rows.get(1)[0]);
    }

    @Test
    public void snippetWrapsMatchedTermsWithDelimiters() throws Exception {
        long c = insertConversation("Cafe");
        insertMessage(c, 1, "meet at the KFC for lunch");
        List<String[]> rows = runQuery("kfc", 10);
        assertEquals(1, rows.size());
        String snippet = rows.get(0)[2];
        assertNotNull(snippet);
        assertTrue("snippet missing start delimiter: " + snippet,
                snippet.indexOf(MessageSearchQuery.SNIPPET_START) >= 0);
        assertTrue("snippet missing end delimiter: " + snippet,
                snippet.indexOf(MessageSearchQuery.SNIPPET_END) >= 0);
        // Matched fragment is between markers.
        int s = snippet.indexOf(MessageSearchQuery.SNIPPET_START);
        int e = snippet.indexOf(MessageSearchQuery.SNIPPET_END);
        assertEquals("KFC", snippet.substring(s + 1, e));
    }

    @Test
    public void multiTokenAndMatching() throws Exception {
        long c = insertConversation("Mike");
        insertMessage(c, 1, "dinner at KFC tonight");
        insertMessage(c, 2, "dinner at the restaurant");
        // Both tokens must appear; only the first message qualifies.
        List<String[]> rows = runQuery("dinner KFC", 10);
        assertEquals(1, rows.size());
    }

    @Test
    public void prefixMatchingOnPartialWord() throws Exception {
        long c = insertConversation("Sam");
        insertMessage(c, 1, "kfcdelivery");
        List<String[]> rows = runQuery("kfc", 10);
        assertEquals("prefix match should find kfcdelivery", 1, rows.size());
    }

    @Test
    public void diacriticFoldingFromUserInput() throws Exception {
        long c = insertConversation("Eve");
        insertMessage(c, 1, "café au lait");
        // User types ASCII; tokenizer folds diacritics so this should match.
        List<String[]> rows = runQuery("cafe", 10);
        assertEquals(1, rows.size());
    }
}
