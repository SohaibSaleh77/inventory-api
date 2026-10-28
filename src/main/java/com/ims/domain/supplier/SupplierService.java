package com.ims.domain.supplier;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.OptimisticLockException;
import com.ims.common.exception.ResourceInUseException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.PageRequest;
import com.ims.common.util.Validators;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.product.ProductDao;
import com.ims.domain.supplier.dto.SupplierDtos.CreateRequest;
import com.ims.domain.supplier.dto.SupplierDtos.Detail;
import com.ims.domain.supplier.dto.SupplierDtos.ItemResponse;
import com.ims.domain.supplier.dto.SupplierDtos.Response;
import com.ims.domain.supplier.dto.SupplierDtos.UpdateRequest;
import com.ims.domain.supplier.dto.SupplierDtos.UpsertItemRequest;
import com.ims.security.AuthContext;
import com.ims.security.Permission;

import java.util.List;

/** Supplier management incl. the supplier catalog (supplier-product mappings). */
public final class SupplierService {

    private final TransactionManager tm;
    private final SupplierDao supplierDao;
    private final ProductDao productDao;
    private final AuditLogger audit;

    public SupplierService(TransactionManager tm, SupplierDao supplierDao,
                           ProductDao productDao, AuditLogger audit) {
        this.tm = tm;
        this.supplierDao = supplierDao;
        this.productDao = productDao;
        this.audit = audit;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.SUPPLIER_WRITE);
        String code = Validators.requireCode(request.code(), "code", 20);
        String name = Validators.requireText(request.name(), "name", 150);

