package com.ims.domain.stock.dto;

import com.ims.domain.stock.StockAdjustment;
import com.ims.domain.stock.StockMovement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** DTO records for the stock module. */
public final class StockDtos {

    private StockDtos() {
    }

    public record AdjustRequest(String productSku, String warehouseCode,
                                BigDecimal quantityDelta, String reason, String note) {
    }

    public record MovementResponse(Long id, String movementNo, Long productId,
                                   String productSku, Long warehouseId,
                                   String warehouseCode, String movementType,
                                   BigDecimal quantity, BigDecimal unitCost,
                                   BigDecimal balanceAfter, String refType, Long refId,
                                   String refNo, String reason, String note,
                                   Long performedBy, Instant performedAt) {

        public static MovementResponse from(StockMovement m, String sku, String whCode) {
            return new MovementResponse(m.getId(), m.getMovementNo(), m.getProductId(),
                    sku, m.getWarehouseId(), whCode, m.getMovementType(), m.getQuantity(),
                    m.getUnitCost(), m.getBalanceAfter(), m.getRefType(), m.getRefId(),
                    m.getRefNo(), m.getReason(), m.getNote(), m.getPerformedBy(),
                    m.getPerformedAt());
        }
    }

    public record AdjustmentResponse(Long id, String adjustmentNo, Long productId,
                                     String productSku, Long warehouseId,
                                     String warehouseCode, BigDecimal quantityDelta,
                                     String reason, String note, Long createdBy,
                                     Instant createdAt) {

        public static AdjustmentResponse from(StockAdjustment a, String sku, String whCode) {
            return new AdjustmentResponse(a.getId(), a.getAdjustmentNo(), a.getProductId(),
                    sku, a.getWarehouseId(), whCode, a.getQuantityDelta(), a.getReason(),
                    a.getNote(), a.getCreatedBy(), a.getCreatedAt());
        }
    }

    public record StockLevelResponse(Long stockItemId, Long productId, String sku,
                                     String productName, Long warehouseId,
                                     String warehouseCode, String warehouseName,
                                     BigDecimal qtyOnHand, BigDecimal qtyReserved,
                                     BigDecimal qtyAvailable, BigDecimal avgUnitCost,
                                     BigDecimal stockValue) {

        public static StockLevelResponse from(StockItemDaoView v) {
            return new StockLevelResponse(v.stockItemId(), v.productId(), v.sku(),
                    v.productName(), v.warehouseId(), v.warehouseCode(), v.warehouseName(),
                    v.qtyOnHand(), v.qtyReserved(), v.qtyAvailable(), v.avgUnitCost(),
                    v.stockValue());
        }
    }

    /** Local mirror of the DAO view record to avoid exposing DAO types in DTOs. */
    public record StockItemDaoView(long stockItemId, long productId, String sku,
                                   String productName, long warehouseId,
                                   String warehouseCode, String warehouseName,
                                   BigDecimal qtyOnHand, BigDecimal qtyReserved,
                                   BigDecimal qtyAvailable, BigDecimal avgUnitCost,
                                   BigDecimal stockValue) {
    }

    public record PageResponse<T>(List<T> content, int page, int size,
                                  long totalElements, int totalPages) {
    }
}
