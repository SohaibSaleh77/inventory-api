package com.ims.domain.stocktake;

import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Dates;
import com.ims.common.util.PageRequest;
import com.ims.common.util.Quantities;
import com.ims.common.util.Validators;
import com.ims.db.DocumentNumbers;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.product.ProductDao;
import com.ims.domain.stock.StockAdjustment;
import com.ims.domain.stock.StockAdjustmentDao;
import com.ims.domain.stock.StockService;
import com.ims.domain.stocktake.dto.StocktakeDtos.CountRequest;
import com.ims.domain.stocktake.dto.StocktakeDtos.CreateRequest;
import com.ims.domain.stocktake.dto.StocktakeDtos.DetailResponse;
import com.ims.domain.stocktake.dto.StocktakeDtos.LineResponse;
import com.ims.domain.stocktake.dto.StocktakeDtos.PostResult;
import com.ims.domain.stocktake.dto.StocktakeDtos.Response;
import com.ims.domain.warehouse.WarehouseDao;
import com.ims.domain.webhook.WebhookEvents;
import com.ims.domain.webhook.WebhookService;
import com.ims.security.AuthContext;
import com.ims.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Physical inventory (cycle counting) lifecycle.
 *
 * <p>DRAFT freezes the book quantities into a count sheet; OPEN accepts
 * blind counts; REVIEW freezes the counts for variance analysis; POST applies
 * every non-zero variance through the stock adjustment machinery - one
 * adjustment document plus one ledger movement per corrected line - and
 * stamps stock_items.last_counted_at for every counted line. Uncounted
 * lines are left untouched (posting a partial sheet is legal: cycle counts
 * sample the warehouse, they do not have to cover every SKU).
 *
 * <p>Permissions: reads need STOCK_READ, counting and lifecycle transitions
 * need STOCK_WRITE, posting needs STOCK_ADJUST because it creates stock
 * corrections.
 */
public final class StocktakeService {

    private static final Logger log =
            LoggerFactory.getLogger(StocktakeService.class);

    private final TransactionManager tm;
    private final StocktakeDao stocktakeDao;
    private final WarehouseDao warehouseDao;
    private final ProductDao productDao;
    private final StockAdjustmentDao adjustmentDao;
    private final StockService stockService;
    private final WebhookService webhookService;
    private final AuditLogger audit;
    private final DocumentNumbers documentNumbers;

    public StocktakeService(TransactionManager tm, StocktakeDao stocktakeDao,
                            WarehouseDao warehouseDao, ProductDao productDao,
                            StockAdjustmentDao adjustmentDao,
                            StockService stockService,
                            WebhookService webhookService, AuditLogger audit,
                            DocumentNumbers documentNumbers) {
        this.tm = tm;
        this.stocktakeDao = stocktakeDao;
        this.warehouseDao = warehouseDao;
        this.productDao = productDao;
        this.adjustmentDao = adjustmentDao;
        this.stockService = stockService;
        this.webhookService = webhookService;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    // ---- creation ----

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.STOCK_WRITE);
        String warehouseCode = Validators.requireText(
                request.warehouseCode(), "warehouseCode", 20);
        String name = Validators.requireText(request.name(), "name", 200);
        boolean blind = request.blind() != null && request.blind();
        String note = Validators.optionalText(request.note(), "note", 500);

