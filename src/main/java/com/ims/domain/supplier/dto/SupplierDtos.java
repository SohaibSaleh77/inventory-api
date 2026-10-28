package com.ims.domain.supplier.dto;

import com.ims.domain.supplier.Supplier;
import com.ims.domain.supplier.SupplierItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** DTO records for suppliers. */
public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record CreateRequest(String code, String name, String contactName, String email,
                                String phone, String addressLine1, String addressLine2,
                                String city, String stateProvince, String postalCode,
                                String country, Integer paymentTermsDays, Integer rating,
                                String notes) {
    }

    public record UpdateRequest(String name, String contactName, String email, String phone,
                                String addressLine1, String addressLine2, String city,
                                String stateProvince, String postalCode, String country,
                                Integer paymentTermsDays, Integer rating, String notes,
                                Boolean active) {
    }

    public record UpsertItemRequest(String productSku, String supplierSku,
                                    BigDecimal unitCost, Boolean preferred,
                                    Integer leadTimeDays) {
    }

    public record ItemResponse(Long productId, String productSku, String productName,
                               String supplierSku, BigDecimal unitCost, boolean preferred,
                               int leadTimeDays, Instant updatedAt) {

        public static ItemResponse from(SupplierItem item, String sku, String name) {
            return new ItemResponse(item.getProductId(), sku, name, item.getSupplierSku(),
                    item.getUnitCost(), item.isPreferred(), item.getLeadTimeDays(),
                    item.getUpdatedAt());
        }
    }

    public record Response(Long id, String code, String name, String contactName,
                           String email, String phone, String addressLine1,
                           String addressLine2, String city, String stateProvince,
                           String postalCode, String country, int paymentTermsDays,
                           Integer rating, String notes, boolean active, int version,
                           Instant createdAt, Instant updatedAt) {

        public static Response from(Supplier s) {
            return new Response(s.getId(), s.getCode(), s.getName(), s.getContactName(),
                    s.getEmail(), s.getPhone(), s.getAddressLine1(), s.getAddressLine2(),
                    s.getCity(), s.getStateProvince(), s.getPostalCode(), s.getCountry(),
                    s.getPaymentTermsDays(), s.getRating(), s.getNotes(), s.isActive(),
                    s.getVersion(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record Detail(Response supplier, List<ItemResponse> items) {
    }
}
