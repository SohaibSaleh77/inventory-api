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

/** JDBC DAO for shipments. */
public final class ShipmentDao {

    public Shipment insert(Connection c, DocumentNumbers docNumbers, Shipment shipment)
            throws SQLException {
        shipment.setShipmentNo(docNumbers.next(c, DocumentNumbers.DocType.SHIPMENT));
        String sql = """
                INSERT INTO shipments (shipment_no, sales_order_id, warehouse_id,
                                       shipped_by, carrier, tracking_no, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, shipment.getShipmentNo());
            ps.setLong(2, shipment.getSalesOrderId());
            ps.setLong(3, shipment.getWarehouseId());
            SqlUtils.setNullableLong(ps, 4, shipment.getShippedBy());
            SqlUtils.setNullableString(ps, 5, shipment.getCarrier());
            SqlUtils.setNullableString(ps, 6, shipment.getTrackingNo());
            SqlUtils.setNullableString(ps, 7, shipment.getNote());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    shipment.setId(keys.getLong(1));
                }
            }
            return shipment;
        }
    }

    public ShipmentLine insertLine(Connection c, ShipmentLine line) throws SQLException {
        String sql = """
                INSERT INTO shipment_lines (shipment_id, sales_order_line_id, product_id,
                                            qty_shipped, unit_cost_at_ship)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, line.getShipmentId());
            ps.setLong(2, line.getSalesOrderLineId());
            ps.setLong(3, line.getProductId());
            ps.setBigDecimal(4, line.getQtyShipped());
            ps.setBigDecimal(5, line.getUnitCostAtShip());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    line.setId(keys.getLong(1));
                }
            }
            return line;
        }
    }

    public List<Shipment> findBySalesOrder(Connection c, long soId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, shipment_no, sales_order_id, warehouse_id, shipped_by,
                       shipped_at, carrier, tracking_no, note
                  FROM shipments WHERE sales_order_id = ? ORDER BY id
                """)) {
            ps.setLong(1, soId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Shipment> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    public List<ShipmentLine> findLines(Connection c, long shipmentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, shipment_id, sales_order_line_id, product_id, qty_shipped,
                       unit_cost_at_ship
                  FROM shipment_lines WHERE shipment_id = ? ORDER BY id
                """)) {
            ps.setLong(1, shipmentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ShipmentLine> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapLine(rs));
                }
                return result;
            }
        }
    }

    static Shipment map(ResultSet rs) throws SQLException {
        Shipment s = new Shipment();
        s.setId(SqlUtils.getLong(rs, "id"));
        s.setShipmentNo(rs.getString("shipment_no"));
        s.setSalesOrderId(SqlUtils.getLong(rs, "sales_order_id"));
        s.setWarehouseId(SqlUtils.getLong(rs, "warehouse_id"));
        s.setShippedBy(SqlUtils.getLong(rs, "shipped_by"));
        s.setShippedAt(SqlUtils.getInstant(rs, "shipped_at"));
        s.setCarrier(rs.getString("carrier"));
        s.setTrackingNo(rs.getString("tracking_no"));
        s.setNote(rs.getString("note"));
        return s;
    }

    static ShipmentLine mapLine(ResultSet rs) throws SQLException {
        ShipmentLine line = new ShipmentLine();
        line.setId(SqlUtils.getLong(rs, "id"));
        line.setShipmentId(SqlUtils.getLong(rs, "shipment_id"));
        line.setSalesOrderLineId(SqlUtils.getLong(rs, "sales_order_line_id"));
        line.setProductId(SqlUtils.getLong(rs, "product_id"));
        line.setQtyShipped(rs.getBigDecimal("qty_shipped"));
        line.setUnitCostAtShip(rs.getBigDecimal("unit_cost_at_ship"));
        return line;
    }
}
