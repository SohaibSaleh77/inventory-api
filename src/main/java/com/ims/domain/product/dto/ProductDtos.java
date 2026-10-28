package com.ims.domain.product.dto;

import com.ims.domain.product.Product;

import java.math.BigDecimal;
import java.time.Instant;

/** DTO records for products. */
public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateRequest(String sku, String barcode, String name, String description,
                                String categoryCode, String unitOfMeasure,
                                BigDecimal standardCost, BigDecimal listPrice,
                                BigDecimal reorderPoint, BigDecimal reorderQty) {
    }

    public record UpdateRequest(String barcode, String name, String description,
                                String categoryCode, String unitOfMeasure,
                                BigDecimal standardCost, BigDecimal listPrice,
                                BigDecimal reorderPoint, BigDecimal reorderQty,
                                Boolean active) {
    }

    public record Response(Long id, String sku, String barcode, String name,
                           String description, Long categoryId, String unitOfMeasure,
                           BigDecimal standardCost, BigDecimal listPrice,
                           BigDecimal reorderPoint, BigDecimal reorderQty,
                           boolean active, int version,
                           Instant createdAt, Instant updatedAt) {

        public static Response from(Product p) {
            return new Response(p.getId(), p.getSku(), p.getBarcode(), p.getName(),
                    p.getDescription(), p.getCategoryId(), p.getUnitOfMeasure(),
                    p.getStandardCost(), p.getListPrice(), p.getReorderPoint(),
                    p.getReorderQty(), p.isActive(), p.getVersion(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }
}
