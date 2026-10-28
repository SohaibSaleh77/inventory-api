package com.ims.domain.warehouse;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.OptimisticLockException;
import com.ims.common.exception.ResourceInUseException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Validators;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.warehouse.dto.WarehouseDtos.CreateRequest;
import com.ims.domain.warehouse.dto.WarehouseDtos.Response;
import com.ims.domain.warehouse.dto.WarehouseDtos.UpdateRequest;
import com.ims.security.AuthContext;
import com.ims.security.Permission;

import java.util.List;

/** Warehouse management. Warehouses with history can only be deactivated. */
public final class WarehouseService {

    private final TransactionManager tm;
    private final WarehouseDao warehouseDao;
    private final AuditLogger audit;

    public WarehouseService(TransactionManager tm, WarehouseDao warehouseDao,
                            AuditLogger audit) {
        this.tm = tm;
        this.warehouseDao = warehouseDao;
        this.audit = audit;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.WAREHOUSE_WRITE);
        String code = Validators.requireCode(request.code(), "code", 20);
        String name = Validators.requireText(request.name(), "name", 100);

        return tm.inTransaction(conn -> {
            if (warehouseDao.codeExists(conn, code)) {
                throw new ValidationException("code", "already in use");
            }
            Warehouse warehouse = new Warehouse(code, name);
            warehouse.setAddressLine1(Validators.optionalText(
                    request.addressLine1(), "addressLine1", 200));
            warehouse.setCity(Validators.optionalText(request.city(), "city", 100));
            warehouse.setStateProvince(Validators.optionalText(
                    request.stateProvince(), "stateProvince", 100));
            warehouse.setPostalCode(Validators.optionalText(
                    request.postalCode(), "postalCode", 20));
            warehouse.setCountry(Validators.optionalText(request.country(), "country", 60));
            Warehouse inserted = warehouseDao.insert(conn, warehouse);
            audit.logAction(conn, actor, AuditLogs.WAREHOUSE_CREATED, "WAREHOUSE",
                    inserted.getId(), code, null, Response.from(inserted));
            return Response.from(inserted);
        });
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.WAREHOUSE_WRITE);
        String name = Validators.requireText(request.name(), "name", 100);

        return tm.inTransaction(conn -> {
            Warehouse warehouse = warehouseDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Warehouse", id));
            Response before = Response.from(warehouse);

            warehouse.setName(name);
            if (request.addressLine1() != null) {
                warehouse.setAddressLine1(Validators.optionalText(
                        request.addressLine1(), "addressLine1", 200));
            }
            if (request.city() != null) {
                warehouse.setCity(Validators.optionalText(request.city(), "city", 100));
            }
            if (request.stateProvince() != null) {
                warehouse.setStateProvince(Validators.optionalText(
                        request.stateProvince(), "stateProvince", 100));
            }
            if (request.postalCode() != null) {
                warehouse.setPostalCode(Validators.optionalText(
                        request.postalCode(), "postalCode", 20));
            }
            if (request.country() != null) {
                warehouse.setCountry(Validators.optionalText(
                        request.country(), "country", 60));
            }
            if (request.active() != null) {
                warehouse.setActive(request.active());
            }
            if (!warehouseDao.update(conn, warehouse)) {
                throw new OptimisticLockException("Warehouse", id);
            }
            Warehouse updated = warehouseDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.WAREHOUSE_UPDATED, "WAREHOUSE", id,
                    updated.getCode(), before, Response.from(updated));
            return Response.from(updated);
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.WAREHOUSE_READ);
        return tm.withConnection(conn -> warehouseDao.findById(conn, id)
                .map(Response::from)
                .orElseThrow(() -> new NotFoundException("Warehouse", id)));
    }

    public List<Response> list(AuthContext actor, boolean includeInactive) {
        actor.require(Permission.WAREHOUSE_READ);
        return tm.withConnection(conn ->
                warehouseDao.findAll(conn, includeInactive).stream()
                        .map(Response::from).toList());
    }

    public void delete(AuthContext actor, long id) {
        actor.require(Permission.WAREHOUSE_WRITE);
        tm.inTransaction(conn -> {
            Warehouse warehouse = warehouseDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Warehouse", id));
            long references = warehouseDao.countReferences(conn, id);
            if (references > 0) {
                throw new ResourceInUseException("Warehouse", id,
                        references + " stock/document references");
            }
            if (!warehouseDao.delete(conn, id)) {
                throw new NotFoundException("Warehouse", id);
            }
            audit.logAction(conn, actor, AuditLogs.WAREHOUSE_DELETED, "WAREHOUSE", id,
                    warehouse.getCode(), Response.from(warehouse), null);
            return null;
        });
    }
}
