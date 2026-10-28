package com.ims.domain.customer.dto;

import com.ims.domain.customer.Customer;

import java.math.BigDecimal;
import java.time.Instant;

/** DTO records for customers. */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CreateRequest(String code, String name, String customerType,
                                String email, String phone, String addressLine1,
                                String addressLine2, String city, String stateProvince,
                                String postalCode, String country,
                                BigDecimal creditLimit, String notes) {
    }

    public record UpdateRequest(String name, String customerType, String email,
                                String phone, String addressLine1, String addressLine2,
                                String city, String stateProvince, String postalCode,
                                String country, BigDecimal creditLimit, String notes,
                                Boolean active) {
    }

    public record Response(Long id, String code, String name, String customerType,
                           String email, String phone, String addressLine1,
                           String addressLine2, String city, String stateProvince,
                           String postalCode, String country, BigDecimal creditLimit,
                           String notes, boolean active, int version,
                           Instant createdAt, Instant updatedAt) {

        public static Response from(Customer c) {
            return new Response(c.getId(), c.getCode(), c.getName(), c.getCustomerType(),
                    c.getEmail(), c.getPhone(), c.getAddressLine1(), c.getAddressLine2(),
                    c.getCity(), c.getStateProvince(), c.getPostalCode(), c.getCountry(),
                    c.getCreditLimit(), c.getNotes(), c.isActive(), c.getVersion(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }
}
