package com.ims.domain.sales;

import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Money;
import com.ims.common.util.Quantities;
import com.ims.common.util.Validators;
import com.ims.db.DocumentNumbers;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.product.ProductDao;
import com.ims.domain.sales.dto.SalesDtos.ReturnLineResponse;
import com.ims.domain.sales.dto.SalesDtos.ReturnRequest;
import com.ims.domain.sales.dto.SalesDtos.ReturnResponse;
import com.ims.domain.stock.StockMovement;
import com.ims.domain.stock.StockService;
import com.ims.domain.warehouse.WarehouseDao;
import com.ims.security.AuthContext;
import com.ims.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Customer returns: accepts returned goods against shipped SO lines, restocks
 * them (SALE_RETURN movement) unless flagged non-restockable, and records the
 * credit amount. Guard: cumulative returns per line can never exceed the
 * shipped quantity of that line.
 */
public final class SalesReturnService {

    private static final Logger log = LoggerFactory.getLogger(SalesReturnService.class);

    private final TransactionManager tm;
    private final SalesOrderDao soDao;
    private final SalesReturnDao returnDao;
    private final ShipmentDao shipmentDao;
    private final WarehouseDao warehouseDao;
    private final ProductDao productDao;
    private final StockService stockService;
    private final AuditLogger audit;
    private final DocumentNumbers documentNumbers;

    public SalesReturnService(TransactionManager tm, SalesOrderDao soDao,
                              SalesReturnDao returnDao, ShipmentDao shipmentDao,
                              WarehouseDao warehouseDao, ProductDao productDao,
                              StockService stockService, AuditLogger audit,
                              DocumentNumbers documentNumbers) {
        this.tm = tm;
        this.soDao = soDao;
        this.returnDao = returnDao;
        this.shipmentDao = shipmentDao;
        this.warehouseDao = warehouseDao;
        this.productDao = productDao;
        this.stockService = stockService;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    public ReturnResponse receiveReturn(AuthContext actor, long soId,
                                        ReturnRequest request) {
        actor.require(Permission.SALES_ORDER_FULFILL);
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ValidationException("lines", "at least one return line required");
        }
        String reason = normalizeReason(request.reason());

        return tm.inTransaction(conn -> {
            SalesOrder so = soDao.lockById(conn, soId)
                    .orElseThrow(() -> new NotFoundException("Sales order", soId));
            boolean shipped = soDao.findLines(conn, soId).stream()
                    .anyMatch(l -> l.getQtyFulfilled().signum() > 0);
            if (!shipped) {
                throw new IllegalStateTransitionException("Sales order", soId,
                        so.getStatus(), "accept returns against");
            }
            var warehouse = warehouseDao.findById(conn, so.getWarehouseId())
                    .orElseThrow();

            SalesReturn salesReturn = new SalesReturn(soId, so.getWarehouseId(),
                    actor.userId(), reason,
                    Validators.optionalText(request.note(), "note", 300));
            returnDao.insert(conn, documentNumbers, salesReturn);

            List<ReturnLineResponse> lineResponses = new ArrayList<>();
            for (var lineRequest : request.lines()) {
                SalesOrderLine soLine = soDao.findLines(conn, soId).stream()
                        .filter(l -> l.getId() == lineRequest.soLineId())
                        .findFirst()
                        .orElseThrow(() -> new ValidationException("lines",
                                "line " + lineRequest.soLineId() + " does not belong to "
                                        + so.getSoNo()));
                BigDecimal qty = Quantities.of(Validators.requirePositive(
                        lineRequest.qty(), "qty"));

                BigDecimal alreadyReturned = returnDao.totalReturnedForLine(conn,
                        soLine.getId());
                BigDecimal shippedQty = totalShippedForLine(conn, soLine.getId());
                if (alreadyReturned.add(qty).compareTo(shippedQty) > 0) {
                    throw new ValidationException("qty",
                            "return would exceed shipped quantity for line "
                                    + soLine.getLineNo() + " (shipped "
                                    + shippedQty.toPlainString() + ", already returned "
                                    + alreadyReturned.toPlainString() + ")");
                }

                if (lineRequest.restock()) {
                    stockService.applyStockChange(conn, actor, soLine.getProductId(),
                            so.getWarehouseId(), qty, null, false,
                            StockMovement.SALE_RETURN, StockService.REF_RETURN,
                            salesReturn.getId(), salesReturn.getReturnNo(), reason,
                            "SO " + so.getSoNo());
                }

                BigDecimal unitCredit = Money.lineAmount(BigDecimal.ONE,
                        soLine.getUnitPrice(), soLine.getDiscountPct());
                BigDecimal credit = Money.multiply(qty, unitCredit);
                SalesReturnLine returnLine = new SalesReturnLine(salesReturn.getId(),
                        soLine.getId(), soLine.getProductId(), qty,
                        lineRequest.restock(), credit);
                returnDao.insertLine(conn, returnLine);
                lineResponses.add(ReturnLineResponse.from(returnLine,
                        skuOf(conn, soLine.getProductId())));
            }

            audit.logAction(conn, actor, AuditLogs.SALES_RETURN_RECEIVED, "RETURN",
                    salesReturn.getId(), salesReturn.getReturnNo(), null, null);
            log.info("Return {} received against SO {} ({} lines)",
                    salesReturn.getReturnNo(), so.getSoNo(), lineResponses.size());
            return new ReturnResponse(salesReturn.getId(), salesReturn.getReturnNo(),
                    soId, so.getSoNo(), warehouse.getCode(), salesReturn.getReceivedBy(),
                    salesReturn.getReceivedAt(), salesReturn.getReason(),
                    salesReturn.getNote(), lineResponses);
        });
    }

