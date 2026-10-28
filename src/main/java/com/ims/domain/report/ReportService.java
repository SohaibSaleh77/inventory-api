package com.ims.domain.report;

import com.ims.db.TransactionManager;
import com.ims.domain.report.dto.ReportDtos.Dashboard;
import com.ims.domain.report.dto.ReportDtos.GrossMarginReport;
import com.ims.domain.report.dto.ReportDtos.GrossMarginRow;
import com.ims.domain.report.dto.ReportDtos.LowStockReport;
import com.ims.domain.report.dto.ReportDtos.LowStockRow;
import com.ims.domain.report.dto.ReportDtos.MovementSummary;
import com.ims.domain.report.dto.ReportDtos.MovementSummaryRow;
import com.ims.domain.report.dto.ReportDtos.PurchasesBySupplier;
import com.ims.domain.report.dto.ReportDtos.PurchasesBySupplierRow;
import com.ims.domain.report.dto.ReportDtos.SalesByCustomer;
import com.ims.domain.report.dto.ReportDtos.SalesByCustomerRow;
import com.ims.domain.report.dto.ReportDtos.StockSummary;
import com.ims.domain.report.dto.ReportDtos.StockSummaryRow;
import com.ims.domain.report.dto.ReportDtos.StockValuation;
import com.ims.domain.report.dto.ReportDtos.ReorderReport;
import com.ims.domain.report.dto.ReportDtos.ReorderRow;
import com.ims.domain.report.dto.ReportDtos.RecentMovement;
import com.ims.domain.report.dto.ReportDtos.StockValuationRow;
import com.ims.security.AuthContext;
import com.ims.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only operational reports computed directly in SQL (aggregation happens
 * in the database, not in the JVM).
 */
