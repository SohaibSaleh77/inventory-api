package com.ims.domain.report.dto;

import java.math.BigDecimal;
import java.util.List;

/** DTO records for reports. */
public final class ReportDtos {

    private ReportDtos() {
    }

    public record StockSummaryRow(String sku, String productName, String unit,
                                  long warehousesWithStock, BigDecimal totalOnHand,
                                  BigDecimal totalReserved, BigDecimal totalAvailable,
                                  BigDecimal reorderPoint, boolean belowReorderPoint) {
    }

    public record StockSummary(List<StockSummaryRow> rows, int productCount,
                               BigDecimal totalValue) {
    }

    public record StockValuationRow(String warehouseCode, String warehouseName,
                                    long productCount, BigDecimal qtyOnHand,
                                    BigDecimal stockValue) {
    }

    public record StockValuation(List<StockValuationRow> rows, BigDecimal grandTotal) {
    }

    public record LowStockRow(String sku, String productName, BigDecimal reorderPoint,
                              BigDecimal reorderQty, BigDecimal totalOnHand,
                              BigDecimal shortfall) {
    }

    public record LowStockReport(List<LowStockRow> rows) {
    }

    public record SalesByCustomerRow(String customerCode, String customerName,
                                     long orderCount, BigDecimal revenue) {
    }

    public record SalesByCustomer(List<SalesByCustomerRow> rows) {
    }

    public record PurchasesBySupplierRow(String supplierCode, String supplierName,
                                         long orderCount, BigDecimal spend) {
    }

    public record PurchasesBySupplier(List<PurchasesBySupplierRow> rows) {
    }

    public record MovementSummaryRow(String movementType, long movementCount,
                                     BigDecimal netQuantity) {
    }

    public record MovementSummary(List<MovementSummaryRow> rows) {
    }

    public record GrossMarginRow(String sku, String productName, BigDecimal qtySold,
                                 BigDecimal revenue, BigDecimal cogs,
                                 BigDecimal grossMargin,
                                 BigDecimal marginPct) {
    }

    public record GrossMarginReport(List<GrossMarginRow> rows, BigDecimal totalRevenue,
                                    BigDecimal totalCogs, BigDecimal totalMargin) {
    }

    // ---- reorder & dashboard ----

    public record ReorderRow(String sku, String productName, String unit,
                             BigDecimal reorderPoint, BigDecimal reorderQty,
                             BigDecimal totalAvailable, BigDecimal suggestedQty,
                             Long supplierId, String supplierCode,
                             String supplierName, BigDecimal unitCost,
                             Integer leadTimeDays) {
    }

    public record ReorderReport(List<ReorderRow> rows, int skippedNoSupplier,
                                int skippedNoQty) {
    }

    public record RecentMovement(String movementNo, String sku, String warehouseCode,
                                 String movementType, BigDecimal quantity,
                                 String refType, String refNo,
                                 String performedAt) {
    }

    /** One-call operational snapshot for dashboards. */
    public record Dashboard(long activeProducts, long activeWarehouses,
                            long activeSuppliers, long totalCustomers,
                            BigDecimal stockValue, long lowStockLines,
                            long outOfStockLines, long inTransitTransfers,
                            long draftPurchaseOrders, long openPurchaseOrders,
                            long draftSalesOrders, long confirmedSalesOrders,
                            long activeStocktakes, long pendingWebhookDeliveries,
                            List<RecentMovement> recentMovements) {
    }
}
