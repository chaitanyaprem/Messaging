package com.android.messaging.datamodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
 * Verifies the FTS5 schema and trigger behaviour added in db v3.
 *
 * <p>Drives the SQL constants from {@link DatabaseHelper} against an in-memory SQLite engine
 * (xerial sqlite-jdbc) rather than through Android's SQLiteDatabase. The Robolectric runtime
 * shipped at the time of writing was built without FTS5 (no such module: fts5), so we test the
 * SQL strings against a real FTS5-capable engine. The behavior of unicode61 + remove_diacritics,
 * the BM25 ranking function, and external-content triggers is identical between this engine
 * and the SQLite that ships on the minSdk=35 target devices.
 */
public class DatabaseHelperFtsTest {
    private static final String CREATE_PARTS_MIN_SQL =
            "CREATE TABLE parts(_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "message_id INT, text TEXT)";

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute(CREATE_PARTS_MIN_SQL);
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

    @Test
    public void ftsTableExistsInSchema() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, DatabaseHelper.MESSAGES_FTS_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue("messages_fts virtual table missing", rs.next());
            }
        }
    }

    @Test
    public void insertIntoPartsPopulatesFtsIndex() throws Exception {
        long id = insertPart("meet me at KFC at 5pm");
        assertEquals(1, ftsRowCount("kfc"));
        assertEquals(id, firstFtsRowId("kfc"));
    }

    @Test
    public void updateOnPartsReindexesFts() throws Exception {
        long id = insertPart("pizza tomorrow at noon");
        assertEquals(0, ftsRowCount("kfc"));
        try (PreparedStatement ps = conn.prepareStatement("UPDATE parts SET text=? WHERE _id=?")) {
            ps.setString(1, "breakfast at KFC");
            ps.setLong(2, id);
            ps.executeUpdate();
        }
        assertEquals(1, ftsRowCount("kfc"));
        assertEquals(0, ftsRowCount("pizza"));
    }

    @Test
    public void deleteOnPartsRemovesFromFts() throws Exception {
        long id = insertPart("KFC at 5pm");
        assertEquals(1, ftsRowCount("kfc"));
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM parts WHERE _id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        assertEquals(0, ftsRowCount("kfc"));
    }

    @Test
    public void diacriticsFoldedDuringMatch() throws Exception {
        insertPart("café au lait");
        // unicode61 with remove_diacritics=2: query 'cafe' must match 'café'.
        assertEquals(1, ftsRowCount("cafe"));
    }

    @Test
    public void nullTextRowDoesNotBreakIndex() throws Exception {
        // Image / video parts have NULL text; these must be inserted without breaking FTS.
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO parts(message_id, text) VALUES (?, NULL)")) {
            ps.setLong(1, 1);
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(0, ftsRowCount("anything"));
    }

    @Test
    public void rebuildCommandBackfillsFromParts() throws Exception {
        // Simulate the upgrade path: rows already exist, then the FTS index is reset & rebuilt.
        long a = insertPart("KFC at 5pm");
        long b = insertPart("café tomorrow");
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM " + DatabaseHelper.MESSAGES_FTS_TABLE);
        }
        assertEquals(0, ftsRowCount("kfc"));
        assertEquals(0, ftsRowCount("cafe"));

        try (Statement st = conn.createStatement()) {
            st.execute(DatabaseHelper.REBUILD_MESSAGES_FTS_SQL);
        }

        assertEquals(1, ftsRowCount("kfc"));
        assertEquals(a, firstFtsRowId("kfc"));
        assertEquals(1, ftsRowCount("cafe"));
        assertEquals(b, firstFtsRowId("cafe"));
    }

    @Test
    public void snippetReturnsHighlightedExcerpt() throws Exception {
        insertPart("meet me at KFC at 5pm");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT snippet(" + DatabaseHelper.MESSAGES_FTS_TABLE
                        + ", 0, '<<', '>>', '…', 8) FROM "
                        + DatabaseHelper.MESSAGES_FTS_TABLE
                        + " WHERE " + DatabaseHelper.MESSAGES_FTS_TABLE + " MATCH ?")) {
            ps.setString(1, "kfc");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String snip = rs.getString(1);
                assertNotNull(snip);
                assertTrue("expected delimiters in: " + snip, snip.contains("<<KFC>>"));
            }
        }
    }

    @Test
    public void bm25RankingOrdersResults() throws Exception {
        long shorter = insertPart("KFC");
        long longer = insertPart("KFC was the place where we used to meet on Fridays "
                + "after work and grab a quick bite before heading home");
        // bm25 in SQLite returns negative scores; smaller (more negative) is better.
        // The shorter document should rank first because the matched term is
        // proportionally a larger fraction of the body.
        List<Long> order = ftsMatchOrderedByBm25("kfc");
        assertEquals(2, order.size());
        assertEquals(shorter, (long) order.get(0));
        assertEquals(longer, (long) order.get(1));
    }

    @Test
    public void multiTokenMatchAllRequired() throws Exception {
        insertPart("dinner at KFC tonight");
        insertPart("dinner at the restaurant");
        // FTS5 default operator is implicit AND across tokens.
        assertEquals(1, ftsRowCount("dinner KFC"));
    }

    private long insertPart(String text) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO parts(message_id, text) VALUES (1, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, text);
            assertEquals(1, ps.executeUpdate());
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private int ftsRowCount(String matchQuery) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + DatabaseHelper.MESSAGES_FTS_TABLE
                        + " WHERE " + DatabaseHelper.MESSAGES_FTS_TABLE + " MATCH ?")) {
            ps.setString(1, matchQuery);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private long firstFtsRowId(String matchQuery) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT rowid FROM " + DatabaseHelper.MESSAGES_FTS_TABLE
                        + " WHERE " + DatabaseHelper.MESSAGES_FTS_TABLE + " MATCH ?"
                        + " LIMIT 1")) {
            ps.setString(1, matchQuery);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    fail("no FTS row matched: " + matchQuery);
                }
                return rs.getLong(1);
            }
        }
    }

    private List<Long> ftsMatchOrderedByBm25(String matchQuery) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT rowid FROM " + DatabaseHelper.MESSAGES_FTS_TABLE
                        + " WHERE " + DatabaseHelper.MESSAGES_FTS_TABLE + " MATCH ?"
                        + " ORDER BY bm25(" + DatabaseHelper.MESSAGES_FTS_TABLE + ")")) {
            ps.setString(1, matchQuery);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }
}
