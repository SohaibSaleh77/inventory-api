package com.ims.domain.sales;

import com.ims.common.util.SqlUtils;
import com.ims.db.DocumentNumbers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** JDBC DAO for sales returns. */
public final class SalesReturnDao {

    public SalesReturn insert(Connection c, DocumentNumbers docNumbers,
                              SalesReturn salesReturn) throws SQLException {
        salesReturn.setReturnNo(docNumbers.next(c, DocumentNumbers.DocType.SALES_RETURN));
        String sql = """
                INSERT INTO sales_returns (return_no, sales_order_id, warehouse_id,
                                           received_by, reason, note)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, salesReturn.getReturnNo());
            ps.setLong(2, salesReturn.getSalesOrderId());
            ps.setLong(3, salesReturn.getWarehouseId());
            SqlUtils.setNullableLong(ps, 4, salesReturn.getReceivedBy());
            SqlUtils.setNullableString(ps, 5, salesReturn.getReason());
            SqlUtils.setNullableString(ps, 6, salesReturn.getNote());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    salesReturn.setId(keys.getLong(1));
                }
            }
            return salesReturn;
        }
    }

    public SalesReturnLine insertLine(Connection c, SalesReturnLine line)
            throws SQLException {
        String sql = """
                INSERT INTO sales_return_lines (sales_return_id, sales_order_line_id,
                                                product_id, qty_returned, restock,
                                                credit_amount)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, line.getSalesReturnId());
            ps.setLong(2, line.getSalesOrderLineId());
            ps.setLong(3, line.getProductId());
            ps.setBigDecimal(4, line.getQtyReturned());
            ps.setBoolean(5, line.isRestock());
            ps.setBigDecimal(6, line.getCreditAmount());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    line.setId(keys.getLong(1));
                }
            }
            return line;
        }
    }

    public List<SalesReturn> findBySalesOrder(Connection c, long soId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, return_no, sales_order_id, warehouse_id, received_by,
                       received_at, reason, note
                  FROM sales_returns WHERE sales_order_id = ? ORDER BY id
                """)) {
            ps.setLong(1, soId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SalesReturn> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public List<SalesReturnLine> findLines(Connection c, long returnId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, sales_return_id, sales_order_line_id, product_id, qty_returned,
                       restock, credit_amount
                  FROM sales_return_lines WHERE sales_return_id = ? ORDER BY id
                """)) {
            ps.setLong(1, returnId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SalesReturnLine> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapLine(rs));
                }
                return result;
            }
        }
    }

    /** Total quantity returned per SO line (for return-over-shipment checks). */
    public java.math.BigDecimal totalReturnedForLine(Connection c, long soLineId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(rl.qty_returned), 0)
                  FROM sales_return_lines rl
                  JOIN sales_returns r ON r.id = rl.sales_return_id
                 WHERE rl.sales_order_line_id = ?
                """)) {
            ps.setLong(1, soLineId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    static SalesReturn map(ResultSet rs) throws SQLException {
        SalesReturn r = new SalesReturn();
        r.setId(SqlUtils.getLong(rs, "id"));
        r.setReturnNo(rs.getString("return_no"));
        r.setSalesOrderId(SqlUtils.getLong(rs, "sales_order_id"));
        r.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        r.setReceivedBy(SqlUtils.getLong(rs, "received_by"));
        r.setReceivedAt(SqlUtils.getInstant(rs, "received_at"));
        r.setReason(rs.getString("reason"));
        r.setNote(rs.getString("note"));
        return r;
    }

    static SalesReturnLine mapLine(ResultSet rs) throws SQLException {
        SalesReturnLine line = new SalesReturnLine();
        line.setId(SqlUtils.getLong(rs, "id"));
        line.setSalesReturnId(SqlUtils.getLong(rs, "sales_return_id"));
        line.setSalesOrderLineId(SqlUtils.getLong(rs, "sales_order_line_id"));
        line.setProductId(SqlUtils.getLong(rs, "product_id"));
        line.setQtyReturned(rs.getBigDecimal("qty_returned"));
        line.setRestock(rs.getBoolean("restock"));
        line.setCreditAmount(rs.getBigDecimal("credit_amount"));
        return line;
    }
}
