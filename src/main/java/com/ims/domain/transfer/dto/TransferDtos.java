package com.ims.domain.transfer.dto;

import com.ims.domain.transfer.StockTransfer;

import java.math.BigDecimal;
import java.time.Instant;

/** DTO records for transfers. */
public final class TransferDtos {

    private TransferDtos() {
    }

    public record CreateRequest(String productSku, String fromWarehouseCode,
                                String toWarehouseCode, BigDecimal quantity,
                                String note) {
    }

    public record Response(Long id, String transferNo, Long productId, String productSku,
                           Long fromWarehouseId, String fromWarehouseCode,
                           Long toWarehouseId, String toWarehouseCode,
                           BigDecimal quantity, String status, String note,
                           int version, Long createdBy, Instant createdAt,
                           Long dispatchedBy, Instant dispatchedAt,
                           Long receivedBy, Instant receivedAt) {

        public static Response from(StockTransfer t, String sku, String fromCode,
                                    String toCode) {
            return new Response(t.getId(), t.getTransferNo(), t.getProductId(), sku,
                    t.getFromWarehouseId(), fromCode, t.getToWarehouseId(), toCode,
                    t.getQuantity(), t.getStatus(), t.getNote(), t.getVersion(),
                    t.getCreatedBy(), t.getCreatedAt(), t.getDispatchedBy(),
                    t.getDispatchedAt(), t.getReceivedBy(), t.getReceivedAt());
        }
    }

    public record Enriched(Response transfer, java.math.BigDecimal fromOnHandAfter,
                           java.math.BigDecimal toOnHandAfter) {
    }
}
