package com.ims.domain.sales;

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

/** JDBC DAO for sales orders, lines and reservations. */
public final class SalesOrderDao {

    private static final String COLUMNS = """
            SELECT id, so_no, customer_id, warehouse_id, status, order_date,
                   requested_date, subtotal, discount_total, tax_total, total, notes,
                   version, created_by, confirmed_by, confirmed_at, created_at, updated_at
              FROM sales_orders
            """;

    public Optional<SalesOrder> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<SalesOrder> lockById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<SalesOrder> search(Connection c, String status, Long customerId,
                                   Long warehouseId, String dateFrom, String dateTo,
                                   int limit, int offset) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            params.add(customerId);
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
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<SalesOrder> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public long countSearch(Connection c, String status, Long customerId,
                            Long warehouseId, String dateFrom, String dateTo)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            params.add(customerId);
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
                "SELECT COUNT(*) FROM sales_orders" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public SalesOrder insert(Connection c, SalesOrder so) throws SQLException {
        String sql = """
                INSERT INTO sales_orders (so_no, customer_id, warehouse_id, status,
                                          order_date, requested_date, subtotal,
                                          discount_total, tax_total, total, notes,
                                          created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, so.getSoNo());
            ps.setLong(2, so.getCustomerId());
            ps.setLong(3, so.getWarehouseId());
            ps.setString(4, so.getStatus());
            SqlUtils.setNullableLocalDate(ps, 5, so.getOrderDate());
            SqlUtils.setNullableLocalDate(ps, 6, so.getRequestedDate());
            ps.setBigDecimal(7, so.getSubtotal());
            ps.setBigDecimal(8, so.getDiscountTotal());
            ps.setBigDecimal(9, so.getTaxTotal());
            ps.setBigDecimal(10, so.getTotal());
            SqlUtils.setNullableString(ps, 11, so.getNotes());
            SqlUtils.setNullableLong(ps, 12, so.getCreatedBy());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    so.setId(keys.getLong(1));
                }
            }
            return so;
        }
    }

    public boolean update(Connection c, SalesOrder so) throws SQLException {
        String sql = """
                UPDATE sales_orders
                   SET customer_id = ?, warehouse_id = ?, requested_date = ?, subtotal = ?,
                       discount_total = ?, tax_total = ?, total = ?, notes = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, so.getCustomerId());
            ps.setLong(2, so.getWarehouseId());
            SqlUtils.setNullableLocalDate(ps, 3, so.getRequestedDate());
            ps.setBigDecimal(4, so.getSubtotal());
            ps.setBigDecimal(5, so.getDiscountTotal());
            ps.setBigDecimal(6, so.getTaxTotal());
            ps.setBigDecimal(7, so.getTotal());
            SqlUtils.setNullableString(ps, 8, so.getNotes());
            ps.setLong(9, so.getId());
            ps.setInt(10, so.getVersion());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean updateStatus(Connection c, long id, String fromStatus, String toStatus)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sales_orders SET status = ?, version = version + 1 "
                        + "WHERE id = ? AND status = ?")) {
            ps.setString(1, toStatus);
            ps.setLong(2, id);
            ps.setString(3, fromStatus);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean markConfirmed(Connection c, long id, long userId) throws SQLException {
        String sql = """
                UPDATE sales_orders
                   SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = ?,
                       version = version + 1
                 WHERE id = ? AND status = 'DRAFT'
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
            ps.setLong(3, id);
            return ps.executeUpdate() == 1;
        }
    }

    // ---- lines ----

    public List<SalesOrderLine> findLines(Connection c, long soId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, so_id, line_no, product_id, qty_ordered, qty_reserved,
                       qty_fulfilled, unit_price, discount_pct, tax_pct, line_total
                  FROM sales_order_lines WHERE so_id = ? ORDER BY line_no
                """)) {
            ps.setLong(1, soId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SalesOrderLine> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapLine(rs));
                }
                return result;
            }
        }
    }

    public void replaceLines(Connection c, long soId, List<SalesOrderLine> lines)
            throws SQLException {
        try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM sales_order_lines WHERE so_id = ?")) {
            del.setLong(1, soId);
            del.executeUpdate();
        }
        String sql = """
                INSERT INTO sales_order_lines (so_id, line_no, product_id, qty_ordered,
                                               qty_reserved, qty_fulfilled, unit_price,
                                               discount_pct, tax_pct, line_total)
                VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (SalesOrderLine line : lines) {
                ps.setLong(1, soId);
                ps.setInt(2, line.getLineNo());
                ps.setLong(3, line.getProductId());
                ps.setBigDecimal(4, line.getQtyOrdered());
                ps.setBigDecimal(5, line.getUnitPrice());
                ps.setBigDecimal(6, line.getDiscountPct());
                ps.setBigDecimal(7, line.getTaxPct());
                ps.setBigDecimal(8, line.getLineTotal());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void updateLineFulfilment(Connection c, long lineId, BigDecimal qtyReserved,
                                     BigDecimal qtyFulfilled) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE sales_order_lines
                   SET qty_reserved = ?, qty_fulfilled = ?
                 WHERE id = ?
                """)) {
            ps.setBigDecimal(1, qtyReserved);
            ps.setBigDecimal(2, qtyFulfilled);
            ps.setLong(3, lineId);
            ps.executeUpdate();
        }
    }

    // ---- reservations ----

    public void insertReservation(Connection c, Reservation reservation)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reservations (sales_order_id, sales_order_line_id, product_id,
                                          warehouse_id, qty_reserved, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, reservation.getSalesOrderId());
            ps.setLong(2, reservation.getSalesOrderLineId());
            ps.setLong(3, reservation.getProductId());
            ps.setLong(4, reservation.getWarehouseId());
            ps.setBigDecimal(5, reservation.getQtyReserved());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    reservation.setId(keys.getLong(1));
                }
            }
        }
    }

    public List<Reservation> findActiveReservations(Connection c, long soId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, sales_order_id, sales_order_line_id, product_id, warehouse_id,
                       qty_reserved, status, created_at, released_at
                  FROM reservations WHERE sales_order_id = ? AND status = 'ACTIVE'
                """)) {
            ps.setLong(1, soId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Reservation> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapReservation(rs));
                }
                return result;
            }
        }
    }

    public void updateReservationStatus(Connection c, long reservationId, String status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE reservations
                   SET status = ?, released_at = CASE WHEN ? != 'ACTIVE' THEN NOW(3)
                                                      ELSE NULL END
                 WHERE id = ?
                """)) {
            ps.setString(1, status);
            ps.setString(2, status);
            ps.setLong(3, reservationId);
            ps.executeUpdate();
        }
    }

    /** Shrink an ACTIVE reservation (partial consumption). */
    public void updateReservationQty(Connection c, long reservationId,
                                     java.math.BigDecimal newQty) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE reservations
                   SET qty_reserved = ?
                 WHERE id = ? AND status = 'ACTIVE'
                """)) {
            ps.setBigDecimal(1, newQty);
            ps.setLong(2, reservationId);
            ps.executeUpdate();
        }
    }

    static SalesOrder map(ResultSet rs) throws SQLException {
        SalesOrder so = new SalesOrder();
        so.setId(SqlUtils.getLong(rs, "id"));
        so.setSoNo(rs.getString("so_no"));
        so.setCustomerId(SqlUtils.getLong(rs, "customer_id"));
        so.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        so.setStatus(rs.getString("status"));
        so.setOrderDate(SqlUtils.getLocalDate(rs, "order_date"));
        so.setRequestedDate(SqlUtils.getLocalDate(rs, "requested_date"));
        so.setSubtotal(rs.getBigDecimal("subtotal"));
        so.setDiscountTotal(rs.getBigDecimal("discount_total"));
        so.setTaxTotal(rs.getBigDecimal("tax_total"));
        so.setTotal(rs.getBigDecimal("total"));
        so.setNotes(rs.getString("notes"));
        so.setVersion(rs.getInt("version"));
        so.setCreatedBy(SqlUtils.getLong(rs, "created_by"));
        so.setConfirmedBy(SqlUtils.getLong(rs, "confirmed_by"));
        so.setConfirmedAt(SqlUtils.getInstant(rs, "confirmed_at"));
        so.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        so.setUpdatedAt(SqlUtils.getInstant(rs, "updated_at"));
        return so;
    }

    static SalesOrderLine mapLine(ResultSet rs) throws SQLException {
        SalesOrderLine line = new SalesOrderLine();
        line.setId(SqlUtils.getLong(rs, "id"));
        line.setSoId(SqlUtils.getLong(rs, "so_id"));
        line.setLineNo(rs.getInt("line_no"));
        line.setProductId(SqlUtils.getLong(rs, "product_id"));
        line.setQtyOrdered(rs.getBigDecimal("qty_ordered"));
        line.setQtyReserved(rs.getBigDecimal("qty_reserved"));
        line.setQtyFulfilled(rs.getBigDecimal("qty_fulfilled"));
        line.setUnitPrice(rs.getBigDecimal("unit_price"));
        line.setDiscountPct(rs.getBigDecimal("discount_pct"));
        line.setTaxPct(rs.getBigDecimal("tax_pct"));
        line.setLineTotal(rs.getBigDecimal("line_total"));
        return line;
    }

    static Reservation mapReservation(ResultSet rs) throws SQLException {
        Reservation r = new Reservation();
        r.setId(SqlUtils.getLong(rs, "id"));
        r.setSalesOrderId(SqlUtils.getLong(rs, "sales_order_id"));
        r.setSalesOrderLineId(SqlUtils.getLong(rs, "sales_order_line_id"));
        r.setProductId(SqlUtils.getLong(rs, "product_id"));
        r.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        r.setQtyReserved(rs.getBigDecimal("qty_reserved"));
        r.setStatus(rs.getString("status"));
        r.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        r.setReleasedAt(SqlUtils.getInstant(rs, "released_at"));
        return r;
    }
}
