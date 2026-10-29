package com.ims.testing;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Global consistency checks over the whole database. Used after business
 * simulations to prove the invariants that must hold no matter what
 * combination of operations ran:
 *
 * <ul>
 *   <li>stock_items.qty_on_hand == SUM(stock_movements.quantity) per
 *       (product, warehouse)</li>
 *   <li>stock_items.qty_reserved == SUM(active reservations)</li>
 *   <li>0 &lt;= qty_reserved &lt;= qty_on_hand everywhere</li>
 *   <li>every movement references its source document</li>
 * </ul>
 */
public final class InvariantVerifier {

    private final IntegrationTestEnv env;

    public InvariantVerifier(IntegrationTestEnv env) {
        this.env = env;
    }

    public record Violation(String description) {
    }

    public List<Violation> verifyAll() {
        List<Violation> violations = new ArrayList<>();
        verifyLedgerReconciliation(violations);
        verifyReservationAccounting(violations);
        verifyNoNegativeStock(violations);
        verifyMovementReferences(violations);
        verifyLineFulfilmentConstraints(violations);
        return violations;
    }

    private void verifyLedgerReconciliation(List<Violation> violations) {
        String sql = """
                SELECT si.product_id, si.warehouse_id, si.qty_on_hand,
                       COALESCE((SELECT SUM(m.quantity) FROM stock_movements m
                                  WHERE m.product_id = si.product_id
                                    AND m.warehouse_id = si.warehouse_id), 0) AS ledger_sum
                  FROM stock_items si
                 WHERE si.qty_on_hand <> 0 OR EXISTS (
                       SELECT 1 FROM stock_movements m
                        WHERE m.product_id = si.product_id
                          AND m.warehouse_id = si.warehouse_id)
                """;
        try (Connection c = env.connectionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BigDecimal onHand = rs.getBigDecimal("qty_on_hand");
                BigDecimal ledger = rs.getBigDecimal("ledger_sum");
                if (onHand.compareTo(ledger) != 0) {
                    violations.add(new Violation(
                            "Ledger mismatch: product " + rs.getLong("product_id")
                                    + " warehouse " + rs.getLong("warehouse_id")
                                    + " on_hand=" + onHand + " ledger=" + ledger));
                }
            }
        } catch (SQLException e) {
            violations.add(new Violation("Ledger reconciliation query failed: " + e));
        }
    }

    private void verifyReservationAccounting(List<Violation> violations) {
        String sql = """
                SELECT si.product_id, si.warehouse_id, si.qty_reserved,
                       COALESCE((SELECT SUM(r.qty_reserved) FROM reservations r
                                  WHERE r.product_id = si.product_id
                                    AND r.warehouse_id = si.warehouse_id
                                    AND r.status = 'ACTIVE'), 0) AS active_res
                  FROM stock_items si
                 WHERE si.qty_reserved <> 0 OR EXISTS (
                       SELECT 1 FROM reservations r
                        WHERE r.product_id = si.product_id
                          AND r.warehouse_id = si.warehouse_id
                          AND r.status = 'ACTIVE')
                """;
        try (Connection c = env.connectionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BigDecimal reserved = rs.getBigDecimal("qty_reserved");
                BigDecimal active = rs.getBigDecimal("active_res");
                if (reserved.compareTo(active) != 0) {
                    violations.add(new Violation(
                            "Reservation mismatch: product " + rs.getLong("product_id")
                                    + " warehouse " + rs.getLong("warehouse_id")
                                    + " qty_reserved=" + reserved
                                    + " active_reservations=" + active));
                }
            }
        } catch (SQLException e) {
            violations.add(new Violation("Reservation check failed: " + e));
        }
    }

    private void verifyNoNegativeStock(List<Violation> violations) {
        try (Connection c = env.connectionManager.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT product_id, warehouse_id, qty_on_hand, qty_reserved "
                             + "FROM stock_items WHERE qty_on_hand < 0 OR qty_reserved < 0 "
                             + "OR qty_reserved > qty_on_hand")) {
            while (rs.next()) {
                violations.add(new Violation("Negative/over-reserved stock: product "
                        + rs.getLong(1) + " warehouse " + rs.getLong(2)
                        + " on_hand=" + rs.getBigDecimal(3)
                        + " reserved=" + rs.getBigDecimal(4)));
            }
        } catch (SQLException e) {
            violations.add(new Violation("Negative stock check failed: " + e));
        }
    }

    private void verifyMovementReferences(List<Violation> violations) {
        try (Connection c = env.connectionManager.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT movement_no, movement_type, ref_type FROM stock_movements "
                             + "WHERE movement_type <> 'OPENING_BALANCE' "
                             + "AND (ref_type IS NULL OR ref_id IS NULL OR ref_no IS NULL)")) {
            while (rs.next()) {
                violations.add(new Violation("Movement " + rs.getString(1)
                        + " (" + rs.getString(2) + ") missing document reference"));
            }
        } catch (SQLException e) {
            violations.add(new Violation("Movement reference check failed: " + e));
        }
    }

    private void verifyLineFulfilmentConstraints(List<Violation> violations) {
        try (Connection c = env.connectionManager.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT so_id, line_no, qty_ordered, qty_reserved, qty_fulfilled "
                             + "FROM sales_order_lines "
                             + "WHERE qty_reserved < 0 OR qty_fulfilled < 0 "
                             + "OR qty_fulfilled + qty_reserved > qty_ordered")) {
            while (rs.next()) {
                violations.add(new Violation("SO " + rs.getLong(1) + " line "
                        + rs.getInt(2) + " violates fulfilment constraint"));
            }
        } catch (SQLException e) {
            violations.add(new Violation("SO line check failed: " + e));
        }
        try (Connection c = env.connectionManager.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT po_id, line_no FROM purchase_order_lines "
                             + "WHERE qty_received < 0 OR qty_received > qty_ordered")) {
            while (rs.next()) {
                violations.add(new Violation("PO " + rs.getLong(1) + " line "
                        + rs.getInt(2) + " over-received"));
            }
        } catch (SQLException e) {
            violations.add(new Violation("PO line check failed: " + e));
        }
    }
}
