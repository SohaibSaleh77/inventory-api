package com.ims.db;

import com.ims.common.exception.ConflictException;
import com.ims.common.exception.DataIntegrityException;
import com.ims.common.exception.InternalErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Translates JDBC SQLExceptions (SQLState based) into typed domain exceptions
 * so services and controllers never deal with raw SQL error codes.
 */
public final class SQLExceptionMapper {

    private static final Logger log = LoggerFactory.getLogger(SQLExceptionMapper.class);

    private SQLExceptionMapper() {
    }

    public static RuntimeException translate(String operation, SQLException e) {
        String sqlState = e.getSQLState() == null ? "" : e.getSQLState();
        String message = e.getMessage() == null ? "unknown SQL error" : e.getMessage();

        // 23000 = integrity constraint violation family
        if (sqlState.startsWith("23")) {
            if (sqlState.equals("23505") || message.contains("Duplicate entry")) {
                String duplicate = extractDuplicateKey(message);
                return new com.ims.common.exception.DuplicateResourceException(
                        duplicate != null ? duplicate : "Resource with the same unique key");
            }
            if (sqlState.equals("23503") || message.contains("foreign key constraint")) {
                String fk = extractForeignKey(message);
                return new DataIntegrityException(
                        "Operation " + operation + " violates a foreign key constraint"
                                + (fk != null ? ": " + fk : ""), e);
            }
            if (message.contains("Cannot delete or update a parent row")) {
                return new com.ims.common.exception.ResourceInUseException(
                        "Resource", "", "dependent records");
            }
            if (sqlState.equals("23514") || message.contains("Check constraint")) {
                return new DataIntegrityException(
                        "Operation " + operation + " violates a database CHECK constraint: "
                                + message, e);
            }
            if (e instanceof SQLIntegrityConstraintViolationException) {
                return new DataIntegrityException(
                        "Operation " + operation + " violates an integrity constraint: "
                                + message, e);
            }
            return new DataIntegrityException(
                    "Operation " + operation + " violates an integrity constraint: " + message, e);
        }

        // Custom trigger errors (append-only ledger protection) use SQLState 45000
        if (sqlState.equals("45000")) {
            return new ConflictException("Database rejected the operation: " + message);
        }

        // Deadlock / lock wait timeout: retryable by the caller
        if (sqlState.equals("40001")) {
            return new ConflictException(
                    "Deadlock detected while executing " + operation
                            + " - please retry the request");
        }
        if (sqlState.equals("HY000") && message.contains("Lock wait timeout")) {
            return new ConflictException(
                    "Lock wait timeout while executing " + operation
                            + " - concurrent operation in progress, please retry");
        }

        log.error("Untranslated SQLException during {}: sqlState={} errorCode={}",
                operation, sqlState, e.getErrorCode(), e);
        return new InternalErrorException("Database error while executing " + operation, e);
    }

    private static String extractDuplicateKey(String message) {
        // MySQL format: Duplicate entry 'X' for key 'uq_table_col'
        int entryIdx = message.indexOf("Duplicate entry '");
        if (entryIdx >= 0) {
            int start = entryIdx + "Duplicate entry '".length();
            int end = message.indexOf('\'', start);
            if (end > start) {
                return "value '" + message.substring(start, end) + "'";
            }
        }
        return null;
    }

    private static String extractForeignKey(String message) {
        int idx = message.indexOf("FOREIGN KEY (`");
        if (idx >= 0) {
            int start = message.indexOf("(`", idx) + 2;
            int end = message.indexOf("`", start);
            if (end > start) {
                return message.substring(start, end);
            }
        }
        return null;
    }
}
