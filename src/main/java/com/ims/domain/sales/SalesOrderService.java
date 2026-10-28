package com.ims.domain.sales;

import com.ims.common.exception.IllegalStateTransitionException;
import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.OptimisticLockException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Dates;
import com.ims.common.util.Money;
import com.ims.common.util.PageRequest;
import com.ims.common.util.Quantities;
import com.ims.common.util.Validators;
import com.ims.db.DocumentNumbers;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.customer.CustomerDao;
import com.ims.domain.product.ProductDao;
import com.ims.domain.sales.dto.SalesDtos.CreateRequest;
import com.ims.domain.sales.dto.SalesDtos.LineRequest;
import com.ims.domain.sales.dto.SalesDtos.LineResponse;
import com.ims.domain.sales.dto.SalesDtos.Response;
import com.ims.domain.sales.dto.SalesDtos.UpdateRequest;
import com.ims.domain.stock.StockService;
import com.ims.domain.warehouse.WarehouseDao;
import com.ims.domain.webhook.WebhookEvents;
import com.ims.domain.webhook.WebhookService;
import com.ims.security.AuthContext;
import com.ims.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sales order lifecycle:
 * DRAFT -&gt; CONFIRMED (reserves stock) -&gt; (PARTIALLY_)FULFILLED -&gt; CLOSED.
 * CANCELLED is reachable while not yet fulfilled, releasing reservations.
 */
