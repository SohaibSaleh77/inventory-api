package com.ims.domain.sales.dto;

import com.ims.domain.sales.SalesOrder;
import com.ims.domain.sales.SalesOrderLine;
import com.ims.domain.sales.SalesReturn;
import com.ims.domain.sales.SalesReturnLine;
import com.ims.domain.sales.Shipment;
import com.ims.domain.sales.ShipmentLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** DTO records for sales. */
public final class SalesDtos {

    private SalesDtos() {
    }

    public record LineRequest(String productSku, BigDecimal qty, BigDecimal unitPrice,
                              BigDecimal discountPct, BigDecimal taxPct) {
    }

    public record CreateRequest(String customerCode, String warehouseCode,
                                LocalDate requestedDate, String notes,
                                List<LineRequest> lines) {
    }

    public record UpdateRequest(String customerCode, String warehouseCode,
                                LocalDate requestedDate, String notes,
                                List<LineRequest> lines) {
    }

    public record ShipLineRequest(long soLineId, BigDecimal qty) {
    }

    public record ShipRequest(List<ShipLineRequest> lines, String carrier,
                              String trackingNo, String note) {
    }

    public record ReturnLineRequest(long soLineId, BigDecimal qty, boolean restock) {
    }

    public record ReturnRequest(List<ReturnLineRequest> lines, String reason,
                                String note) {
    }

    public record LineResponse(Long id, int lineNo, Long productId, String productSku,
                               String productName, BigDecimal qtyOrdered,
                               BigDecimal qtyReserved, BigDecimal qtyFulfilled,
                               BigDecimal qtyOutstanding, BigDecimal unitPrice,
                               BigDecimal discountPct, BigDecimal taxPct,
                               BigDecimal lineTotal) {

        public static LineResponse from(SalesOrderLine line, String sku, String name) {
            return new LineResponse(line.getId(), line.getLineNo(), line.getProductId(),
                    sku, name, line.getQtyOrdered(), line.getQtyReserved(),
                    line.getQtyFulfilled(), line.qtyOutstanding(), line.getUnitPrice(),
                    line.getDiscountPct(), line.getTaxPct(), line.getLineTotal());
        }
    }

    public record Response(Long id, String soNo, Long customerId, String customerCode,
                           String customerName, Long warehouseId, String warehouseCode,
                           String status, LocalDate orderDate, LocalDate requestedDate,
                           BigDecimal subtotal, BigDecimal discountTotal,
                           BigDecimal taxTotal, BigDecimal total, String notes,
                           int version, Long createdBy, Instant confirmedAt,
                           Instant createdAt, Instant updatedAt,
                           List<LineResponse> lines) {

        public static Response from(SalesOrder so, String customerCode,
                                    String customerName, String warehouseCode,
                                    List<LineResponse> lines) {
            return new Response(so.getId(), so.getSoNo(), so.getCustomerId(),
                    customerCode, customerName, so.getWarehouseId(), warehouseCode,
                    so.getStatus(), so.getOrderDate(), so.getRequestedDate(),
                    so.getSubtotal(), so.getDiscountTotal(), so.getTaxTotal(),
                    so.getTotal(), so.getNotes(), so.getVersion(), so.getCreatedBy(),
                    so.getConfirmedAt(), so.getCreatedAt(), so.getUpdatedAt(), lines);
        }
    }

    public record ShipmentLineResponse(Long id, Long soLineId, Long productId,
                                       String productSku, BigDecimal qtyShipped,
                                       BigDecimal unitCostAtShip) {

        public static ShipmentLineResponse from(ShipmentLine line, String sku) {
            return new ShipmentLineResponse(line.getId(), line.getSalesOrderLineId(),
                    line.getProductId(), sku, line.getQtyShipped(),
                    line.getUnitCostAtShip());
        }
    }

    public record ShipmentResponse(Long id, String shipmentNo, Long salesOrderId,
                                   String soNo, String warehouseCode, Long shippedBy,
                                   Instant shippedAt, String carrier, String trackingNo,
                                   String note, List<ShipmentLineResponse> lines) {

        public static ShipmentResponse from(Shipment s, String soNo, String whCode,
                                            List<ShipmentLineResponse> lines) {
            return new ShipmentResponse(s.getId(), s.getShipmentNo(),
                    s.getSalesOrderId(), soNo, whCode, s.getShippedBy(),
                    s.getShippedAt(), s.getCarrier(), s.getTrackingNo(), s.getNote(),
                    lines);
        }
    }

    public record ReturnLineResponse(Long id, Long soLineId, Long productId,
                                     String productSku, BigDecimal qtyReturned,
                                     boolean restock, BigDecimal creditAmount) {

        public static ReturnLineResponse from(SalesReturnLine line, String sku) {
            return new ReturnLineResponse(line.getId(), line.getSalesOrderLineId(),
                    line.getProductId(), sku, line.getQtyReturned(), line.isRestock(),
                    line.getCreditAmount());
        }
    }

    public record ReturnResponse(Long id, String returnNo, Long salesOrderId,
                                 String soNo, String warehouseCode, Long receivedBy,
                                 Instant receivedAt, String reason, String note,
                                 List<ReturnLineResponse> lines) {

        public static ReturnResponse from(SalesReturn r, String soNo, String whCode,
                                          List<ReturnLineResponse> lines) {
            return new ReturnResponse(r.getId(), r.getReturnNo(), r.getSalesOrderId(),
                    soNo, whCode, r.getReceivedBy(), r.getReceivedAt(), r.getReason(),
                    r.getNote(), lines);
        }
    }
}
