package com.ims.domain.purchase;

import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Quantities;
import com.ims.common.util.Validators;
import com.ims.db.DocumentNumbers;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiveRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiptLineResponse;
import com.ims.domain.purchase.dto.PurchaseDtos.ReceiptResponse;
import com.ims.domain.stock.StockMovement;
import com.ims.domain.stock.StockService;
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
 * Goods receipt processing: receives quantities against approved PO lines,
 * writes the GRN, increments PO line received quantities, moves stock into
 * the warehouse (updating moving average cost), and advances the PO status.
 * All atomic - a failed receive leaves no trace.
 */
public final class ReceivingService {

    private static final Logger log = LoggerFactory.getLogger(ReceivingService.class);

    private final TransactionManager tm;
    private final PurchaseOrderDao poDao;
    private final ReceiptDao receiptDao;
    private final WarehouseDao warehouseDao;
    private final com.ims.domain.product.ProductDao productDao;
    private final StockService stockService;
    private final AuditLogger audit;
    private final DocumentNumbers documentNumbers;

    public ReceivingService(TransactionManager tm, PurchaseOrderDao poDao,
                            ReceiptDao receiptDao, WarehouseDao warehouseDao,
                            com.ims.domain.product.ProductDao productDao,
                            StockService stockService, AuditLogger audit,
                            DocumentNumbers documentNumbers) {
        this.tm = tm;
        this.poDao = poDao;
        this.receiptDao = receiptDao;
        this.warehouseDao = warehouseDao;
        this.productDao = productDao;
        this.stockService = stockService;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    public ReceiptResponse receive(AuthContext actor, long poId, ReceiveRequest request) {
        actor.require(Permission.PURCHASE_ORDER_WRITE);
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ValidationException("lines", "at least one receipt line required");
        }

        return tm.inTransaction(conn -> {
            PurchaseOrder po = poDao.lockById(conn, poId)
                    .orElseThrow(() -> new NotFoundException("Purchase order", poId));
            if (!po.isReceivable()) {
                throw new IllegalStateTransitionException("Purchase order", poId,
                        po.getStatus(), "receive goods against");
            }
            var warehouse = warehouseDao.findById(conn, po.getWarehouseId())
                    .orElseThrow();
            var existingLines = poDao.findLines(conn, poId);

            Receipt receipt = new Receipt(poId, po.getWarehouseId(), actor.userId(),
                    Validators.optionalText(request.note(), "note", 300));
            receiptDao.insert(conn, documentNumbers, receipt);

            List<ReceiptLineResponse> lineResponses = new ArrayList<>();
            for (var lineRequest : request.lines()) {
                PurchaseOrderLine poLine = existingLines.stream()
                        .filter(l -> l.getId() == lineRequest.poLineId())
                        .findFirst()
                        .orElseThrow(() -> new ValidationException("lines",
                                "line " + lineRequest.poLineId()
                                        + " does not belong to PO " + po.getPoNo()));
                BigDecimal accepted = Quantities.of(Validators.requirePositive(
                        lineRequest.qtyAccepted(), "qtyAccepted"));
                BigDecimal rejected = Quantities.of(lineRequest.qtyRejected() == null
                        ? BigDecimal.ZERO : Validators.requireNonNegative(
                        lineRequest.qtyRejected(), "qtyRejected"));

                BigDecimal outstanding = poLine.qtyOutstanding();
                if (accepted.add(rejected).compareTo(outstanding) > 0) {
                    throw new ValidationException("qtyAccepted",
                            "accepted+rejected (" + accepted.add(rejected)
                                    + ") exceeds outstanding quantity (" + outstanding
                                    + ") for line " + poLine.getLineNo());
                }

                if (accepted.signum() > 0) {
                    stockService.applyStockChange(conn, actor, poLine.getProductId(),
                            warehouse.getId(), accepted, poLine.getUnitCost(), false,
                            StockMovement.PURCHASE_RECEIPT, StockService.REF_RECEIPT,
                            receipt.getId(), receipt.getReceiptNo(), null,
                            "PO " + po.getPoNo());
                }
                poDao.updateLineReceived(conn, poLine.getId(),
                        poLine.getQtyReceived().add(accepted).add(rejected));

                ReceiptLine receiptLine = new ReceiptLine(receipt.getId(),
                        poLine.getId(), poLine.getProductId(), accepted, rejected,
                        poLine.getUnitCost());
                receiptDao.insertLine(conn, receiptLine);
                lineResponses.add(ReceiptLineResponse.from(receiptLine, skuOf(conn,
                        poLine.getProductId())));
            }

            advanceStatus(conn, po);
            audit.logAction(conn, actor, AuditLogs.GOODS_RECEIVED, "RECEIPT",
                    receipt.getId(), receipt.getReceiptNo(), null, null);
            log.info("GRN {} received against PO {} by {} ({} lines)",
                    receipt.getReceiptNo(), po.getPoNo(), actor.username(),
                    lineResponses.size());
            return new ReceiptResponse(receipt.getId(), receipt.getReceiptNo(), poId,
                    po.getPoNo(), warehouse.getId(), warehouse.getCode(),
                    receipt.getReceivedBy(), receipt.getReceivedAt(), receipt.getNote(),
                    lineResponses);
        });
    }

    /** Advance PARTIALLY_RECEIVED / RECEIVED based on line fulfilment. */
    private void advanceStatus(Connection conn, PurchaseOrder po) throws SQLException {
        List<PurchaseOrderLine> lines = poDao.findLines(conn, po.getId());
        boolean fullyReceived = lines.stream()
                .allMatch(l -> l.getQtyReceived().compareTo(l.getQtyOrdered()) >= 0);
        String newStatus = fullyReceived ? PurchaseOrder.STATUS_RECEIVED
                : PurchaseOrder.STATUS_PARTIALLY_RECEIVED;
        String current = poDao.findById(conn, po.getId()).map(PurchaseOrder::getStatus)
                .orElse(po.getStatus());
        if (!newStatus.equals(current)) {
            poDao.updateStatus(conn, po.getId(), current, newStatus);
        }
    }

    public List<ReceiptResponse> receiptsForPo(AuthContext actor, long poId) {
        actor.require(Permission.PURCHASE_ORDER_READ);
        return tm.withConnection(conn -> {
            PurchaseOrder po = poDao.findById(conn, poId)
                    .orElseThrow(() -> new NotFoundException("Purchase order", poId));
            String poNo = po.getPoNo();
            List<ReceiptResponse> result = new ArrayList<>();
            for (Receipt receipt : receiptDao.findByPo(conn, poId)) {
                List<ReceiptLineResponse> lines = receiptDao
                        .findLines(conn, receipt.getId()).stream()
                        .map(l -> ReceiptLineResponse.from(l, skuOf(conn,
                                l.getProductId())))
                        .toList();
                String whCode = warehouseDao.findById(conn, receipt.getWarehouseId())
                        .map(w -> w.getCode()).orElse("?");
                result.add(ReceiptResponse.from(receipt, poNo, whCode, lines));
            }
            return result;
        });
    }

    private String skuOf(Connection conn, long productId) {
        try {
            return productDao.findById(conn, productId).map(p -> p.getSku()).orElse("?");
        } catch (SQLException e) {
            return "?";
        }
    }
}
