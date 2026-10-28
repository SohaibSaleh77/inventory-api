package com.ims.domain.sales;

import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Quantities;
import com.ims.common.util.Validators;
import com.ims.db.DocumentNumbers;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.product.ProductDao;
import com.ims.domain.sales.dto.SalesDtos.ShipLineRequest;
import com.ims.domain.sales.dto.SalesDtos.ShipRequest;
import com.ims.domain.sales.dto.SalesDtos.ShipmentLineResponse;
import com.ims.domain.sales.dto.SalesDtos.ShipmentResponse;
import com.ims.domain.stock.StockItemDao;
import com.ims.domain.stock.StockMovement;
import com.ims.domain.stock.StockService;
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

/**
 * Fulfilment: ships reserved quantities against confirmed SO lines. Shipping
 * consumes the reservation (stock leaves the reserved pool), records the
 * unit cost at ship time for COGS, and advances the order to
 * PARTIALLY_FULFILLED / FULFILLED.
 */
public final class FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

    private final TransactionManager tm;
    private final SalesOrderDao soDao;
    private final ShipmentDao shipmentDao;
    private final WarehouseDao warehouseDao;
    private final ProductDao productDao;
    private final StockItemDao stockItemDao;
    private final StockService stockService;
    private final WebhookService webhookService;
    private final AuditLogger audit;
    private final DocumentNumbers documentNumbers;

    public FulfillmentService(TransactionManager tm, SalesOrderDao soDao,
                              ShipmentDao shipmentDao, WarehouseDao warehouseDao,
                              ProductDao productDao, StockItemDao stockItemDao,
                              StockService stockService,
                              WebhookService webhookService, AuditLogger audit,
                              DocumentNumbers documentNumbers) {
        this.tm = tm;
        this.soDao = soDao;
        this.shipmentDao = shipmentDao;
        this.warehouseDao = warehouseDao;
        this.productDao = productDao;
        this.stockItemDao = stockItemDao;
        this.stockService = stockService;
        this.webhookService = webhookService;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    public ShipmentResponse ship(AuthContext actor, long soId, ShipRequest request) {
        actor.require(Permission.SALES_ORDER_FULFILL);
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ValidationException("lines", "at least one shipment line required");
        }

        return tm.inTransaction(conn -> {
            SalesOrder so = soDao.lockById(conn, soId)
                    .orElseThrow(() -> new NotFoundException("Sales order", soId));
            if (!so.isFulfillable()) {
                throw new IllegalStateTransitionException("Sales order", soId,
                        so.getStatus(), "ship from");
            }
            var warehouse = warehouseDao.findById(conn, so.getWarehouseId())
                    .orElseThrow();
            List<SalesOrderLine> soLines = soDao.findLines(conn, soId);

            Shipment shipment = new Shipment(soId, so.getWarehouseId(), actor.userId(),
                    Validators.optionalText(request.carrier(), "carrier", 60),
                    Validators.optionalText(request.trackingNo(), "trackingNo", 80),
                    Validators.optionalText(request.note(), "note", 300));
            shipmentDao.insert(conn, documentNumbers, shipment);

            List<ShipmentLineResponse> lineResponses = new ArrayList<>();
            for (ShipLineRequest lineRequest : request.lines()) {
                SalesOrderLine soLine = soLines.stream()
                        .filter(l -> l.getId() == lineRequest.soLineId())
                        .findFirst()
                        .orElseThrow(() -> new ValidationException("lines",
                                "line " + lineRequest.soLineId() + " does not belong to "
                                        + so.getSoNo()));
                BigDecimal qty = Quantities.of(Validators.requirePositive(
                        lineRequest.qty(), "qty"));

                BigDecimal reserved = soLine.getQtyReserved();
                if (qty.compareTo(reserved) > 0) {
                    throw new ValidationException("qty",
                            "cannot ship " + qty.toPlainString() + " of line "
                                    + soLine.getLineNo() + ": only "
                                    + reserved.toPlainString() + " reserved");
                }

                BigDecimal costAtShip = stockItemDao
                        .findByProductAndWarehouse(conn, soLine.getProductId(),
                                so.getWarehouseId())
                        .map(s -> s.getAvgUnitCost()).orElse(BigDecimal.ZERO);

                stockService.applyStockChange(conn, actor, soLine.getProductId(),
                        so.getWarehouseId(), qty.negate(), null, true,
                        StockMovement.SALE_SHIPMENT, StockService.REF_SHIPMENT,
                        shipment.getId(), shipment.getShipmentNo(), null,
                        "SO " + so.getSoNo());

                shipmentDao.insertLine(conn, new ShipmentLine(shipment.getId(),
                        soLine.getId(), soLine.getProductId(), qty, costAtShip));

                // Consume the reservation TABLE entry. The stock row's
                // qty_reserved was already decremented by applyStockChange
                // (deductFromReserved), so only the reservation record moves.
                for (Reservation reservation : soDao.findActiveReservations(conn, soId)) {
                    if (reservation.getSalesOrderLineId() == soLine.getId()) {
                        if (reservation.getQtyReserved().compareTo(qty) <= 0) {
                            soDao.updateReservationStatus(conn, reservation.getId(),
                                    Reservation.STATUS_CONSUMED);
                        } else {
                            soDao.updateReservationQty(conn, reservation.getId(),
                                    reservation.getQtyReserved().subtract(qty));
                        }
                        break;
                    }
                }
                soDao.updateLineFulfilment(conn, soLine.getId(),
                        soLine.getQtyReserved().subtract(qty),
                        soLine.getQtyFulfilled().add(qty));
                lineResponses.add(ShipmentLineResponse.from(new ShipmentLine(
                        shipment.getId(), soLine.getId(), soLine.getProductId(), qty,
                        costAtShip), skuOf(conn, soLine.getProductId())));
            }

            advanceStatus(conn, so);
            audit.logAction(conn, actor, AuditLogs.GOODS_SHIPPED, "SHIPMENT",
                    shipment.getId(), shipment.getShipmentNo(), null, null);
            webhookService.enqueueEvent(conn, WebhookEvents.SALES_ORDER_SHIPPED,
                    java.util.Map.of("shipmentId", shipment.getId(),
                            "shipmentNo", shipment.getShipmentNo(),
                            "soId", soId, "soNo", so.getSoNo(),
                            "warehouseId", so.getWarehouseId(),
                            "lines", lineResponses.size()));
            log.info("Shipment {} created for SO {} by {} ({} lines)",
                    shipment.getShipmentNo(), so.getSoNo(), actor.username(),
                    lineResponses.size());
            return new ShipmentResponse(shipment.getId(), shipment.getShipmentNo(),
                    soId, so.getSoNo(), warehouse.getCode(), shipment.getShippedBy(),
                    shipment.getShippedAt(), shipment.getCarrier(),
                    shipment.getTrackingNo(), shipment.getNote(), lineResponses);
        });
    }

    private void advanceStatus(Connection conn, SalesOrder so) throws SQLException {
        List<SalesOrderLine> lines = soDao.findLines(conn, so.getId());
        boolean fullyFulfilled = lines.stream()
                .allMatch(l -> l.getQtyFulfilled().compareTo(l.getQtyOrdered()) >= 0);
        String newStatus = fullyFulfilled ? SalesOrder.STATUS_FULFILLED
                : SalesOrder.STATUS_PARTIALLY_FULFILLED;
        String current = soDao.findById(conn, so.getId()).map(SalesOrder::getStatus)
                .orElse(so.getStatus());
        if (!newStatus.equals(current)) {
            soDao.updateStatus(conn, so.getId(), current, newStatus);
        }
    }

    public List<ShipmentResponse> shipmentsForSo(AuthContext actor, long soId) {
        actor.require(Permission.SALES_ORDER_READ);
        return tm.withConnection(conn -> {
            SalesOrder so = soDao.findById(conn, soId)
                    .orElseThrow(() -> new NotFoundException("Sales order", soId));
            String whCode = warehouseDao.findById(conn, so.getWarehouseId())
                    .map(w -> w.getCode()).orElse("?");
            List<ShipmentResponse> result = new ArrayList<>();
            for (Shipment shipment : shipmentDao.findBySalesOrder(conn, soId)) {
                List<ShipmentLineResponse> lines = shipmentDao
                        .findLines(conn, shipment.getId()).stream()
                        .map(l -> ShipmentLineResponse.from(l, skuOf(conn,
                                l.getProductId())))
                        .toList();
                result.add(ShipmentResponse.from(shipment, so.getSoNo(), whCode, lines));
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
