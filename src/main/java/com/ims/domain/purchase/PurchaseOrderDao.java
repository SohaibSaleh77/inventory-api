package com.ims.domain.purchase;

import com.ims.common.util.SqlUtils;

import java.math.BigDecimal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC DAO for purchase order headers and lines. */
public final class PurchaseOrderDao {

    private static final String COLUMNS = """
            SELECT id, po_no, supplier_id, warehouse_id, status, order_date, expected_date,
                   currency, subtotal, tax_total, total, notes, version, created_by,
                   approved_by, approved_at, created_at, updated_at
              FROM purchase_orders
            """;

    public Optional<PurchaseOrder> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<PurchaseOrder> lockById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<PurchaseOrder> search(Connection c, String status, Long supplierId,
                                      Long warehouseId, String dateFrom, String dateTo,
                                      int limit, int offset) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (supplierId != null) {
            where.append(" AND supplier_id = ?");
            params.add(supplierId);
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            params.add(warehouseId);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            where.append(" AND order_date >= ?");
            params.add(java.sql.Date.valueOf(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            where.append(" AND order_date <= ?");
            params.add(java.sql.Date.valueOf(dateTo));
        }
        String sql = COLUMNS + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        return queryList(c, sql, params);
    }

    public long countSearch(Connection c, String status, Long supplierId,
                            Long warehouseId, String dateFrom, String dateTo)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (supplierId != null) {
            where.append(" AND supplier_id = ?");
            params.add(supplierId);
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            params.add(warehouseId);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            where.append(" AND order_date >= ?");
            params.add(java.sql.Date.valueOf(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            where.append(" AND order_date <= ?");
            params.add(java.sql.Date.valueOf(dateTo));
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM purchase_orders" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public PurchaseOrder insert(Connection c, PurchaseOrder po) throws SQLException {
        String sql = """
                INSERT INTO purchase_orders (po_no, supplier_id, warehouse_id, status,
                                             order_date, expected_date, currency, subtotal,
                                             tax_total, total, notes, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, po.getPoNo());
            ps.setLong(2, po.getSupplierId());
            ps.setLong(3, po.getWarehouseId());
            ps.setString(4, po.getStatus());
            SqlUtils.setNullableLocalDate(ps, 5, po.getOrderDate());
            SqlUtils.setNullableLocalDate(ps, 6, po.getExpectedDate());
            ps.setString(7, po.getCurrency());
            ps.setBigDecimal(8, po.getSubtotal());
            ps.setBigDecimal(9, po.getTaxTotal());
            ps.setBigDecimal(10, po.getTotal());
            SqlUtils.setNullableString(ps, 11, po.getNotes());
            SqlUtils.setNullableLong(ps, 12, po.getCreatedBy());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    po.setId(keys.getLong(1));
                }
            }
            return po;
        }
    }

    public boolean update(Connection c, PurchaseOrder po) throws SQLException {
        String sql = """
                UPDATE purchase_orders
                   SET supplier_id = ?, warehouse_id = ?, order_date = ?, expected_date = ?,
                       currency = ?, subtotal = ?, tax_total = ?, total = ?, notes = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, po.getSupplierId());
            ps.setLong(2, po.getWarehouseId());
            SqlUtils.setNullableLocalDate(ps, 3, po.getOrderDate());
            SqlUtils.setNullableLocalDate(ps, 4, po.getExpectedDate());
            ps.setString(5, po.getCurrency());
            ps.setBigDecimal(6, po.getSubtotal());
            ps.setBigDecimal(7, po.getTaxTotal());
            ps.setBigDecimal(8, po.getTotal());
            SqlUtils.setNullableString(ps, 9, po.getNotes());
            ps.setLong(10, po.getId());
            ps.setInt(11, po.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean updateStatus(Connection c, long id, String fromStatus, String toStatus)
            throws SQLException {
        String sql = "UPDATE purchase_orders SET status = ?, version = version + 1 "
                + "WHERE id = ? AND status = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, toStatus);
            ps.setLong(2, id);
            ps.setString(3, fromStatus);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean markApproved(Connection c, long id, long approvedBy) throws SQLException {
        String sql = """
                UPDATE purchase_orders
                   SET status = 'APPROVED', approved_by = ?, approved_at = ?,
                       version = version + 1
                 WHERE id = ? AND status = 'SUBMITTED'
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, approvedBy);
            ps.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
            ps.setLong(3, id);
            return ps.executeUpdate() == 1;
        }
    }

    public long countOpenForSupplier(Connection c, long supplierId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM purchase_orders
                 WHERE supplier_id = ? AND status NOT IN ('CANCELLED','CLOSED')
                """)) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ---- lines ----

    public List<PurchaseOrderLine> findLines(Connection c, long poId) throws SQLException {
        String sql = """
                SELECT id, po_id, line_no, product_id, qty_ordered, qty_received,
                       unit_cost, tax_pct, line_total
                  FROM purchase_order_lines
                 WHERE po_id = ?
                 ORDER BY line_no
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, poId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PurchaseOrderLine> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapLine(rs));
                }
                return result;
            }
        }
    }

    public void replaceLines(Connection c, long poId, List<PurchaseOrderLine> lines)
            throws SQLException {
        try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM purchase_order_lines WHERE po_id = ?")) {
            del.setLong(1, poId);
            del.executeUpdate();
        }
        String sql = """
                INSERT INTO purchase_order_lines (po_id, line_no, product_id, qty_ordered,
                                                  qty_received, unit_cost, tax_pct,
                                                  line_total)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (PurchaseOrderLine line : lines) {
                ps.setLong(1, poId);
                ps.setInt(2, line.getLineNo());
                ps.setLong(3, line.getProductId());
                ps.setBigDecimal(4, line.getQtyOrdered());
                ps.setBigDecimal(5, line.getUnitCost());
                ps.setBigDecimal(6, line.getTaxPct());
                ps.setBigDecimal(7, line.getLineTotal());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Update received quantity on a line (guarded by qty <= qty_ordered CHECK). */
    public void updateLineReceived(Connection c, long lineId, BigDecimal qtyReceived)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE purchase_order_lines SET qty_received = ? WHERE id = ?")) {
            ps.setBigDecimal(1, qtyReceived);
            ps.setLong(2, lineId);
            ps.executeUpdate();
        }
    }

    public Optional<PurchaseOrderLine> findLine(Connection c, long lineId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, po_id, line_no, product_id, qty_ordered, qty_received,
                       unit_cost, tax_pct, line_total
                  FROM purchase_order_lines WHERE id = ?
                """)) {
            ps.setLong(1, lineId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapLine(rs)) : Optional.empty();
            }
        }
    }

    private List<PurchaseOrder> queryList(Connection c, String sql, List<Object> params)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<PurchaseOrder> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    static PurchaseOrder map(ResultSet rs) throws SQLException {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(SqlUtils.getLong(rs, "id"));
        po.setPoNo(rs.getString("po_no"));
        po.setSupplierId(SqlUtils.getLong(rs, "supplier_id"));
        po.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        po.setStatus(rs.getString("status"));
        po.setOrderDate(SqlUtils.getLocalDate(rs, "order_date"));
        po.setExpectedDate(SqlUtils.getLocalDate(rs, "expected_date"));
        po.setCurrency(rs.getString("currency"));
        po.setSubtotal(rs.getBigDecimal("subtotal"));
        po.setTaxTotal(rs.getBigDecimal("tax_total"));
        po.setTotal(rs.getBigDecimal("total"));
        po.setNotes(rs.getString("notes"));
        po.setVersion(rs.getInt("version"));
        po.setCreatedBy(SqlUtils.getLong(rs, "created_by"));
        po.setApprovedBy(SqlUtils.getLong(rs, "approved_by"));
        po.setApprovedAt(SqlUtils.getInstant(rs, "approved_at"));
        po.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        po.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return po;
    }

    static PurchaseOrderLine mapLine(ResultSet rs) throws SQLException {
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setId(SqlUtils.getLong(rs, "id"));
        line.setPoId(SqlUtils.getLong(rs, "po_id"));
        line.setLineNo(rs.getInt("line_no"));
        line.setProductId(SqlUtils.getLong(rs, "product_id"));
        line.setQtyOrdered(rs.getBigDecimal("qty_ordered"));
        line.setQtyReceived(rs.getBigDecimal("qty_received"));
        line.setUnitCost(rs.getBigDecimal("unit_cost"));
        line.setTaxPct(rs.getBigDecimal("tax_pct"));
        line.setLineTotal(rs.getBigDecimal("line_total"));
        return line;
    }

    // ---- reorder proposals ----

    /** One replenishment suggestion row (see v_reorder_proposals). */
    public record ReorderRow(long productId, String sku, String productName,
                             String unitOfMeasure, BigDecimal reorderPoint,
                             BigDecimal reorderQty, BigDecimal totalAvailable,
                             BigDecimal suggestedQty, Long supplierId,
                             String supplierCode, String supplierName,
                             BigDecimal unitCost, Integer leadTimeDays) {
    }

    /**
     * Products at or below their reorder point with the cheapest preferred
     * supplier attached; optionally restricted to one supplier.
     */
    public List<ReorderRow> reorderProposals(Connection c, Long supplierId)
            throws SQLException {
        String sql = """
                SELECT product_id, sku, name, unit_of_measure, reorder_point,
                       reorder_qty, total_available, suggested_qty, supplier_id,
                       supplier_code, supplier_name, unit_cost, lead_time_days
                  FROM v_reorder_proposals
                 WHERE (? IS NULL OR supplier_id = ?)
                 ORDER BY supplier_code, sku
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (supplierId == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, supplierId);
                ps.setLong(2, supplierId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<ReorderRow> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    rows.add(new ReorderRow(
                            rs.getLong("product_id"),
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getString("unit_of_measure"),
                            rs.getBigDecimal("reorder_point"),
                            rs.getBigDecimal("reorder_qty"),
                            rs.getBigDecimal("total_available"),
                            rs.getBigDecimal("suggested_qty"),
                            SqlUtils.getLong(rs, "supplier_id"),
                            rs.getString("supplier_code"),
                            rs.getString("supplier_name"),
                            rs.getBigDecimal("unit_cost"),
                            SqlUtils.getInt(rs, "lead_time_days")));
                }
                return rows;
            }
        }
    }
}
