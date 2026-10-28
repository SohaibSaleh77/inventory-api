package com.ims.domain.transfer;

import com.ims.common.util.SqlUtils;
import com.ims.db.DocumentNumbers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC DAO for stock_transfers. */
public final class StockTransferDao {

    private static final String COLUMNS = """
            SELECT id, transfer_no, product_id, from_warehouse_id, to_warehouse_id,
                   quantity, status, note, version, created_by, created_at,
                   dispatched_by, dispatched_at, received_by, received_at
              FROM stock_transfers
            """;

    public Optional<StockTransfer> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Lock the transfer row FOR UPDATE (state transitions must serialize). */
    public Optional<StockTransfer> lockById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                COLUMNS + " WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<StockTransfer> search(Connection c, String status, Long productId,
                                      int limit, int offset) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (productId != null) {
            where.append(" AND product_id = ?");
            params.add(productId);
        }
        String sql = COLUMNS + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<StockTransfer> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public long countSearch(Connection c, String status, Long productId)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (productId != null) {
            where.append(" AND product_id = ?");
            params.add(productId);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM stock_transfers" + where)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public StockTransfer insert(Connection c, DocumentNumbers docNumbers,
                                StockTransfer transfer) throws SQLException {
        transfer.setTransferNo(docNumbers.next(c, DocumentNumbers.DocType.TRANSFER));
        String sql = """
                INSERT INTO stock_transfers (transfer_no, product_id, from_warehouse_id,
                                             to_warehouse_id, quantity, status, note,
                                             created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, transfer.getTransferNo());
            ps.setLong(2, transfer.getProductId());
            ps.setLong(3, transfer.getFromWarehouseId());
            ps.setLong(4, transfer.getToWarehouseId());
            ps.setBigDecimal(5, transfer.getQuantity());
            ps.setString(6, transfer.getStatus());
            SqlUtils.setNullableString(ps, 7, transfer.getNote());
            SqlUtils.setNullableLong(ps, 8, transfer.getCreatedBy());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    transfer.setId(keys.getLong(1));
                }
            }
            return transfer;
        }
    }

    public boolean markDispatched(Connection c, long id, long userId) throws SQLException {
        String sql = """
                UPDATE stock_transfers
                   SET status = 'DISPATCHED', dispatched_by = ?, dispatched_at = ?,
                       version = version + 1
                 WHERE id = ? AND status = 'PENDING'
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
            ps.setLong(3, id);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean markReceived(Connection c, long id, long userId) throws SQLException {
        String sql = """
                UPDATE stock_transfers
                   SET status = 'RECEIVED', received_by = ?, received_at = ?,
                       version = version + 1
                 WHERE id = ? AND status = 'DISPATCHED'
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
            ps.setLong(3, id);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean markCancelled(Connection c, long id) throws SQLException {
        String sql = """
                UPDATE stock_transfers
                   SET status = 'CANCELLED', version = version + 1
                 WHERE id = ? AND status = 'PENDING'
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    static StockTransfer map(ResultSet rs) throws SQLException {
        StockTransfer t = new StockTransfer();
        t.setId(SqlUtils.getLong(rs, "id"));
        t.setTransferNo(rs.getString("transfer_no"));
        t.setProductId(SqlUtils.getLong(rs, "product_id"));
        t.setFromWarehouseId(SqlUtils.getLong(rs, "from_warehouse_id"));
        t.setToWarehouseId(SqlUtils.getLong(rs, "to_warehouse_id"));
        t.setQuantity(rs.getBigDecimal("quantity"));
        t.setStatus(rs.getString("status"));
        t.setNote(rs.getString("note"));
        t.setVersion(rs.getInt("version"));
        t.setCreatedBy(SqlUtils.getLong(rs, "created_by"));
        t.setCreatedAt(SqlUtils.getInstant(rs, "created_at"));
        t.setDispatchedBy(SqlUtils.getLong(rs, "dispatched_by"));
        t.setDispatchedAt(SqlUtils.getInstant(rs, "dispatched_at"));
        t.setReceivedBy(SqlUtils.getLong(rs, "received_by"));
        t.setReceivedAt(SqlUtils.getInstant(rs, "received_at"));
        return t;
    }
}
