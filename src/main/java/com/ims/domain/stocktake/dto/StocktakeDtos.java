package com.ims.domain.stocktake.dto;

import com.ims.domain.stocktake.Stocktake;
import com.ims.domain.stocktake.StocktakeLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Request/response records for the stocktake API. */
public final class StocktakeDtos {

    private StocktakeDtos() {
    }

    public record CreateRequest(String warehouseCode, String name, Boolean blind,
                                String note) {
    }

    public record CountRequest(String productSku, BigDecimal countedQty) {
    }

    /**
     * Count-sheet line. {@code expectedQty} and {@code variance} are omitted
     * (null) while the stocktake is OPEN in blind mode - counters must not
     * see the book quantity.
     */
    public record LineResponse(long productId, String sku, String productName,
                               BigDecimal expectedQty, BigDecimal countedQty,
                               BigDecimal variance, int recounts,
                               String countedBy, Instant countedAt) {

        public static LineResponse from(StocktakeLine line, boolean hideExpected) {
            return new LineResponse(line.getProductId(), line.getProductSku(),
                    line.getProductName(),
                    hideExpected ? null : line.getExpectedQty(),
                    line.getCountedQty(),
                    hideExpected ? null : line.variance(),
                    line.getRecounts(), line.getCountedByUsername(),
                    line.getCountedAt());
        }
    }

    public record Response(long id, String stocktakeNo, long warehouseId,
                           String warehouseCode, String name, boolean blind,
                           String status, String note, int lineCount,
                           int countedLines, int varianceLines,
                           Instant openedAt, Instant closedAt, Instant postedAt,
                           Instant createdAt) {

        public static Response from(Stocktake stocktake, String warehouseCode,
                                    int lineCount, int countedLines,
                                    int varianceLines) {
            return new Response(stocktake.getId(), stocktake.getStocktakeNo(),
                    stocktake.getWarehouseId(), warehouseCode, stocktake.getName(),
                    stocktake.isBlind(), stocktake.getStatus(), stocktake.getNote(),
                    lineCount, countedLines, varianceLines,
                    stocktake.getOpenedAt(), stocktake.getClosedAt(),
                    stocktake.getPostedAt(), stocktake.getCreatedAt());
        }
    }

    public record DetailResponse(long id, String stocktakeNo, long warehouseId,
                                 String warehouseCode, String name, boolean blind,
                                 String status, String note, int lineCount,
                                 int countedLines, int varianceLines,
                                 Instant openedAt, Instant closedAt,
                                 Instant postedAt, Instant createdAt,
                                 List<LineResponse> lines) {

        public static DetailResponse from(Stocktake stocktake,
                                          String warehouseCode,
                                          List<LineResponse> lines) {
            int counted = 0;
            int variances = 0;
            for (LineResponse line : lines) {
                if (line.countedQty() != null) {
                    counted++;
                    if (line.variance() != null
                            && line.variance().signum() != 0) {
                        variances++;
                    }
                }
            }
            return new DetailResponse(stocktake.getId(), stocktake.getStocktakeNo(),
                    stocktake.getWarehouseId(), warehouseCode, stocktake.getName(),
                    stocktake.isBlind(), stocktake.getStatus(), stocktake.getNote(),
                    lines.size(), counted, variances, stocktake.getOpenedAt(),
                    stocktake.getClosedAt(), stocktake.getPostedAt(),
                    stocktake.getCreatedAt(), lines);
        }
    }

    /** Result of posting: what happened to every line of the sheet. */
    public record PostResult(long stocktakeId, String stocktakeNo, int countedLines,
                             int correctedLines, int zeroVarianceLines,
                             int uncountedLines) {

        public int totalLines() {
            return countedLines + uncountedLines;
        }
    }
}
