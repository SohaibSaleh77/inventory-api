package com.ims.domain.stock;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.PageRequest;
import com.ims.common.util.Quantities;
import com.ims.common.util.Validators;
import com.ims.db.DocumentNumbers;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.product.ProductDao;
import com.ims.domain.stock.dto.StockDtos.AdjustmentResponse;
import com.ims.domain.stock.dto.StockDtos.AdjustRequest;
import com.ims.domain.stock.dto.StockDtos.MovementResponse;
import com.ims.domain.stock.dto.StockDtos.StockLevelResponse;
import com.ims.domain.warehouse.WarehouseDao;
import com.ims.security.AuthContext;
import com.ims.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stock queries and adjustments (reason-coded corrections).
 * The adjustment document and the ledger movement are written atomically.
 */
public final class StockQueryService {

    private static final Logger log = LoggerFactory.getLogger(StockQueryService.class);

    private final TransactionManager tm;
    private final StockItemDao stockItemDao;
    private final StockMovementDao movementDao;
    private final StockAdjustmentDao adjustmentDao;
    private final StockService stockService;
    private final ProductDao productDao;
    private final WarehouseDao warehouseDao;
    private final AuditLogger audit;
    private final DocumentNumbers documentNumbers;

    public StockQueryService(TransactionManager tm, StockItemDao stockItemDao,
                             StockMovementDao movementDao,
                             StockAdjustmentDao adjustmentDao, StockService stockService,
                             ProductDao productDao, WarehouseDao warehouseDao,
                             AuditLogger audit, DocumentNumbers documentNumbers) {
        this.tm = tm;
        this.stockItemDao = stockItemDao;
        this.movementDao = movementDao;
        this.adjustmentDao = adjustmentDao;
        this.stockService = stockService;
        this.productDao = productDao;
        this.warehouseDao = warehouseDao;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    // ---- queries ----

    public PageRequest.Page<StockLevelResponse> stockLevels(AuthContext actor,
            String skuLike, Long warehouseId, Boolean lowOnly, PageRequest page) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            var views = stockItemDao.searchLevels(conn, skuLike, warehouseId, lowOnly,
                    page.size(), page.offset());
            long total = stockItemDao.countLevels(conn, skuLike, warehouseId, lowOnly);
            return PageRequest.Page.of(views.stream()
                    .map(v -> new StockLevelResponse(v.stockItemId(), v.productId(),
                            v.sku(), v.productName(), v.warehouseId(), v.warehouseCode(),
                            v.warehouseName(), v.qtyOnHand(), v.qtyReserved(),
                            v.qtyAvailable(), v.avgUnitCost(), v.stockValue()))
                    .toList(), page, total);
        });
    }

    public PageRequest.Page<MovementResponse> movements(AuthContext actor,
            Long productId, Long warehouseId, String movementType,
            String refType, Long refId, String from, String to, PageRequest page) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            StockMovementDao.MovementQuery query = new StockMovementDao.MovementQuery(
                    productId, warehouseId, movementType, refType, refId, from, to);
            long total = movementDao.countSearch(conn, query);
            List<StockMovement> found = movementDao.search(conn, query, page.size(),
                    page.offset());
            return PageRequest.Page.of(annotate(conn, found), page, total);
        });
    }

    /**
     * Movements as CSV rows (bounded export; a hard cap protects the heap).
     * Returns the rows plus whether the cap truncated the result.
     */
    public record MovementsCsv(List<List<String>> rows, boolean truncated) {
    }

    public static final int CSV_EXPORT_MAX_ROWS = 50_000;

    public MovementsCsv movementsCsv(AuthContext actor, Long productId, Long warehouseId,
                                     String movementType, String refType, Long refId,
                                     String from, String to) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            StockMovementDao.MovementQuery query = new StockMovementDao.MovementQuery(
                    productId, warehouseId, movementType, refType, refId, from, to);
            List<StockMovement> found = movementDao.search(conn, query,
                    CSV_EXPORT_MAX_ROWS + 1, 0);
            boolean truncated = found.size() > CSV_EXPORT_MAX_ROWS;
            if (truncated) {
                found = found.subList(0, CSV_EXPORT_MAX_ROWS);
                log.warn("CSV movement export truncated at {} rows - apply filters",
                        CSV_EXPORT_MAX_ROWS);
            }
            var products = productDao.findByIds(conn,
                    found.stream().map(StockMovement::getProductId).toList());
            var warehouses = warehouseDao.findByIds(conn,
                    found.stream().map(StockMovement::getWarehouseId).toList());
            List<List<String>> rows = new ArrayList<>(found.size());
            for (StockMovement m : found) {
                String sku = products.containsKey(m.getProductId())
                        ? products.get(m.getProductId()).getSku() : "?";
                String whCode = warehouses.containsKey(m.getWarehouseId())
                        ? warehouses.get(m.getWarehouseId()).getCode() : "?";
                rows.add(List.of(
                        m.getMovementNo() == null ? "" : m.getMovementNo(),
                        sku, whCode,
                        String.valueOf(m.getQuantity()),
                        String.valueOf(m.getBalanceAfter()),
                        String.valueOf(m.getUnitCost()),
                        m.getMovementType() == null ? "" : m.getMovementType(),
                        m.getRefType() == null ? "" : m.getRefType(),
                        m.getRefNo() == null ? "" : m.getRefNo(),
                        m.getReason() == null ? "" : m.getReason(),
                        m.getPerformedAt() == null ? "" : m.getPerformedAt().toString()));
            }
            return new MovementsCsv(rows, truncated);
        });
    }

    /** Attach sku + warehouse code to movements without per-row queries. */
    private List<MovementResponse> annotate(Connection conn, List<StockMovement> found)
            throws SQLException {
        var products = productDao.findByIds(conn,
                found.stream().map(StockMovement::getProductId).toList());
        var warehouses = warehouseDao.findByIds(conn,
                found.stream().map(StockMovement::getWarehouseId).toList());
        List<MovementResponse> content = new ArrayList<>(found.size());
        for (StockMovement m : found) {
            String sku = products.containsKey(m.getProductId())
                    ? products.get(m.getProductId()).getSku() : "?";
            String whCode = warehouses.containsKey(m.getWarehouseId())
                    ? warehouses.get(m.getWarehouseId()).getCode() : "?";
            content.add(MovementResponse.from(m, sku, whCode));
        }
        return content;
    }

    public PageRequest.Page<AdjustmentResponse> adjustments(AuthContext actor,
            Long productId, Long warehouseId, String reason, PageRequest page) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            // totalElements was previously synthesised from the page itself
            // (content.size + offset) which made every page beyond the first
            // report a wrong total - now a real COUNT with the same filters.
            long total = adjustmentDao.countSearch(conn, productId, warehouseId, reason);
            List<StockAdjustment> found = adjustmentDao.search(conn, productId,
                    warehouseId, reason, page.size(), page.offset());
            var products = productDao.findByIds(conn,
                    found.stream().map(StockAdjustment::getProductId).toList());
            var warehouses = warehouseDao.findByIds(conn,
                    found.stream().map(StockAdjustment::getWarehouseId).toList());
            List<AdjustmentResponse> content = new ArrayList<>(found.size());
            for (StockAdjustment a : found) {
                String sku = products.containsKey(a.getProductId())
                        ? products.get(a.getProductId()).getSku() : "?";
                String whCode = warehouses.containsKey(a.getWarehouseId())
                        ? warehouses.get(a.getWarehouseId()).getCode() : "?";
                content.add(AdjustmentResponse.from(a, sku, whCode));
            }
            return PageRequest.Page.of(content, page, total);
        });
    }

    /**
     * Stock levels as CSV rows (bounded export with the same filters as
     * the levels endpoint).
     */
    public record LevelsCsv(List<List<String>> rows, boolean truncated) {
    }

    public LevelsCsv stockLevelsCsv(AuthContext actor, String skuLike, Long warehouseId,
                                    Boolean lowOnly) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            var views = stockItemDao.searchLevels(conn, skuLike, warehouseId, lowOnly,
                    CSV_EXPORT_MAX_ROWS + 1, 0);
            boolean truncated = views.size() > CSV_EXPORT_MAX_ROWS;
            if (truncated) {
                views = views.subList(0, CSV_EXPORT_MAX_ROWS);
                log.warn("CSV levels export truncated at {} rows - apply filters",
                        CSV_EXPORT_MAX_ROWS);
            }
            List<List<String>> rows = new ArrayList<>(views.size());
            for (var v : views) {
                rows.add(List.of(
                        v.sku(), v.productName(), v.warehouseCode(), v.warehouseName(),
                        String.valueOf(v.qtyOnHand()), String.valueOf(v.qtyReserved()),
                        String.valueOf(v.qtyAvailable()), String.valueOf(v.avgUnitCost()),
                        String.valueOf(v.stockValue())));
            }
            return new LevelsCsv(rows, truncated);
        });
    }

    /**
     * Apply a stock adjustment: locks the stock row, applies the delta with
     * full invariant checks, writes the adjustment document and the ledger
     * entry in one transaction.
     */
    public AdjustmentResponse adjust(AuthContext actor, AdjustRequest request) {
        actor.require(Permission.STOCK_ADJUST);
        String sku = Validators.requireText(request.productSku(), "productSku", 40);
        String warehouseCode = Validators.requireText(
                request.warehouseCode(), "warehouseCode", 20);
        BigDecimal delta = Quantities.of(request.quantityDelta());
        if (delta.signum() == 0) {
            throw new ValidationException("quantityDelta", "must not be zero");
        }
        String reason = normalizeReason(request.reason());

        return tm.inTransaction(conn -> {
            var product = productDao.findBySku(conn, sku.trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException("productSku",
                            "unknown product SKU: " + sku));
            var warehouse = warehouseDao.findByCode(conn, warehouseCode.trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException("warehouseCode",
                            "unknown warehouse code: " + warehouseCode));
            if (!product.isActive()) {
                throw new ValidationException("productSku",
                        "product is inactive: " + product.getSku());
            }
            if (!warehouse.isActive()) {
                throw new ValidationException("warehouseCode",
                        "warehouse is inactive: " + warehouse.getCode());
            }

            String movementType = delta.signum() > 0
                    ? StockMovement.ADJUSTMENT_IN : StockMovement.ADJUSTMENT_OUT;

            StockAdjustment adjustment = new StockAdjustment(warehouse.getId(),
                    product.getId(), delta, reason,
                    Validators.optionalText(request.note(), "note", 300),
                    actor.userId());
            adjustmentDao.insert(conn, documentNumbers, adjustment);

            StockService.AppliedStockChange applied = stockService.applyStockChange(
                    conn, actor, product.getId(), warehouse.getId(), delta,
                    null, false, movementType, StockService.REF_ADJUSTMENT,
                    adjustment.getId(), adjustment.getAdjustmentNo(), reason,
                    request.note());
            // Link ledger row to the adjustment document
            audit.logAction(conn, actor, AuditLogs.STOCK_ADJUSTED, "ADJUSTMENT",
                    adjustment.getId(), adjustment.getAdjustmentNo(), null,
                    java.util.Map.of("sku", product.getSku(),
                            "warehouse", warehouse.getCode(),
                            "delta", delta,
                            "balanceAfter", applied.stockItem().getQtyOnHand()));
            log.info("Stock adjusted: {} {} in {} by {} -> on hand {}",
                    delta, product.getSku(), warehouse.getCode(), actor.username(),
                    applied.stockItem().getQtyOnHand());
            return AdjustmentResponse.from(adjustment, product.getSku(),
                    warehouse.getCode());
        });
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("reason", "is required");
        }
        String value = reason.trim().toUpperCase();
        if (!StockAdjustment.allowedReasons().contains(value)) {
            throw new ValidationException("reason",
                    "must be one of " + StockAdjustment.allowedReasons());
        }
        return value;
    }

    /** Used by reports/tests: current on-hand across all warehouses. */
    public BigDecimal totalOnHand(AuthContext actor, long productId) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> stockItemDao
                .findNonZeroForProduct(conn, productId).stream()
                .map(StockItem::getQtyOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