        return tm.inTransaction(conn -> {
            var warehouse = warehouseDao.findByCode(conn,
                            warehouseCode.trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException("warehouseCode",
                            "unknown warehouse code: " + warehouseCode));
            if (!warehouse.isActive()) {
                throw new ValidationException("warehouseCode",
                        "warehouse is inactive: " + warehouse.getCode());
            }
            List<StocktakeDao.CountSheetRow> sheet =
                    stocktakeDao.buildCountSheet(conn, warehouse.getId());
            if (sheet.isEmpty()) {
                throw new ValidationException("warehouseCode",
                        "no active products to count");
            }

            Stocktake stocktake = new Stocktake(null, warehouse.getId(), name,
                    blind, note, actor.userId());
            stocktake.setStocktakeNo(documentNumbers.next(conn,
                    DocumentNumbers.DocType.STOCKTAKE));
            stocktake = stocktakeDao.insert(conn, stocktake);

            List<StocktakeLine> lines = new ArrayList<>(sheet.size());
            for (StocktakeDao.CountSheetRow row : sheet) {
                StocktakeLine line = new StocktakeLine(stocktake.getId(),
                        row.productId(), row.expectedQty());
                lines.add(line);
            }
            stocktakeDao.insertLines(conn, stocktake.getId(), lines);

            audit.logAction(conn, actor, AuditLogs.STOCKTAKE_CREATED, "STOCKTAKE",
                    stocktake.getId(), stocktake.getStocktakeNo(), null,
                    Map.of("warehouse", warehouse.getCode(), "lines", lines.size(),
                            "blind", blind));
            log.info("Stocktake {} created for {} ({} lines) by {}",
                    stocktake.getStocktakeNo(), warehouse.getCode(), lines.size(),
                    actor.username());
            return Response.from(stocktake, warehouse.getCode(), lines.size(), 0, 0);
        });
    }

    // ---- lifecycle ----

    public Response open(AuthContext actor, long id) {
        actor.require(Permission.STOCK_WRITE);
        return tm.inTransaction(conn -> {
            Stocktake stocktake = getForUpdate(conn, id);
            requireStatus(stocktake, Stocktake.STATUS_DRAFT, "open");
            if (!stocktakeDao.updateStatus(conn, id, Stocktake.STATUS_DRAFT,
                    Stocktake.STATUS_OPEN, Dates.now(), null,
                    stocktake.getVersion())) {
                throw new com.ims.common.exception.OptimisticLockException(
                        "Stocktake", id);
            }
            audit.logAction(conn, actor, AuditLogs.STOCKTAKE_OPENED, "STOCKTAKE",
                    id, stocktake.getStocktakeNo(), null, null);
            return summarize(conn, id);
        });
    }

    public LineResponse submitCount(AuthContext actor, long id, CountRequest request) {
        actor.require(Permission.STOCK_WRITE);
        String sku = Validators.requireText(request.productSku(), "productSku", 40);
        BigDecimal countedQty = Quantities.of(Validators.requireNonNegative(
                request.countedQty(), "countedQty"));

        return tm.inTransaction(conn -> {
            Stocktake stocktake = getForUpdate(conn, id);
            requireStatus(stocktake, Stocktake.STATUS_OPEN, "count");
            var product = productDao.findBySku(conn, sku.trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException("productSku",
                            "unknown product SKU: " + sku));

            StocktakeLine line = stocktakeDao
                    .findLine(conn, id, product.getId())
                    .orElseThrow(() -> new ValidationException("productSku",
                            "SKU is not on this count sheet: " + product.getSku()));
            boolean recount = line.getCountedQty() != null;
            line.setCountedQty(countedQty);
            if (!stocktakeDao.recordCount(conn, line, actor.userId())) {
                throw new com.ims.common.exception.OptimisticLockException(
                        "Stocktake line", line.getId());
            }

            audit.logAction(conn, actor, AuditLogs.STOCKTAKE_COUNTED, "STOCKTAKE",
                    id, stocktake.getStocktakeNo(), null,
                    Map.of("sku", product.getSku(), "countedQty", countedQty,
                            "recount", recount));
            StocktakeLine updated = stocktakeDao
                    .findLine(conn, id, product.getId()).orElseThrow();
            boolean hideExpected = stocktake.isBlind();
            return LineResponse.from(updated, hideExpected);
        });
    }

    public Response close(AuthContext actor, long id) {
        actor.require(Permission.STOCK_WRITE);
        return tm.inTransaction(conn -> {
            Stocktake stocktake = getForUpdate(conn, id);
            requireStatus(stocktake, Stocktake.STATUS_OPEN, "close");
            if (!stocktakeDao.updateStatus(conn, id, Stocktake.STATUS_OPEN,
                    Stocktake.STATUS_REVIEW, Dates.now(), null,
                    stocktake.getVersion())) {
                throw new com.ims.common.exception.OptimisticLockException(
                        "Stocktake", id);
            }
            var summary = summarize(conn, id);
            audit.logAction(conn, actor, AuditLogs.STOCKTAKE_CLOSED, "STOCKTAKE",
                    id, stocktake.getStocktakeNo(), null,
                    Map.of("countedLines", summary.countedLines(),
                            "varianceLines", summary.varianceLines()));
            return summary;
        });
    }

    /**
     * Apply the counted variances to stock. Runs entirely in one
     * transaction: adjustment documents, ledger movements, last_counted_at
     * stamps, the STOCKTAKE_POSTED transition, the audit row and the
     * webhook outbox entry all commit together or not at all.
     */
    public PostResult post(AuthContext actor, long id) {
        actor.require(Permission.STOCK_ADJUST);
        return tm.inTransaction(conn -> {
            Stocktake stocktake = getForUpdate(conn, id);
            requireStatus(stocktake, Stocktake.STATUS_REVIEW, "post");
            List<StocktakeLine> lines = stocktakeDao.findLines(conn, id);

            int countedLines = 0;
            int correctedLines = 0;
            int zeroVarianceLines = 0;
            int uncountedLines = 0;

            for (StocktakeLine line : lines) {
                if (line.getCountedQty() == null) {
                    uncountedLines++;
                    continue;
                }
                countedLines++;
                BigDecimal variance = line.variance();
                if (variance.signum() == 0) {
                    zeroVarianceLines++;
                } else {
                    applyVariance(conn, actor, stocktake, line, variance);
                    correctedLines++;
                }
                // every counted line refreshes the cycle-count clock, even
                // the perfectly matching ones
                stocktakeDao.touchStockItems(conn, stocktake.getWarehouseId(),
                        line.getProductId());
            }

            if (!stocktakeDao.updateStatus(conn, id, Stocktake.STATUS_REVIEW,
                    Stocktake.STATUS_POSTED, Dates.now(), actor.userId(),
                    stocktake.getVersion())) {
                throw new com.ims.common.exception.OptimisticLockException(
                        "Stocktake", id);
            }

            PostResult result = new PostResult(id, stocktake.getStocktakeNo(),
                    countedLines, correctedLines, zeroVarianceLines,
                    uncountedLines);
            audit.logAction(conn, actor, AuditLogs.STOCKTAKE_POSTED, "STOCKTAKE",
                    id, stocktake.getStocktakeNo(), null,
                    Map.of("countedLines", countedLines,
                            "correctedLines", correctedLines,
                            "zeroVarianceLines", zeroVarianceLines,
                            "uncountedLines", uncountedLines));
            Map<String, Object> eventData = new java.util.LinkedHashMap<>();
            eventData.put("stocktakeId", id);
            eventData.put("stocktakeNo", stocktake.getStocktakeNo());
            eventData.put("warehouseId", stocktake.getWarehouseId());
            eventData.put("countedLines", countedLines);
            eventData.put("correctedLines", correctedLines);
            webhookService.enqueueEvent(conn, WebhookEvents.STOCKTAKE_POSTED,
                    eventData);
            log.info("Stocktake {} posted by {}: {} counted, {} corrected, "
                            + "{} uncounted",
                    stocktake.getStocktakeNo(), actor.username(), countedLines,
                    correctedLines, uncountedLines);
            return result;
        });
    }

    public Response cancel(AuthContext actor, long id) {
        actor.require(Permission.STOCK_WRITE);
        return tm.inTransaction(conn -> {
            Stocktake stocktake = getForUpdate(conn, id);
            switch (stocktake.getStatus()) {
                case Stocktake.STATUS_DRAFT, Stocktake.STATUS_OPEN,
                     Stocktake.STATUS_REVIEW -> { /* cancellable */ }
                default -> throw new IllegalStateTransitionException("Stocktake",
                        id, stocktake.getStatus(), "cancel");
            }
            if (!stocktakeDao.updateStatus(conn, id, stocktake.getStatus(),
                    Stocktake.STATUS_CANCELLED, Dates.now(), null,
                    stocktake.getVersion())) {
                throw new com.ims.common.exception.OptimisticLockException(
                        "Stocktake", id);
            }
            audit.logAction(conn, actor, AuditLogs.STOCKTAKE_CANCELLED,
                    "STOCKTAKE", id, stocktake.getStocktakeNo(), null,
                    Map.of("fromStatus", stocktake.getStatus()));
            return summarize(conn, id);
        });
    }

    // ---- queries ----

    public DetailResponse get(AuthContext actor, long id) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            Stocktake stocktake = stocktakeDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Stocktake", id));
            return detail(conn, stocktake);
        });
    }

    public PageRequest.Page<Response> list(AuthContext actor, Long warehouseId,
                                           String status, PageRequest page) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            long total = stocktakeDao.countSearch(conn, warehouseId, status);
            List<Stocktake> found = stocktakeDao.search(conn, warehouseId, status,
                    page.size(), page.offset());
            List<Response> content = new ArrayList<>(found.size());
            for (Stocktake stocktake : found) {
                content.add(summarize(conn, stocktake.getId(), stocktake));
            }
            return PageRequest.Page.of(content, page, total);
        });
    }

    /** Count sheet as CSV rows (bounded by the line count of the sheet). */
    public record SheetCsv(List<List<String>> rows, boolean truncated) {
    }

    public static final int CSV_EXPORT_MAX_ROWS = 50_000;

    public SheetCsv exportCsv(AuthContext actor, long id) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            Stocktake stocktake = stocktakeDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Stocktake", id));
            List<StocktakeLine> lines = stocktakeDao.findLines(conn, id);
            boolean truncated = lines.size() > CSV_EXPORT_MAX_ROWS;
            if (truncated) {
                lines = lines.subList(0, CSV_EXPORT_MAX_ROWS);
            }
            boolean hideExpected = stocktake.isBlind()
                    && Stocktake.STATUS_OPEN.equals(stocktake.getStatus());
            List<List<String>> rows = new ArrayList<>(lines.size());
            for (StocktakeLine line : lines) {
                rows.add(List.of(
                        line.getProductSku(),
                        line.getProductName(),
                        hideExpected ? "" : String.valueOf(line.getExpectedQty()),
                        line.getCountedQty() == null
                                ? "" : String.valueOf(line.getCountedQty()),
                        hideExpected || line.variance() == null
                                ? "" : String.valueOf(line.variance()),
                        String.valueOf(line.getRecounts()),
                        line.getCountedAt() == null
                                ? "" : line.getCountedAt().toString()));
            }
            return new SheetCsv(rows, truncated);
        });
    }

    // ---- internals ----

    /** One variance = one adjustment document + one ledger movement. */
    private void applyVariance(Connection conn, AuthContext actor,
                               Stocktake stocktake, StocktakeLine line,
                               BigDecimal variance) throws SQLException {
        String movementType = variance.signum() > 0
                ? com.ims.domain.stock.StockMovement.ADJUSTMENT_IN
                : com.ims.domain.stock.StockMovement.ADJUSTMENT_OUT;

        StockAdjustment adjustment = new StockAdjustment(
                stocktake.getWarehouseId(), line.getProductId(), variance,
                StockAdjustment.REASON_COUNT_CORRECTION,
                "stocktake " + stocktake.getStocktakeNo(),
                actor.userId());
        adjustmentDao.insert(conn, documentNumbers, adjustment);

        stockService.applyStockChange(conn, actor, line.getProductId(),
                stocktake.getWarehouseId(), variance, null, false, movementType,
                StockService.REF_STOCKTAKE, stocktake.getId(),
                stocktake.getStocktakeNo(), StockAdjustment.REASON_COUNT_CORRECTION,
                "stocktake " + stocktake.getStocktakeNo());
    }

    private Stocktake getForUpdate(Connection conn, long id) throws SQLException {
        return stocktakeDao.findById(conn, id)
                .orElseThrow(() -> new NotFoundException("Stocktake", id));
    }

    private static void requireStatus(Stocktake stocktake, String expected,
                                      String action) {
        if (!expected.equals(stocktake.getStatus())) {
            throw new IllegalStateTransitionException("Stocktake",
                    stocktake.getId(), stocktake.getStatus(), action);
        }
    }

    private Response summarize(Connection conn, long id) throws SQLException {
        return summarize(conn, id, null);
    }

    private Response summarize(Connection conn, long id, Stocktake preloaded)
            throws SQLException {
        Stocktake stocktake = preloaded != null ? preloaded
                : stocktakeDao.findById(conn, id)
                        .orElseThrow(() -> new NotFoundException("Stocktake", id));
        String warehouseCode = warehouseDao.findById(conn,
                stocktake.getWarehouseId()).map(w -> w.getCode()).orElse("?");
        StocktakeDao.LineStats stats = stocktakeDao.lineStats(conn, id);
        return Response.from(stocktake, warehouseCode, stats.lineCount(),
                stats.countedLines(), stats.varianceLines());
    }

    private DetailResponse detail(Connection conn, Stocktake stocktake)
            throws SQLException {
        String warehouseCode = warehouseDao.findById(conn,
                stocktake.getWarehouseId()).map(w -> w.getCode()).orElse("?");
        List<StocktakeLine> lines = stocktakeDao.findLines(conn,
                stocktake.getId());
        // blind sheets hide book quantities from counters while OPEN
        boolean hideExpected = stocktake.isBlind()
                && Stocktake.STATUS_OPEN.equals(stocktake.getStatus());
        List<LineResponse> responses = lines.stream()
                .map(line -> LineResponse.from(line, hideExpected))
                .toList();
        DetailResponse response = DetailResponse.from(stocktake, warehouseCode,
                responses);
        return hideExpected
                ? new DetailResponse(response.id(), response.stocktakeNo(),
                        response.warehouseId(), response.warehouseCode(),
                        response.name(), response.blind(), response.status(),
                        response.note(), response.lineCount(),
                        response.countedLines(), 0, response.openedAt(),
                        response.closedAt(), response.postedAt(),
                        response.createdAt(), response.lines())
                : response;
    }
}
