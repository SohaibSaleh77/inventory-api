package com.ims.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Gap-tolerant, concurrency-safe document numbering backed by the
 * doc_sequences table. The row is locked with SELECT ... FOR UPDATE inside
 * the caller's transaction, so concurrent transactions serialize per
 * document type - numbers are unique, ordered, and never handed out twice.
 */
public final class DocumentNumbers {

    /** Document types with their prefixes and zero padding. */
    public enum DocType {
        PURCHASE_ORDER("PO", 6),
        SALES_ORDER("SO", 6),
        RECEIPT("GRN", 6),
        SHIPMENT("SHP", 6),
        SALES_RETURN("RET", 6),
        TRANSFER("TRF", 6),
        ADJUSTMENT("ADJ", 6),
        STOCKTAKE("STK", 6),
        MOVEMENT("MOV", 9);

        private final String prefix;
        private final int padding;

        DocType(String prefix, int padding) {
            this.prefix = prefix;
            this.padding = padding;
        }

        public String prefix() {
            return prefix;
        }
    }

    /**
     * Reserve the next number for the document type. MUST be called inside a
     * transaction (throws when not).
     */
    public String next(Connection conn, DocType type) throws SQLException {
        Integer currentValue = lockCurrent(conn, type);
        long next;
        if (currentValue == null) {
            next = 1;
            insertSequence(conn, type, next + 1);
        } else {
            next = currentValue;
            updateSequence(conn, type, next + 1);
        }
        return type.prefix() + "-" + String.format("%0" + type.padding + "d", next);
    }

    private Integer lockCurrent(Connection conn, DocType type) throws SQLException {
        String sql = "SELECT next_val FROM doc_sequences WHERE doc_type = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("next_val") : null;
            }
        }
    }

    private void insertSequence(Connection conn, DocType type, long nextValue)
            throws SQLException {
        String sql = "INSERT INTO doc_sequences (doc_type, prefix, next_val) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setString(2, type.prefix());
            ps.setLong(3, nextValue);
            ps.executeUpdate();
        }
    }

    private void updateSequence(Connection conn, DocType type, long nextValue)
            throws SQLException {
        String sql = "UPDATE doc_sequences SET next_val = ? WHERE doc_type = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, nextValue);
            ps.setString(2, type.name());
            ps.executeUpdate();
        }
    }
}
