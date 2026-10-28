package com.ims.domain.warehouse.dto;

import com.ims.domain.warehouse.Warehouse;

import java.time.Instant;

/** DTO records for warehouses. */
public final class WarehouseDtos {

    private WarehouseDtos() {
    }

    public record CreateRequest(String code, String name, String addressLine1,
                                String city, String stateProvince, String postalCode,
                                String country) {
    }

    public record UpdateRequest(String name, String addressLine1, String city,
                                String stateProvince, String postalCode, String country,
                                Boolean active) {
    }

    public record Response(Long id, String code, String name, String addressLine1,
                           String city, String stateProvince, String postalCode,
                           String country, boolean active, int version,
                           Instant createdAt, Instant updatedAt) {

        public static Response from(Warehouse w) {
            return new Response(w.getId(), w.getCode(), w.getName(), w.getAddressLine1(),
                    w.getCity(), w.getStateProvince(), w.getPostalCode(), w.getCountry(),
                    w.isActive(), w.getVersion(), w.getCreatedAt(), w.getUpdatedAt());
        }
    }
}
