package com.ims.unit;

import com.ims.db.SQLExceptionMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLState to domain-exception translation. */
class SQLExceptionMapperTest {

    @Test
    void duplicateKeyMapsToDuplicateResource() {
        SQLException e = new SQLException(
                "Duplicate entry 'WH-MAIN' for key 'uq_warehouses_code'", "23000", 1062);
        RuntimeException mapped = SQLExceptionMapper.translate("insert", e);
        assertInstanceOf(com.ims.common.exception.DuplicateResourceException.class, mapped);
        assertTrue(mapped.getMessage().contains("WH-MAIN"));
    }

    @Test
    void foreignKeyMapsToDataIntegrity() {
        SQLException e = new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`fk_po_lines_product`)", "23000", 1452);
        RuntimeException mapped = SQLExceptionMapper.translate("insert", e);
        assertInstanceOf(com.ims.common.exception.DataIntegrityException.class, mapped);
    }

    @Test
    void checkConstraintMapsToDataIntegrity() {
        SQLException e = new SQLException(
                "Check constraint 'chk_stock_on_hand' is violated.", "23514", 3819);
        RuntimeException mapped = SQLExceptionMapper.translate("update", e);
        assertInstanceOf(com.ims.common.exception.DataIntegrityException.class, mapped);
    }

    @Test
    void customSqlState45000MapsToConflict() {
        SQLException e = new SQLException(
                "stock_movements is an append-only ledger: UPDATE is forbidden",
                "45000", 1644);
        RuntimeException mapped = SQLExceptionMapper.translate("update", e);
        assertInstanceOf(com.ims.common.exception.ConflictException.class, mapped);
        assertTrue(mapped.getMessage().contains("append-only"));
    }

    @Test
    void deadlockMapsToRetryableConflict() {
        SQLException e = new SQLException("Deadlock found when trying to get lock",
                "40001", 1213);
        RuntimeException mapped = SQLExceptionMapper.translate("tx", e);
        assertInstanceOf(com.ims.common.exception.ConflictException.class, mapped);
        assertTrue(mapped.getMessage().contains("retry"));
    }

    @Test
    void unknownErrorsMapToInternal() {
        SQLException e = new SQLException("Something odd", "HY000", 9999);
        RuntimeException mapped = SQLExceptionMapper.translate("op", e);
        assertInstanceOf(com.ims.common.exception.InternalErrorException.class, mapped);
        assertEquals(500, ((com.ims.common.exception.ApiException) mapped).httpStatus());
    }
}