    public List<ReturnResponse> returnsForSo(AuthContext actor, long soId) {
        actor.require(Permission.SALES_ORDER_READ);
        return tm.withConnection(conn -> {
            SalesOrder so = soDao.findById(conn, soId)
                    .orElseThrow(() -> new NotFoundException("Sales order", soId));
            String whCode = warehouseDao.findById(conn, so.getWarehouseId())
                    .map(w -> w.getCode()).orElse("?");
            List<ReturnResponse> result = new ArrayList<>();
            for (SalesReturn r : returnDao.findBySalesOrder(conn, soId)) {
                List<ReturnLineResponse> lines = returnDao.findLines(conn, r.getId())
                        .stream()
                        .map(l -> ReturnLineResponse.from(l, skuOf(conn,
                                l.getProductId())))
                        .toList();
                result.add(ReturnResponse.from(r, so.getSoNo(), whCode, lines));
            }
            return result;
        });
    }

    private BigDecimal totalShippedForLine(Connection conn, long soLineId)
            throws SQLException {
        BigDecimal total = BigDecimal.ZERO;
        for (Shipment shipment : shipmentDao.findBySalesOrder(conn,
                soLineOf(conn, soLineId))) {
            for (ShipmentLine line : shipmentDao.findLines(conn, shipment.getId())) {
                if (line.getSalesOrderLineId() == soLineId) {
                    total = total.add(line.getQtyShipped());
                }
            }
        }
        return total;
    }

    private long soLineOf(Connection conn, long soLineId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT so_id FROM sales_order_lines WHERE id = ?")) {
            ps.setLong(1, soLineId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new NotFoundException("Sales order line", soLineId);
    }

    private String skuOf(Connection conn, long productId) {
        try {
            return productDao.findById(conn, productId).map(p -> p.getSku()).orElse("?");
        } catch (SQLException e) {
            return "?";
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("reason", "is required");
        }
        String value = reason.trim().toUpperCase();
        if (!SalesReturn.allowedReasons().contains(value)) {
            throw new ValidationException("reason",
                    "must be one of " + SalesReturn.allowedReasons());
        }
        return value;
    }
}