public final class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final TransactionManager tm;

    public ReportService(TransactionManager tm) {
        this.tm = tm;
    }

    public StockSummary stockSummary(AuthContext actor) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            List<StockSummaryRow> rows = new ArrayList<>();
            BigDecimal totalValue = BigDecimal.ZERO;
            String sql = "SELECT * FROM v_product_stock_summary ORDER BY sku";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal onHand = rs.getBigDecimal("total_on_hand");
                    BigDecimal reserved = rs.getBigDecimal("total_reserved");
                    BigDecimal available = rs.getBigDecimal("total_available");
                    BigDecimal reorderPoint = rs.getBigDecimal("reorder_point");
                    StockSummaryRow row = new StockSummaryRow(
                            rs.getString("sku"), rs.getString("name"),
                            rs.getString("unit_of_measure"),
                            rs.getLong("warehouses_with_stock"), onHand, reserved,
                            available, reorderPoint,
                            onHand.compareTo(reorderPoint) <= 0
                                    && reorderPoint.compareTo(BigDecimal.ZERO) > 0);
                    rows.add(row);
                }
            }
            return new StockSummary(rows, rows.size(), totalValue);
        });
    }

    public StockValuation stockValuation(AuthContext actor) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            List<StockValuationRow> rows = new ArrayList<>();
            BigDecimal grandTotal = BigDecimal.ZERO;
            String sql = """
                    SELECT w.code AS warehouse_code, w.name AS warehouse_name,
                           COUNT(si.id) AS product_count,
                           COALESCE(SUM(si.qty_on_hand), 0) AS qty_on_hand,
                           COALESCE(SUM(si.qty_on_hand * si.avg_unit_cost), 0) AS stock_value
                      FROM warehouses w
                      LEFT JOIN stock_items si ON si.warehouse_id = w.id
                                                AND si.qty_on_hand <> 0
                     GROUP BY w.id, w.code, w.name
                     ORDER BY w.code
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StockValuationRow row = new StockValuationRow(
                            rs.getString("warehouse_code"), rs.getString("warehouse_name"),
                            rs.getLong("product_count"), rs.getBigDecimal("qty_on_hand"),
                            rs.getBigDecimal("stock_value"));
                    rows.add(row);
                    grandTotal = grandTotal.add(row.stockValue());
                }
            }
            return new StockValuation(rows, grandTotal);
        });
    }

    public LowStockReport lowStock(AuthContext actor) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            List<LowStockRow> rows = new ArrayList<>();
            String sql = """
                    SELECT sku, name, reorder_point, reorder_qty, total_on_hand
                      FROM v_low_stock ORDER BY sku
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal onHand = rs.getBigDecimal("total_on_hand");
                    BigDecimal reorderPoint = rs.getBigDecimal("reorder_point");
                    rows.add(new LowStockRow(rs.getString("sku"), rs.getString("name"),
                            reorderPoint, rs.getBigDecimal("reorder_qty"), onHand,
                            reorderPoint.subtract(onHand)));
                }
            }
            return new LowStockReport(rows);
        });
    }

    public SalesByCustomer salesByCustomer(AuthContext actor, String dateFrom,
                                           String dateTo) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            List<SalesByCustomerRow> rows = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT c.code, c.name, COUNT(so.id) AS order_count,
                           COALESCE(SUM(so.total), 0) AS revenue
                      FROM sales_orders so
                      JOIN customers c ON c.id = so.customer_id
                     WHERE so.status NOT IN ('DRAFT','CANCELLED')
                    """);
            List<Object> params = new ArrayList<>();
            appendDateRange(sql, params, dateFrom, dateTo);
            sql.append(" GROUP BY c.id, c.code, c.name ORDER BY revenue DESC");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new SalesByCustomerRow(rs.getString("code"),
                                rs.getString("name"), rs.getLong("order_count"),
                                rs.getBigDecimal("revenue")));
                    }
                }
            }
            return new SalesByCustomer(rows);
        });
    }

    public PurchasesBySupplier purchasesBySupplier(AuthContext actor, String dateFrom,
                                                   String dateTo) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            List<PurchasesBySupplierRow> rows = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT s.code, s.name, COUNT(po.id) AS order_count,
                           COALESCE(SUM(po.total), 0) AS spend
                      FROM purchase_orders po
                      JOIN suppliers s ON s.id = po.supplier_id
                     WHERE po.status NOT IN ('DRAFT','SUBMITTED','CANCELLED')
                    """);
            List<Object> params = new ArrayList<>();
            appendDateRange(sql, params, dateFrom, dateTo);
            sql.append(" GROUP BY s.id, s.code, s.name ORDER BY spend DESC");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new PurchasesBySupplierRow(rs.getString("code"),
                                rs.getString("name"), rs.getLong("order_count"),
                                rs.getBigDecimal("spend")));
                    }
                }
            }
            return new PurchasesBySupplier(rows);
        });
    }

    public MovementSummary movementSummary(AuthContext actor, String dateFrom,
                                           String dateTo) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            List<MovementSummaryRow> rows = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT movement_type, COUNT(*) AS movement_count,
                           COALESCE(SUM(quantity), 0) AS net_quantity
                      FROM stock_movements WHERE 1 = 1
                    """);
            List<Object> params = new ArrayList<>();
            if (dateFrom != null && !dateFrom.isBlank()) {
                sql.append(" AND performed_at >= ?");
                params.add(dateFrom + " 00:00:00.000");
            }
            if (dateTo != null && !dateTo.isBlank()) {
                sql.append(" AND performed_at <= ?");
                params.add(dateTo + " 23:59:59.999");
            }
            sql.append(" GROUP BY movement_type ORDER BY movement_type");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new MovementSummaryRow(rs.getString("movement_type"),
                                rs.getLong("movement_count"),
                                rs.getBigDecimal("net_quantity")));
                    }
                }
            }
            return new MovementSummary(rows);
        });
    }

    /**
     * Gross margin from shipment lines: revenue from SO line pricing,
     * COGS from the recorded unit cost at ship time.
     */
    public GrossMarginReport grossMargin(AuthContext actor, String dateFrom,
                                         String dateTo) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            List<GrossMarginRow> rows = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT p.sku, p.name,
                           COALESCE(SUM(sl.qty_shipped), 0) AS qty_sold,
                           COALESCE(SUM(sl.qty_shipped * sol.unit_price
                               * (1 - sol.discount_pct / 100)), 0) AS revenue,
                           COALESCE(SUM(sl.qty_shipped * sl.unit_cost_at_ship), 0) AS cogs
                      FROM shipment_lines sl
                      JOIN shipments sh ON sh.id = sl.shipment_id
                      JOIN sales_order_lines sol ON sol.id = sl.sales_order_line_id
                      JOIN products p ON p.id = sl.product_id
                     WHERE 1 = 1
                    """);
            List<Object> params = new ArrayList<>();
            if (dateFrom != null && !dateFrom.isBlank()) {
                sql.append(" AND sh.shipped_at >= ?");
                params.add(dateFrom + " 00:00:00.000");
            }
            if (dateTo != null && !dateTo.isBlank()) {
                sql.append(" AND sh.shipped_at <= ?");
                params.add(dateTo + " 23:59:59.999");
            }
            sql.append(" GROUP BY p.id, p.sku, p.name ORDER BY revenue DESC");
            BigDecimal totalRevenue = BigDecimal.ZERO;
            BigDecimal totalCogs = BigDecimal.ZERO;
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BigDecimal revenue = rs.getBigDecimal("revenue")
                                .setScale(2, RoundingMode.HALF_UP);
                        BigDecimal cogs = rs.getBigDecimal("cogs")
                                .setScale(2, RoundingMode.HALF_UP);
                        BigDecimal margin = revenue.subtract(cogs);
                        BigDecimal marginPct = revenue.signum() == 0
                                ? BigDecimal.ZERO
                                : margin.multiply(BigDecimal.valueOf(100))
                                        .divide(revenue, 2, RoundingMode.HALF_UP);
                        rows.add(new GrossMarginRow(rs.getString("sku"),
                                rs.getString("name"), rs.getBigDecimal("qty_sold"),
                                revenue, cogs, margin, marginPct));
                        totalRevenue = totalRevenue.add(revenue);
                        totalCogs = totalCogs.add(cogs);
                    }
                }
            }
            return new GrossMarginReport(rows, totalRevenue, totalCogs,
                    totalRevenue.subtract(totalCogs));
        });
    }

    private void appendDateRange(StringBuilder sql, List<Object> params,
                                 String dateFrom, String dateTo) {
        if (dateFrom != null && !dateFrom.isBlank()) {
            sql.append(" AND order_date >= ?");
            params.add(java.sql.Date.valueOf(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sql.append(" AND order_date <= ?");
            params.add(java.sql.Date.valueOf(dateTo));
        }
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    // ---- reorder proposals ----

    /**
     * Products at or below their reorder point, each with the suggested
     * replenishment quantity and the cheapest preferred supplier. Rows
     * without a supplier catalog entry or without a computable quantity
     * are counted separately so operators know what needs master data.
     */
    public ReorderReport reorderProposals(AuthContext actor) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            List<ReorderRow> rows = new ArrayList<>();
            int noSupplier = 0;
            int noQty = 0;
            String sql = """
                    SELECT product_id, sku, name, unit_of_measure, reorder_point,
                           reorder_qty, total_available, suggested_qty, supplier_id,
                           supplier_code, supplier_name, unit_cost, lead_time_days
                      FROM v_reorder_proposals
                     ORDER BY supplier_code, sku
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long supplierId = rs.getLong("supplier_id");
                    boolean hasSupplier = !rs.wasNull();
                    BigDecimal suggested = rs.getBigDecimal("suggested_qty");
                    if (!hasSupplier) {
                        noSupplier++;
                    } else if (suggested == null || suggested.signum() <= 0) {
                        noQty++;
                    } else {
                        rows.add(new ReorderRow(rs.getString("sku"),
                                rs.getString("name"),
                                rs.getString("unit_of_measure"),
                                rs.getBigDecimal("reorder_point"),
                                rs.getBigDecimal("reorder_qty"),
                                rs.getBigDecimal("total_available"), suggested,
                                hasSupplier ? supplierId : null,
                                rs.getString("supplier_code"),
                                rs.getString("supplier_name"),
                                rs.getBigDecimal("unit_cost"),
                                rs.getObject("lead_time_days") == null ? null
                                        : rs.getInt("lead_time_days")));
                    }
                }
            }
            return new ReorderReport(rows, noSupplier, noQty);
        });
    }

    // ---- dashboard ----

    /** One-call KPI snapshot: master data, stock health, open documents. */
    public Dashboard dashboard(AuthContext actor) {
        actor.require(Permission.REPORT_READ);
        return tm.withConnection(conn -> {
            long activeProducts = scalar(conn, "SELECT COUNT(*) FROM products WHERE is_active = 1");
            long activeWarehouses = scalar(conn, "SELECT COUNT(*) FROM warehouses WHERE is_active = 1");
            long activeSuppliers = scalar(conn, "SELECT COUNT(*) FROM suppliers WHERE is_active = 1");
            long totalCustomers = scalar(conn, "SELECT COUNT(*) FROM customers");
            BigDecimal stockValue = decimal(conn,
                    "SELECT COALESCE(SUM(qty_on_hand * avg_unit_cost), 0) FROM stock_items");
            long lowStockLines = scalar(conn,
                    "SELECT COUNT(*) FROM v_low_stock");
            long outOfStockLines = scalar(conn,
                    "SELECT COUNT(*) FROM v_stock_levels WHERE qty_on_hand = 0");
            long inTransitTransfers = scalar(conn,
                    "SELECT COUNT(*) FROM stock_transfers WHERE status = 'DISPATCHED'");
            long draftPurchaseOrders = scalar(conn,
                    "SELECT COUNT(*) FROM purchase_orders WHERE status = 'DRAFT'");
            long openPurchaseOrders = scalar(conn,
                    "SELECT COUNT(*) FROM purchase_orders WHERE status IN ('SUBMITTED','APPROVED')");
            long draftSalesOrders = scalar(conn,
                    "SELECT COUNT(*) FROM sales_orders WHERE status = 'DRAFT'");
            long confirmedSalesOrders = scalar(conn,
                    "SELECT COUNT(*) FROM sales_orders WHERE status = 'CONFIRMED'");
            long activeStocktakes = scalar(conn,
                    "SELECT COUNT(*) FROM stocktakes WHERE status IN ('DRAFT','OPEN','REVIEW')");
            long pendingWebhookDeliveries = scalar(conn,
                    "SELECT COUNT(*) FROM webhook_deliveries WHERE status = 'PENDING'");

            List<RecentMovement> recent = new ArrayList<>();
            String sql = """
                    SELECT m.movement_no, p.sku, w.code AS warehouse_code,
                           m.movement_type, m.quantity, m.ref_type, m.ref_no,
                           m.performed_at
                      FROM stock_movements m
                      JOIN products p ON p.id = m.product_id
                      JOIN warehouses w ON w.id = m.warehouse_id
                     ORDER BY m.id DESC
                     LIMIT 10
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    recent.add(new RecentMovement(rs.getString("movement_no"),
                            rs.getString("sku"), rs.getString("warehouse_code"),
                            rs.getString("movement_type"), rs.getBigDecimal("quantity"),
                            rs.getString("ref_type"), rs.getString("ref_no"),
                            rs.getTimestamp("performed_at").toInstant().toString()));
                }
            }
            return new Dashboard(activeProducts, activeWarehouses, activeSuppliers,
                    totalCustomers, stockValue, lowStockLines, outOfStockLines,
                    inTransitTransfers, draftPurchaseOrders, openPurchaseOrders,
                    draftSalesOrders, confirmedSalesOrders, activeStocktakes,
                    pendingWebhookDeliveries, recent);
        });
    }

    private static long scalar(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static BigDecimal decimal(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }
}
