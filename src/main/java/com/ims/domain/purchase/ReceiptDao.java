package com.ims.domain.purchase;

import com.ims.common.util.SqlUtils;
import com.ims.db.DocumentNumbers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** JDBC DAO for goods receipts. */
public final class ReceiptDao {

    public Receipt insert(Connection c, DocumentNumbers docNumbers, Receipt receipt)
            throws SQLException {
        receipt.setReceiptNo(docNumbers.next(c, DocumentNumbers.DocType.RECEIPT));
        String sql = """
                INSERT INTO receipts (receipt_no, po_id, warehouse_id, received_by, note)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, receipt.getReceiptNo());
            ps.setLong(2, receipt.getPoId());
            ps.setLong(3, receipt.getWarehouseId());
            SqlUtils.setNullableLong(ps, 4, receipt.getReceivedBy());
            SqlUtils.setNullableString(ps, 5, receipt.getNote());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    receipt.setId(keys.getLong(1));
                }
            }
            return receipt;
        }
    }

    public ReceiptLine insertLine(Connection c, ReceiptLine line) throws SQLException {
        String sql = """
                INSERT INTO receipt_lines (receipt_id, po_line_id, product_id,
                                           qty_accepted, qty_rejected, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, line.getReceiptId());
            ps.setLong(2, line.getPoLineId());
            ps.setLong(3, line.getProductId());
            ps.setBigDecimal(4, line.getQtyAccepted());
            ps.setBigDecimal(5, line.getQtyRejected());
            ps.setBigDecimal(6, line.getUnitCost());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    line.setId(keys.getLong(1));
                }
            }
            return line;
        }
    }

    public List<Receipt> findByPo(Connection c, long poId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, receipt_no, po_id, warehouse_id, received_by, received_at, note
                  FROM receipts WHERE po_id = ? ORDER BY id
                """)) {
            ps.setLong(1, poId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Receipt> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public List<ReceiptLine> findLines(Connection c, long receiptId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, receipt_id, po_line_id, product_id, qty_accepted, qty_rejected,
                       unit_cost
                  FROM receipt_lines WHERE receipt_id = ? ORDER BY id
                """)) {
            ps.setLong(1, receiptId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ReceiptLine> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapLine(rs));
                }
                return result;
            }
        }
    }

    static Receipt map(ResultSet rs) throws SQLException {
        Receipt r = new Receipt();
        r.setId(SqlUtils.getLong(rs, "id"));
        r.setReceiptNo(rs.getString("receipt_no"));
        r.setPoId(SqlUtils.getLong(rs, "po_id"));
        r.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        r.setReceivedBy(SqlUtils.getLong(rs, "received_by"));
        r.setReceivedAt(SqlUtils.getInstant(rs, "received_at"));
        r.setNote(rs.getString("note"));
        return r;
    }

    static ReceiptLine mapLine(ResultSet rs) throws SQLException {
        ReceiptLine line = new ReceiptLine();
        line.setId(SqlUtils.getLong(rs, "id"));
        line.setReceiptId(SqlUtils.getLong(rs, "receipt_id"));
        line.setPoLineId(SqlUtils.getLong(rs, "po_line_id"));
        line.setProductId(SqlUtils.getLong(rs, "product_id"));
        line.setQtyAccepted(rs.getBigDecimal("qty_accepted"));
        line.setQtyRejected(rs.getBigDecimal("qty_rejected"));
        line.setUnitCost(rs.getBigDecimal("unit_cost"));
        return line;
    }
}
