package com.ims.domain.purchase.dto;

import com.ims.domain.purchase.PurchaseOrder;
import com.ims.domain.purchase.PurchaseOrderLine;
import com.ims.domain.purchase.Receipt;
import com.ims.domain.purchase.ReceiptLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** DTO records for purchasing. */
public final class PurchaseDtos {

    private PurchaseDtos() {
    }

    public record LineRequest(String productSku, BigDecimal qty, BigDecimal unitCost,
                              BigDecimal taxPct) {
    }

    public record CreateRequest(String supplierCode, String warehouseCode,
                                LocalDate orderDate, LocalDate expectedDate,
                                String currency, String notes, List<LineRequest> lines) {
    }

    public record UpdateRequest(String supplierCode, String warehouseCode,
                                LocalDate orderDate, LocalDate expectedDate,
                                String notes, List<LineRequest> lines) {
    }

    public record ReceiveLineRequest(long poLineId, BigDecimal qtyAccepted,
                                     BigDecimal qtyRejected) {
    }

    public record ReceiveRequest(List<ReceiveLineRequest> lines, String note) {
    }

    public record LineResponse(Long id, int lineNo, Long productId, String productSku,
                               String productName, BigDecimal qtyOrdered,
                               BigDecimal qtyReceived, BigDecimal qtyOutstanding,
                               BigDecimal unitCost, BigDecimal taxPct,
                               BigDecimal lineTotal) {

        public static LineResponse from(PurchaseOrderLine line, String sku, String name) {
            return new LineResponse(line.getId(), line.getLineNo(), line.getProductId(),
                    sku, name, line.getQtyOrdered(), line.getQtyReceived(),
                    line.qtyOutstanding(), line.getUnitCost(), line.getTaxPct(),
                    line.getLineTotal());
        }
    }

    public record Response(Long id, String poNo, Long supplierId, String supplierCode,
                           String supplierName, Long warehouseId, String warehouseCode,
                           String status, LocalDate orderDate, LocalDate expectedDate,
                           String currency, BigDecimal subtotal, BigDecimal taxTotal,
                           BigDecimal total, String notes, int version, Long createdBy,
                           Instant approvedAt, Instant createdAt, Instant updatedAt,
                           List<LineResponse> lines) {

        public static Response from(PurchaseOrder po, String supplierCode,
                                    String supplierName, String warehouseCode,
                                    List<LineResponse> lines) {
            return new Response(po.getId(), po.getPoNo(), po.getSupplierId(), supplierCode,
                    supplierName, po.getWarehouseId(), warehouseCode, po.getStatus(),
                    po.getOrderDate(), po.getExpectedDate(), po.getCurrency(),
                    po.getSubtotal(), po.getTaxTotal(), po.getTotal(), po.getNotes(),
                    po.getVersion(), po.getCreatedBy(), po.getApprovedAt(),
                    po.getCreatedAt(), po.getUpdatedAt(), lines);
        }
    }

    public record ReceiptLineResponse(Long id, Long poLineId, Long productId,
                                      String productSku, BigDecimal qtyAccepted,
                                      BigDecimal qtyRejected, BigDecimal unitCost) {

        public static ReceiptLineResponse from(ReceiptLine line, String sku) {
            return new ReceiptLineResponse(line.getId(), line.getPoLineId(),
                    line.getProductId(), sku, line.getQtyAccepted(),
                    line.getQtyRejected(), line.getUnitCost());
        }
    }

    public record ReceiptResponse(Long id, String receiptNo, Long poId, String poNo,
                                  Long warehouseId, String warehouseCode,
                                  Long receivedBy, Instant receivedAt, String note,
                                  List<ReceiptLineResponse> lines) {

        public static ReceiptResponse from(Receipt r, String poNo, String whCode,
                                           List<ReceiptLineResponse> lines) {
            return new ReceiptResponse(r.getId(), r.getReceiptNo(), r.getPoId(), poNo,
                    r.getWarehouseId(), whCode, r.getReceivedBy(), r.getReceivedAt(),
                    r.getNote(), lines);
        }
    }
}
