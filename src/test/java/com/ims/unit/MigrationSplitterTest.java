package com.ims.unit;

import com.ims.db.DatabaseMigrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statement splitter: comments, strings, semicolons. */
class MigrationSplitterTest {

    @Test
    void splitsOnSemicolons() {
        List<String> statements = DatabaseMigrator.splitStatements("""
                CREATE TABLE a (id INT);
                CREATE TABLE b (id INT);
                """);
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE TABLE a"));
    }

    @Test
    void ignoresSemicolonsInsideStrings() {
        List<String> statements = DatabaseMigrator.splitStatements(
                "INSERT INTO t VALUES ('a;b;c');\nINSERT INTO t VALUES ('x')");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("'a;b;c'"));
    }

    @Test
    void stripsLineComments() {
        List<String> statements = DatabaseMigrator.splitStatements("""
                -- a comment; with a semicolon
                SELECT 1;
                """);
        assertEquals(1, statements.size());
        assertEquals("SELECT 1", statements.get(0));
    }

    @Test
    void stripsBlockComments() {
        List<String> statements = DatabaseMigrator.splitStatements("""
                SELECT /* block; comment */ 1;
                """);
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).contains("SELECT"));
        assertTrue(statements.get(0).contains("1"));
        assertTrue(!statements.get(0).contains("/*"));
    }

    @Test
    void handlesTriggerBodiesWithSingleStatements() {
        String sql = """
                CREATE TRIGGER trg BEFORE UPDATE ON t FOR EACH ROW
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'no updates; allowed';
                """;
        List<String> statements = DatabaseMigrator.splitStatements(sql);
        assertEquals(1, statements.size(),
                "the quoted semicolon must not split the trigger");
    }

    @Test
    void trailingStatementWithoutSemicolonIsKept() {
        List<String> statements = DatabaseMigrator.splitStatements("SELECT 1");
        assertEquals(1, statements.size());
    }
}