public final class SalesOrderService {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderService.class);

    private final TransactionManager tm;
    private final SalesOrderDao soDao;
    private final CustomerDao customerDao;
    private final WarehouseDao warehouseDao;
    private final ProductDao productDao;
    private final StockService stockService;
    private final WebhookService webhookService;
    private final AuditLogger audit;
    private final DocumentNumbers documentNumbers;

    public SalesOrderService(TransactionManager tm, SalesOrderDao soDao,
                             CustomerDao customerDao, WarehouseDao warehouseDao,
                             ProductDao productDao, StockService stockService,
                             WebhookService webhookService, AuditLogger audit,
                             DocumentNumbers documentNumbers) {
        this.tm = tm;
        this.soDao = soDao;
        this.customerDao = customerDao;
        this.warehouseDao = warehouseDao;
        this.productDao = productDao;
        this.stockService = stockService;
        this.webhookService = webhookService;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.SALES_ORDER_WRITE);
        requireLines(request.lines());

        return tm.inTransaction(conn -> {
            var customer = findCustomer(conn, request.customerCode());
            var warehouse = findWarehouse(conn, request.warehouseCode());
            List<SalesOrderLine> lines = resolveLines(conn, request.lines());

            SalesOrder so = new SalesOrder(customer.getId(), warehouse.getId(),
                    Dates.today());
            so.setSoNo(documentNumbers.next(conn, DocumentNumbers.DocType.SALES_ORDER));
            so.setRequestedDate(request.requestedDate() == null
                    ? Dates.today().plusDays(7) : request.requestedDate());
            so.setNotes(Validators.optionalText(request.notes(), "notes", 500));
            so.setCreatedBy(actor.userId());
            computeTotals(so, lines);
            soDao.insert(conn, so);
            soDao.replaceLines(conn, so.getId(), lines);
            audit.logAction(conn, actor, AuditLogs.SO_CREATED, "SALES_ORDER", so.getId(),
                    so.getSoNo(), null, toResponse(conn, so.getId()));
            log.info("SO {} created by {} ({} lines, total {})", so.getSoNo(),
                    actor.username(), lines.size(), so.getTotal());
            return toResponse(conn, so.getId());
        });
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.SALES_ORDER_WRITE);
        requireLines(request.lines());

        return tm.inTransaction(conn -> {
            SalesOrder so = soDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Sales order", id));
            if (!so.isEditable()) {
                throw new IllegalStateTransitionException("Sales order", id,
                        so.getStatus(), "edit");
            }
            var before = toResponse(conn, id);

            var customer = findCustomer(conn, request.customerCode());
            var warehouse = findWarehouse(conn, request.warehouseCode());
            List<SalesOrderLine> lines = resolveLines(conn, request.lines());
            so.setCustomerId(customer.getId());
            so.setWarehouseId(warehouse.getId());
            so.setRequestedDate(request.requestedDate() == null
                    ? Dates.today().plusDays(7) : request.requestedDate());
            so.setNotes(Validators.optionalText(request.notes(), "notes", 500));
            computeTotals(so, lines);
            if (!soDao.update(conn, so)) {
                throw new OptimisticLockException("Sales order", id);
            }
            soDao.replaceLines(conn, id, lines);
            audit.logAction(conn, actor, AuditLogs.SO_UPDATED, "SALES_ORDER", id,
                    so.getSoNo(), before, toResponse(conn, id));
            return toResponse(conn, id);
        });
    }

    /**
     * Confirm: reserves stock for every line atomically. If any line cannot
     * reserve, the entire confirmation fails and nothing is held.
     */
    public Response confirm(AuthContext actor, long id) {
        actor.require(Permission.SALES_ORDER_WRITE);
        return tm.inTransaction(conn -> {
            SalesOrder so = soDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Sales order", id));
            if (!SalesOrder.STATUS_DRAFT.equals(so.getStatus())) {
                throw new IllegalStateTransitionException("Sales order", id,
                        so.getStatus(), "confirm");
            }
            List<SalesOrderLine> lines = soDao.findLines(conn, id);
            if (lines.isEmpty()) {
                throw new ValidationException("lines",
                        "cannot confirm an order without lines");
            }
            // Reserve everything; any failure rolls back the whole batch
            List<SalesOrderLine> updated = new ArrayList<>();
            for (SalesOrderLine line : lines) {
                stockService.reserve(conn, line.getProductId(), so.getWarehouseId(),
                        line.getQtyOrdered());
                SalesOrderLine copy = new SalesOrderLine(line.getSoId(), line.getLineNo(),
                        line.getProductId(), line.getQtyOrdered(), line.getUnitPrice(),
                        line.getDiscountPct(), line.getTaxPct(), line.getLineTotal());
                copy.setId(line.getId());
                copy.setQtyReserved(line.getQtyOrdered());
                copy.setQtyFulfilled(line.getQtyFulfilled());
                updated.add(copy);
                soDao.insertReservation(conn, new Reservation(so.getId(), line.getId(),
                        line.getProductId(), so.getWarehouseId(), line.getQtyOrdered()));
            }
            for (SalesOrderLine line : updated) {
                soDao.updateLineFulfilment(conn, line.getId(), line.getQtyReserved(),
                        line.getQtyFulfilled());
            }
            if (!soDao.markConfirmed(conn, id, actor.userId())) {
                throw new IllegalStateTransitionException("Sales order", id,
                        "DRAFT", "confirm");
            }
            audit.logAction(conn, actor, AuditLogs.SO_CONFIRMED, "SALES_ORDER", id,
                    so.getSoNo(), null, null);
            webhookService.enqueueEvent(conn, WebhookEvents.SALES_ORDER_CONFIRMED,
                    java.util.Map.of("soId", id, "soNo", so.getSoNo(),
                            "customerId", so.getCustomerId(),
                            "warehouseId", so.getWarehouseId()));
            log.info("SO {} confirmed by {} - stock reserved", so.getSoNo(),
                    actor.username());
            return toResponse(conn, id);
        });
    }

    public Response cancel(AuthContext actor, long id) {
        actor.require(Permission.SALES_ORDER_WRITE);
        return tm.inTransaction(conn -> {
            SalesOrder so = soDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Sales order", id));
            if (SalesOrder.STATUS_FULFILLED.equals(so.getStatus())
                    || SalesOrder.STATUS_CLOSED.equals(so.getStatus())
                    || SalesOrder.STATUS_CANCELLED.equals(so.getStatus())) {
                throw new IllegalStateTransitionException("Sales order", id,
                        so.getStatus(), "cancel");
            }
            boolean shippedAnything = soDao.findLines(conn, id).stream()
                    .anyMatch(l -> l.getQtyFulfilled().signum() > 0);
            if (shippedAnything) {
                throw new IllegalStateTransitionException("Sales order", id,
                        so.getStatus() + " (already shipped)", "cancel");
            }
            // Release all active reservations
            for (Reservation reservation : soDao.findActiveReservations(conn, id)) {
                stockService.releaseReservation(conn, reservation.getProductId(),
                        reservation.getWarehouseId(), reservation.getQtyReserved());
                soDao.updateReservationStatus(conn, reservation.getId(),
                        Reservation.STATUS_RELEASED);
            }
            for (SalesOrderLine line : soDao.findLines(conn, id)) {
                soDao.updateLineFulfilment(conn, line.getId(), BigDecimal.ZERO,
                        line.getQtyFulfilled());
            }
            if (!soDao.updateStatus(conn, id, so.getStatus(),
                    SalesOrder.STATUS_CANCELLED)) {
                throw new OptimisticLockException("Sales order", id);
            }
            audit.logAction(conn, actor, AuditLogs.SO_CANCELLED, "SALES_ORDER", id,
                    so.getSoNo(), null, null);
            log.info("SO {} cancelled by {}", so.getSoNo(), actor.username());
            return toResponse(conn, id);
        });
    }

    public Response close(AuthContext actor, long id) {
        actor.require(Permission.SALES_ORDER_WRITE);
        return tm.inTransaction(conn -> {
            SalesOrder so = soDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Sales order", id));
            if (!SalesOrder.STATUS_FULFILLED.equals(so.getStatus())) {
                throw new IllegalStateTransitionException("Sales order", id,
                        so.getStatus(), "close");
            }
            if (!soDao.updateStatus(conn, id, SalesOrder.STATUS_FULFILLED,
                    SalesOrder.STATUS_CLOSED)) {
                throw new OptimisticLockException("Sales order", id);
            }
            audit.logAction(conn, actor, AuditLogs.SO_CLOSED, "SALES_ORDER", id,
                    so.getSoNo(), null, null);
            return toResponse(conn, id);
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.SALES_ORDER_READ);
        return tm.withConnection(conn -> toResponse(conn, id));
    }

    public PageRequest.Page<Response> search(AuthContext actor, String status,
                                             Long customerId, Long warehouseId,
                                             String dateFrom, String dateTo,
                                             PageRequest page) {
        actor.require(Permission.SALES_ORDER_READ);
        return tm.withConnection(conn -> {
            long total = soDao.countSearch(conn, status, customerId, warehouseId,
                    dateFrom, dateTo);
            List<Response> content = new ArrayList<>();
            for (SalesOrder so : soDao.search(conn, status, customerId, warehouseId,
                    dateFrom, dateTo, page.size(), page.offset())) {
                content.add(toResponseReadonly(conn, so));
            }
            return PageRequest.Page.of(content, page, total);
        });
    }

    // ---- helpers ----

    private void requireLines(List<LineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException("lines", "at least one line is required");
        }
    }

    private com.ims.domain.customer.Customer findCustomer(Connection conn,
                                                          String customerCode)
            throws SQLException {
        String code = Validators.requireText(customerCode, "customerCode", 20)
                .trim().toUpperCase();
        var customer = customerDao.findByCode(conn, code)
                .orElseThrow(() -> new ValidationException("customerCode",
                        "unknown customer: " + code));
        if (!customer.isActive()) {
            throw new ValidationException("customerCode",
                    "customer is inactive: " + customer.getCode());
        }
        return customer;
    }

    private com.ims.domain.warehouse.Warehouse findWarehouse(Connection conn,
                                                             String warehouseCode)
            throws SQLException {
        String code = Validators.requireText(warehouseCode, "warehouseCode", 20)
                .trim().toUpperCase();
        var warehouse = warehouseDao.findByCode(conn, code)
                .orElseThrow(() -> new ValidationException("warehouseCode",
                        "unknown warehouse: " + code));
        if (!warehouse.isActive()) {
            throw new ValidationException("warehouseCode",
                    "warehouse is inactive: " + warehouse.getCode());
        }
        return warehouse;
    }

    private List<SalesOrderLine> resolveLines(Connection conn, List<LineRequest> requests)
            throws SQLException {
        List<SalesOrderLine> resolved = new ArrayList<>();
        int lineNo = 1;
        for (LineRequest request : requests) {
            String field = "lines[" + lineNo + "]";
            var product = productDao.findBySku(conn, Validators
                    .requireText(request.productSku(), field + ".productSku", 40)
                    .trim().toUpperCase())
                    .orElseThrow(() -> new ValidationException(field + ".productSku",
                            "unknown product SKU: " + request.productSku()));
            if (!product.isActive()) {
                throw new ValidationException(field + ".productSku",
                        "product is inactive: " + product.getSku());
            }
            BigDecimal qty = Quantities.of(Validators.requirePositive(request.qty(),
                    field + ".qty"));
            BigDecimal price = request.unitPrice() == null
                    ? Money.of(product.getListPrice())
                    : Money.of(Validators.requireNonNegative(
                            request.unitPrice(), field + ".unitPrice"));
            BigDecimal discountPct = request.discountPct() == null ? BigDecimal.ZERO
                    : Validators.requireNonNegative(request.discountPct(),
                    field + ".discountPct");
            Validators.requireIntInRange(discountPct.intValueExact(), field + ".discountPct", 0, 100);
            BigDecimal taxPct = request.taxPct() == null ? BigDecimal.ZERO
                    : Validators.requireNonNegative(request.taxPct(), field + ".taxPct");
            BigDecimal gross = Money.lineAmount(qty, price, discountPct);
            BigDecimal lineTotal = Money.add(gross, Money.tax(gross, taxPct));
            resolved.add(new SalesOrderLine(null, lineNo, product.getId(), qty, price,
                    discountPct, taxPct, lineTotal));
            lineNo++;
        }
        return resolved;
    }

    private void computeTotals(SalesOrder so, List<SalesOrderLine> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SalesOrderLine line : lines) {
            BigDecimal gross = Money.lineAmount(line.getQtyOrdered(),
                    line.getUnitPrice(), null);
            BigDecimal net = Money.lineAmount(line.getQtyOrdered(),
                    line.getUnitPrice(), line.getDiscountPct());
            discount = discount.add(gross.subtract(net));
            subtotal = subtotal.add(net);
            tax = tax.add(Money.tax(net, line.getTaxPct()));
        }
        so.setSubtotal(Money.of(subtotal));
        so.setDiscountTotal(Money.of(discount));
        so.setTaxTotal(Money.of(tax));
        so.setTotal(Money.add(so.getSubtotal(), so.getTaxTotal()));
    }

    private Response toResponse(Connection conn, long id) throws SQLException {
        SalesOrder so = soDao.findById(conn, id)
                .orElseThrow(() -> new NotFoundException("Sales order", id));
        return toResponseReadonly(conn, so);
    }

    private Response toResponseReadonly(Connection conn, SalesOrder so)
            throws SQLException {
        var customer = customerDao.findById(conn, so.getCustomerId());
        String customerCode = customer.map(c -> c.getCode()).orElse("?");
        String customerName = customer.map(c -> c.getName()).orElse("?");
        String warehouseCode = warehouseDao.findById(conn, so.getWarehouseId())
                .map(w -> w.getCode()).orElse("?");
        List<LineResponse> lines = new ArrayList<>();
        for (SalesOrderLine line : soDao.findLines(conn, so.getId())) {
            var product = productDao.findById(conn, line.getProductId());
            lines.add(LineResponse.from(line,
                    product.map(p -> p.getSku()).orElse("?"),
                    product.map(p -> p.getName()).orElse("?")));
        }
        return Response.from(so, customerCode, customerName, warehouseCode, lines);
    }
}
