package com.ims.domain.transfer;

import com.ims.common.exception.IllegalStateTransitionException;
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
import com.ims.domain.stock.StockItemDao;
import com.ims.domain.stock.StockMovement;
import com.ims.domain.stock.StockService;
import com.ims.domain.transfer.dto.TransferDtos.CreateRequest;
import com.ims.domain.transfer.dto.TransferDtos.Response;
import com.ims.domain.warehouse.WarehouseDao;
import com.ims.security.AuthContext;
import com.ims.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Two-phase inter-warehouse transfers:
 * create (PENDING) -&gt; dispatch (deducts source, TRANSFER_OUT) -&gt; receive
 * (adds to destination, TRANSFER_IN). Cancellation is only possible while
 * PENDING (stock has not left the source yet).
 */
public final class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransactionManager tm;
    private final StockTransferDao transferDao;
    private final ProductDao productDao;
    private final WarehouseDao warehouseDao;
    private final StockItemDao stockItemDao;
    private final StockService stockService;
    private final AuditLogger audit;
    private final DocumentNumbers documentNumbers;

    public TransferService(TransactionManager tm, StockTransferDao transferDao,
                           ProductDao productDao, WarehouseDao warehouseDao,
                           StockItemDao stockItemDao, StockService stockService,
                           AuditLogger audit, DocumentNumbers documentNumbers) {
        this.tm = tm;
        this.transferDao = transferDao;
        this.productDao = productDao;
        this.warehouseDao = warehouseDao;
        this.stockItemDao = stockItemDao;
        this.stockService = stockService;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.STOCK_TRANSFER);
        String sku = Validators.requireText(request.productSku(), "productSku", 40);
        var quantity = Quantities.of(Validators.requirePositive(
                request.quantity(), "quantity"));

        return tm.inTransaction(conn -> {
            var product = productDao.findBySku(conn, sku.trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException("productSku",
                            "unknown product SKU: " + sku));
            var from = warehouseDao.findByCode(conn, request.fromWarehouseCode()
                            .trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException("fromWarehouseCode",
                            "unknown warehouse: " + request.fromWarehouseCode()));
            var to = warehouseDao.findByCode(conn, request.toWarehouseCode()
                            .trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException("toWarehouseCode",
                            "unknown warehouse: " + request.toWarehouseCode()));
            if (from.getId().equals(to.getId())) {
                throw new ValidationException("toWarehouseCode",
                        "source and destination must differ");
            }
            if (!product.isActive()) {
                throw new ValidationException("productSku",
                        "product is inactive: " + product.getSku());
            }

            StockTransfer transfer = new StockTransfer(product.getId(), from.getId(),
                    to.getId(), quantity,
                    Validators.optionalText(request.note(), "note", 300),
                    actor.userId());
            transferDao.insert(conn, documentNumbers, transfer);
            audit.logAction(conn, actor, AuditLogs.TRANSFER_CREATED, "TRANSFER",
                    transfer.getId(), transfer.getTransferNo(), null,
                    Response.from(transfer, product.getSku(), from.getCode(),
                            to.getCode()));
            return Response.from(transfer, product.getSku(), from.getCode(),
                    to.getCode());
        });
    }

    /** Dispatch: stock physically leaves the source warehouse. */
    public Response dispatch(AuthContext actor, long id) {
        actor.require(Permission.STOCK_TRANSFER);
        return tm.inTransaction(conn -> {
            StockTransfer transfer = transferDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Transfer", id));
            if (!StockTransfer.STATUS_PENDING.equals(transfer.getStatus())) {
                throw new IllegalStateTransitionException("Transfer", id,
                        transfer.getStatus(), "dispatch");
            }
            var product = productDao.findById(conn, transfer.getProductId())
                    .orElseThrow();
            var from = warehouseDao.findById(conn, transfer.getFromWarehouseId())
                    .orElseThrow();
            var to = warehouseDao.findById(conn, transfer.getToWarehouseId()).orElseThrow();

            stockService.applyStockChange(conn, actor, transfer.getProductId(),
                    transfer.getFromWarehouseId(), transfer.getQuantity().negate(),
                    null, false, StockMovement.TRANSFER_OUT,
                    StockService.REF_TRANSFER, transfer.getId(),
                    transfer.getTransferNo(), null, transfer.getNote());

            if (!transferDao.markDispatched(conn, id, actor.userId())) {
                throw new IllegalStateTransitionException("Transfer", id,
                        "PENDING", "dispatch");
            }
            StockTransfer updated = transferDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.TRANSFER_DISPATCHED, "TRANSFER",
                    id, transfer.getTransferNo(), null,
                    Response.from(updated, product.getSku(), from.getCode(),
                            to.getCode()));
            log.info("Transfer {} dispatched ({} x {} from {})",
                    transfer.getTransferNo(), transfer.getQuantity(), product.getSku(),
                    from.getCode());
            return Response.from(updated, product.getSku(), from.getCode(),
                    to.getCode());
        });
    }

    /** Receive: stock arrives at the destination warehouse. */
    public Response receive(AuthContext actor, long id) {
        actor.require(Permission.STOCK_TRANSFER);
        return tm.inTransaction(conn -> {
            StockTransfer transfer = transferDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Transfer", id));
            if (!StockTransfer.STATUS_DISPATCHED.equals(transfer.getStatus())) {
                throw new IllegalStateTransitionException("Transfer", id,
                        transfer.getStatus(), "receive");
            }
            var product = productDao.findById(conn, transfer.getProductId())
                    .orElseThrow();
            var from = warehouseDao.findById(conn, transfer.getFromWarehouseId())
                    .orElseThrow();
            var to = warehouseDao.findById(conn, transfer.getToWarehouseId()).orElseThrow();

            // Preserve the moving average cost from the source warehouse
            var sourceStock = stockItemDao.findByProductAndWarehouse(conn,
                    transfer.getProductId(), transfer.getFromWarehouseId());
            java.math.BigDecimal carriedCost = sourceStock
                    .map(s -> s.getAvgUnitCost()).orElse(java.math.BigDecimal.ZERO);

            stockService.applyStockChange(conn, actor, transfer.getProductId(),
                    transfer.getToWarehouseId(), transfer.getQuantity(),
                    carriedCost, false, StockMovement.TRANSFER_IN,
                    StockService.REF_TRANSFER, transfer.getId(),
                    transfer.getTransferNo(), null, transfer.getNote());

            if (!transferDao.markReceived(conn, id, actor.userId())) {
                throw new IllegalStateTransitionException("Transfer", id,
                        "DISPATCHED", "receive");
            }
            StockTransfer updated = transferDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.TRANSFER_RECEIVED, "TRANSFER",
                    id, transfer.getTransferNo(), null,
                    Response.from(updated, product.getSku(), from.getCode(),
                            to.getCode()));
            log.info("Transfer {} received at {}", transfer.getTransferNo(),
                    to.getCode());
            return Response.from(updated, product.getSku(), from.getCode(),
                    to.getCode());
        });
    }

    public Response cancel(AuthContext actor, long id) {
        actor.require(Permission.STOCK_TRANSFER);
        return tm.inTransaction(conn -> {
            StockTransfer transfer = transferDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Transfer", id));
            if (!StockTransfer.STATUS_PENDING.equals(transfer.getStatus())) {
                throw new IllegalStateTransitionException("Transfer", id,
                        transfer.getStatus(), "cancel");
            }
            var product = productDao.findById(conn, transfer.getProductId())
                    .orElseThrow();
            var from = warehouseDao.findById(conn, transfer.getFromWarehouseId())
                    .orElseThrow();
            var to = warehouseDao.findById(conn, transfer.getToWarehouseId()).orElseThrow();
            if (!transferDao.markCancelled(conn, id)) {
                throw new IllegalStateTransitionException("Transfer", id,
                        "PENDING", "cancel");
            }
            StockTransfer updated = transferDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.TRANSFER_CANCELLED, "TRANSFER",
                    id, transfer.getTransferNo(), null,
                    Response.from(updated, product.getSku(), from.getCode(),
                            to.getCode()));
            return Response.from(updated, product.getSku(), from.getCode(),
                    to.getCode());
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            StockTransfer transfer = transferDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Transfer", id));
            return Response.from(transfer, skuOf(conn, transfer.getProductId()),
                    codeOf(conn, transfer.getFromWarehouseId()),
                    codeOf(conn, transfer.getToWarehouseId()));
        });
    }

    public PageRequest.Page<Response> search(AuthContext actor, String status,
                                             Long productId, PageRequest page) {
        actor.require(Permission.STOCK_READ);
        return tm.withConnection(conn -> {
            long total = transferDao.countSearch(conn, status, productId);
            return PageRequest.Page.of(transferDao
                    .search(conn, status, productId, page.size(), page.offset())
                    .stream()
                    .map(t -> Response.from(t, skuOf(conn, t.getProductId()),
                            codeOf(conn, t.getFromWarehouseId()),
                            codeOf(conn, t.getToWarehouseId())))
                    .toList(), page, total);
        });
    }

    private String skuOf(java.sql.Connection conn, long productId) {
        try {
            return productDao.findById(conn, productId).map(p -> p.getSku())
                    .orElse("?");
        } catch (java.sql.SQLException e) {
            return "?";
        }
    }

    private String codeOf(java.sql.Connection conn, long warehouseId) {
        try {
            return warehouseDao.findById(conn, warehouseId).map(w -> w.getCode())
                    .orElse("?");
        } catch (java.sql.SQLException e) {
            return "?";
        }
    }
}