        return tm.inTransaction(conn -> {
            if (supplierDao.codeExists(conn, code)) {
                throw new ValidationException("code", "already in use");
            }
            Supplier supplier = new Supplier(code, name);
            applyRequest(supplier, request.contactName(), request.email(), request.phone(),
                    request.addressLine1(), request.addressLine2(), request.city(),
                    request.stateProvince(), request.postalCode(), request.country(),
                    request.paymentTermsDays(), request.rating(), request.notes());
            Supplier inserted = supplierDao.insert(conn, supplier);
            audit.logAction(conn, actor, AuditLogs.SUPPLIER_CREATED, "SUPPLIER",
                    inserted.getId(), code, null, Response.from(inserted));
            return Response.from(inserted);
        });
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.SUPPLIER_WRITE);
        String name = Validators.requireText(request.name(), "name", 150);

        return tm.inTransaction(conn -> {
            Supplier supplier = supplierDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Supplier", id));
            Response before = Response.from(supplier);

            supplier.setName(name);
            if (request.contactName() != null) {
                supplier.setContactName(Validators.optionalText(
                        request.contactName(), "contactName", 120));
            }
            if (request.email() != null) {
                supplier.setEmail(Validators.optionalEmail(request.email(), "email"));
            }
            if (request.phone() != null) {
                supplier.setPhone(Validators.optionalText(request.phone(), "phone", 30));
            }
            if (request.addressLine1() != null) {
                supplier.setAddressLine1(Validators.optionalText(
                        request.addressLine1(), "addressLine1", 200));
            }
            if (request.addressLine2() != null) {
                supplier.setAddressLine2(Validators.optionalText(
                        request.addressLine2(), "addressLine2", 200));
            }
            if (request.city() != null) {
                supplier.setCity(Validators.optionalText(request.city(), "city", 100));
            }
            if (request.stateProvince() != null) {
                supplier.setStateProvince(Validators.optionalText(
                        request.stateProvince(), "stateProvince", 100));
            }
            if (request.postalCode() != null) {
                supplier.setPostalCode(Validators.optionalText(
                        request.postalCode(), "postalCode", 20));
            }
            if (request.country() != null) {
                supplier.setCountry(Validators.optionalText(request.country(), "country", 60));
            }
            if (request.paymentTermsDays() != null) {
                supplier.setPaymentTermsDays(Validators.requireIntInRange(
                        request.paymentTermsDays(), "paymentTermsDays", 0, 365));
            }
            if (request.rating() != null) {
                supplier.setRating(Validators.requireIntInRange(
                        request.rating(), "rating", 1, 5));
            }
            if (request.notes() != null) {
                supplier.setNotes(Validators.optionalText(request.notes(), "notes", 1000));
            }
            if (request.active() != null) {
                supplier.setActive(request.active());
            }
            if (!supplierDao.update(conn, supplier)) {
                throw new OptimisticLockException("Supplier", id);
            }
            Supplier updated = supplierDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.SUPPLIER_UPDATED, "SUPPLIER", id,
                    updated.getCode(), before, Response.from(updated));
            return Response.from(updated);
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.SUPPLIER_READ);
        return tm.withConnection(conn -> supplierDao.findById(conn, id)
                .map(Response::from)
                .orElseThrow(() -> new NotFoundException("Supplier", id)));
    }

    public Detail getDetail(AuthContext actor, long id) {
        actor.require(Permission.SUPPLIER_READ);
        return tm.withConnection(conn -> {
            Supplier supplier = supplierDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Supplier", id));
            List<ItemResponse> items = new java.util.ArrayList<>();
            for (SupplierItem item : supplierDao.findItems(conn, id)) {
                var product = productDao.findById(conn, item.getProductId())
                        .orElse(null);
                String sku = product == null ? "?" : product.getSku();
                String name = product == null ? "?" : product.getName();
                items.add(ItemResponse.from(item, sku, name));
            }
            return new Detail(Response.from(supplier), items);
        });
    }

    public PageRequest.Page<Response> search(AuthContext actor, String search,
                                             Boolean active, PageRequest page) {
        actor.require(Permission.SUPPLIER_READ);
        return tm.withConnection(conn -> {
            List<Supplier> suppliers = supplierDao.search(conn, search, active,
                    page.size(), page.offset());
            long total = supplierDao.countSearch(conn, search, active);
            return PageRequest.Page.of(
                    suppliers.stream().map(Response::from).toList(), page, total);
        });
    }

    /** Add or update a supplier catalog entry. */
    public ItemResponse upsertItem(AuthContext actor, long supplierId,
                                   UpsertItemRequest request) {
        actor.require(Permission.SUPPLIER_WRITE);
        String sku = Validators.requireText(request.productSku(), "productSku", 40);
        java.math.BigDecimal unitCost = Validators.requireNonNegative(
                request.unitCost(), "unitCost");

        return tm.inTransaction(conn -> {
            supplierDao.findById(conn, supplierId)
                    .orElseThrow(() -> new NotFoundException("Supplier", supplierId));
            var product = productDao.findBySku(conn, sku.trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException("productSku",
                            "unknown product SKU: " + sku));
            SupplierItem item = new SupplierItem(supplierId, product.getId(),
                    request.supplierSku(), unitCost,
                    Boolean.TRUE.equals(request.preferred()),
                    request.leadTimeDays() == null ? 7
                            : Validators.requireIntInRange(request.leadTimeDays(),
                            "leadTimeDays", 0, 365));
            supplierDao.upsertItem(conn, item);
            audit.logAction(conn, actor, AuditLogs.SUPPLIER_ITEM_SAVED, "SUPPLIER",
                    supplierId, null, null, null);
            SupplierItem saved = supplierDao.findItem(conn, supplierId, product.getId())
                    .orElseThrow();
            return ItemResponse.from(saved, product.getSku(), product.getName());
        });
    }

    public void removeItem(AuthContext actor, long supplierId, long productId) {
        actor.require(Permission.SUPPLIER_WRITE);
        tm.inTransaction(conn -> {
            if (!supplierDao.deleteItem(conn, supplierId, productId)) {
                throw new NotFoundException("Supplier item", productId);
            }
            audit.logAction(conn, actor, AuditLogs.SUPPLIER_ITEM_REMOVED, "SUPPLIER",
                    supplierId, null, null, null);
            return null;
        });
    }

    public void delete(AuthContext actor, long id) {
        actor.require(Permission.SUPPLIER_WRITE);
        tm.inTransaction(conn -> {
            Supplier supplier = supplierDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Supplier", id));
            long poCount = supplierDao.countPurchaseOrders(conn, id);
            if (poCount > 0) {
                throw new ResourceInUseException("Supplier", id,
                        poCount + " purchase orders");
            }
            if (!supplierDao.delete(conn, id)) {
                throw new NotFoundException("Supplier", id);
            }
            audit.logAction(conn, actor, AuditLogs.SUPPLIER_DELETED, "SUPPLIER", id,
                    supplier.getCode(), Response.from(supplier), null);
            return null;
        });
    }

    private void applyRequest(Supplier s, String contactName, String email, String phone,
                              String addressLine1, String addressLine2, String city,
                              String stateProvince, String postalCode, String country,
                              Integer paymentTermsDays, Integer rating, String notes) {
        s.setContactName(Validators.optionalText(contactName, "contactName", 120));
        s.setEmail(Validators.optionalEmail(email, "email"));
        s.setPhone(Validators.optionalText(phone, "phone", 30));
        s.setAddressLine1(Validators.optionalText(addressLine1, "addressLine1", 200));
        s.setAddressLine2(Validators.optionalText(addressLine2, "addressLine2", 200));
        s.setCity(Validators.optionalText(city, "city", 100));
        s.setStateProvince(Validators.optionalText(stateProvince, "stateProvince", 100));
        s.setPostalCode(Validators.optionalText(postalCode, "postalCode", 20));
        s.setCountry(Validators.optionalText(country, "country", 60));
        if (paymentTermsDays != null) {
            s.setPaymentTermsDays(Validators.requireIntInRange(
                    paymentTermsDays, "paymentTermsDays", 0, 365));
        }
        if (rating != null) {
            s.setRating(Validators.requireIntInRange(rating, "rating", 1, 5));
        }
        s.setNotes(Validators.optionalText(notes, "notes", 1000));
    }
}
