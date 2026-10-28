package com.ims.domain.customer;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.OptimisticLockException;
import com.ims.common.exception.ResourceInUseException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Money;
import com.ims.common.util.PageRequest;
import com.ims.common.util.Validators;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.customer.dto.CustomerDtos.CreateRequest;
import com.ims.domain.customer.dto.CustomerDtos.Response;
import com.ims.domain.customer.dto.CustomerDtos.UpdateRequest;
import com.ims.security.AuthContext;
import com.ims.security.Permission;

import java.util.List;
import java.util.Set;

/** Customer management. */
public final class CustomerService {

    private static final Set<String> TYPES = Set.of(Customer.TYPE_RETAIL,
            Customer.TYPE_WHOLESALE, Customer.TYPE_DISTRIBUTOR);

    private final TransactionManager tm;
    private final CustomerDao customerDao;
    private final AuditLogger audit;

    public CustomerService(TransactionManager tm, CustomerDao customerDao,
                           AuditLogger audit) {
        this.tm = tm;
        this.customerDao = customerDao;
        this.audit = audit;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.CUSTOMER_WRITE);
        String code = Validators.requireCode(request.code(), "code", 20);
        String name = Validators.requireText(request.name(), "name", 150);
        String type = normalizeType(request.customerType());

        return tm.inTransaction(conn -> {
            if (customerDao.codeExists(conn, code)) {
                throw new ValidationException("code", "already in use");
            }
            Customer customer = new Customer(code, name, type);
            customer.setEmail(Validators.optionalEmail(request.email(), "email"));
            customer.setPhone(Validators.optionalText(request.phone(), "phone", 30));
            customer.setAddressLine1(Validators.optionalText(
                    request.addressLine1(), "addressLine1", 200));
            customer.setAddressLine2(Validators.optionalText(
                    request.addressLine2(), "addressLine2", 200));
            customer.setCity(Validators.optionalText(request.city(), "city", 100));
            customer.setStateProvince(Validators.optionalText(
                    request.stateProvince(), "stateProvince", 100));
            customer.setPostalCode(Validators.optionalText(
                    request.postalCode(), "postalCode", 20));
            customer.setCountry(Validators.optionalText(request.country(), "country", 60));
            customer.setCreditLimit(Money.of(Validators.optionalNonNegative(
                    request.creditLimit(), "creditLimit")));
            customer.setNotes(Validators.optionalText(request.notes(), "notes", 1000));
            Customer inserted = customerDao.insert(conn, customer);
            audit.logAction(conn, actor, AuditLogs.CUSTOMER_CREATED, "CUSTOMER",
                    inserted.getId(), code, null, Response.from(inserted));
            return Response.from(inserted);
        });
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.CUSTOMER_WRITE);
        String name = Validators.requireText(request.name(), "name", 150);

        return tm.inTransaction(conn -> {
            Customer customer = customerDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Customer", id));
            Response before = Response.from(customer);

            customer.setName(name);
            if (request.customerType() != null) {
                customer.setCustomerType(normalizeType(request.customerType()));
            }
            if (request.email() != null) {
                customer.setEmail(Validators.optionalEmail(request.email(), "email"));
            }
            if (request.phone() != null) {
                customer.setPhone(Validators.optionalText(request.phone(), "phone", 30));
            }
            if (request.addressLine1() != null) {
                customer.setAddressLine1(Validators.optionalText(
                        request.addressLine1(), "addressLine1", 200));
            }
            if (request.addressLine2() != null) {
                customer.setAddressLine2(Validators.optionalText(
                        request.addressLine2(), "addressLine2", 200));
            }
            if (request.city() != null) {
                customer.setCity(Validators.optionalText(request.city(), "city", 100));
            }
            if (request.stateProvince() != null) {
                customer.setStateProvince(Validators.optionalText(
                        request.stateProvince(), "stateProvince", 100));
            }
            if (request.postalCode() != null) {
                customer.setPostalCode(Validators.optionalText(
                        request.postalCode(), "postalCode", 20));
            }
            if (request.country() != null) {
                customer.setCountry(Validators.optionalText(request.country(), "country", 60));
            }
            if (request.creditLimit() != null) {
                customer.setCreditLimit(Money.of(Validators.optionalNonNegative(
                        request.creditLimit(), "creditLimit")));
            }
            if (request.notes() != null) {
                customer.setNotes(Validators.optionalText(request.notes(), "notes", 1000));
            }
            if (request.active() != null) {
                customer.setActive(request.active());
            }
            if (!customerDao.update(conn, customer)) {
                throw new OptimisticLockException("Customer", id);
            }
            Customer updated = customerDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.CUSTOMER_UPDATED, "CUSTOMER", id,
                    updated.getCode(), before, Response.from(updated));
            return Response.from(updated);
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.CUSTOMER_READ);
        return tm.withConnection(conn -> customerDao.findById(conn, id)
                .map(Response::from)
                .orElseThrow(() -> new NotFoundException("Customer", id)));
    }

    public PageRequest.Page<Response> search(AuthContext actor, String search,
                                             Boolean active, PageRequest page) {
        actor.require(Permission.CUSTOMER_READ);
        return tm.withConnection(conn -> {
            List<Customer> customers = customerDao.search(conn, search, active,
                    page.size(), page.offset());
            long total = customerDao.countSearch(conn, search, active);
            return PageRequest.Page.of(
                    customers.stream().map(Response::from).toList(), page, total);
        });
    }

    public void delete(AuthContext actor, long id) {
        actor.require(Permission.CUSTOMER_WRITE);
        tm.inTransaction(conn -> {
            Customer customer = customerDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Customer", id));
            long soCount = customerDao.countSalesOrders(conn, id);
            if (soCount > 0) {
                throw new ResourceInUseException("Customer", id,
                        soCount + " sales orders");
            }
            if (!customerDao.delete(conn, id)) {
                throw new NotFoundException("Customer", id);
            }
            audit.logAction(conn, actor, AuditLogs.CUSTOMER_DELETED, "CUSTOMER", id,
                    customer.getCode(), Response.from(customer), null);
            return null;
        });
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return Customer.TYPE_RETAIL;
        }
        String value = type.trim().toUpperCase();
        if (!TYPES.contains(value)) {
            throw new ValidationException("customerType", "must be one of " + TYPES);
        }
        return value;
    }
}
