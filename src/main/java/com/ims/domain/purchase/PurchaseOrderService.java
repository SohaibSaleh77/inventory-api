package com.ims.domain.purchase;

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
import com.ims.domain.product.ProductDao;
import com.ims.domain.purchase.dto.PurchaseDtos.CreateRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.LineRequest;
import com.ims.domain.purchase.dto.PurchaseDtos.LineResponse;
import com.ims.domain.purchase.dto.PurchaseDtos.Response;
import com.ims.domain.purchase.dto.PurchaseDtos.UpdateRequest;
import com.ims.domain.supplier.SupplierDao;
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
 * Purchase order lifecycle:
 * DRAFT -&gt; SUBMITTED -&gt; APPROVED -&gt; (PARTIALLY_)RECEIVED -&gt; CLOSED,
 * with CANCELLED reachable from all non-closed states (approved orders only
 * when nothing has been received yet).
 */
public final class PurchaseOrderService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);

    private final TransactionManager tm;
    private final PurchaseOrderDao poDao;
    private final SupplierDao supplierDao;
    private final WarehouseDao warehouseDao;
    private final ProductDao productDao;
    private final WebhookService webhookService;
    private final AuditLogger audit;
    private final DocumentNumbers documentNumbers;

    public PurchaseOrderService(TransactionManager tm, PurchaseOrderDao poDao,
                                SupplierDao supplierDao, WarehouseDao warehouseDao,
                                ProductDao productDao, WebhookService webhookService,
                                AuditLogger audit,
                                DocumentNumbers documentNumbers) {
        this.tm = tm;
        this.poDao = poDao;
        this.supplierDao = supplierDao;
        this.warehouseDao = warehouseDao;
        this.productDao = productDao;
        this.webhookService = webhookService;
        this.audit = audit;
        this.documentNumbers = documentNumbers;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.PURCHASE_ORDER_WRITE);
        requireNonEmptyLines(request.lines());

        return tm.inTransaction(conn -> {
            var supplier = findSupplier(conn, request.supplierCode());
            var warehouse = findWarehouse(conn, request.warehouseCode());
            List<PurchaseOrderLine> lines = resolveLines(conn, request.lines());

            PurchaseOrder po = insertDraft(conn, actor, supplier.getId(),
                    warehouse.getId(), parseExpectedDate(request.expectedDate()),
                    parseCurrency(request.currency()),
                    Validators.optionalText(request.notes(), "notes", 500), lines);
            log.info("PO {} created by {} ({} lines, total {})", po.getPoNo(),
                    actor.username(), lines.size(), po.getTotal());
            return toResponse(conn, po.getId());
        });
    }

    /**
     * Replenishment shortcut: turn the current reorder proposals into one
     * DRAFT purchase order per supplier (or a single supplier when given).
     * Lines without a supplier catalog entry are skipped and reported.
     */
    public ReorderDrafts createFromReorder(AuthContext actor, String warehouseCode,
                                           String supplierCode) {
        actor.require(Permission.PURCHASE_ORDER_WRITE);
        String normalizedWarehouse = Validators.requireText(
                warehouseCode, "warehouseCode", 20).trim().toUpperCase();
        String normalizedSupplier = supplierCode == null || supplierCode.isBlank()
                ? null : supplierCode.trim().toUpperCase();

        return tm.inTransaction(conn -> {
            var warehouse = warehouseDao.findByCode(conn, normalizedWarehouse)
                    .orElseThrow(() -> new ValidationException("warehouseCode",
                            "unknown warehouse code: " + normalizedWarehouse));
            if (!warehouse.isActive()) {
                throw new ValidationException("warehouseCode",
                        "warehouse is inactive: " + warehouse.getCode());
            }
            Long supplierId = null;
            if (normalizedSupplier != null) {
                var supplier = supplierDao.findByCode(conn, normalizedSupplier)
                        .orElseThrow(() -> new ValidationException("supplierCode",
                                "unknown supplier code: " + normalizedSupplier));
                supplierId = supplier.getId();
            }

            List<PurchaseOrderDao.ReorderRow> rows =
                    poDao.reorderProposals(conn, supplierId);
            // actionable rows only: a positive suggested quantity and a
            // supplier catalog entry to buy from
            List<PurchaseOrderDao.ReorderRow> actionable = rows.stream()
                    .filter(row -> row.supplierId() != null)
                    .filter(row -> row.suggestedQty().signum() > 0)
                    .toList();
            int skipped = rows.size() - actionable.size();
            if (actionable.isEmpty()) {
                return new ReorderDrafts(List.of(), 0, skipped);
            }

            // group proposal rows by supplier; one draft PO each
            java.util.LinkedHashMap<Long, List<PurchaseOrderDao.ReorderRow>> bySupplier =
                    new java.util.LinkedHashMap<>();
            for (PurchaseOrderDao.ReorderRow row : actionable) {
                bySupplier.computeIfAbsent(row.supplierId(),
                        key -> new ArrayList<>()).add(row);
            }

            List<Response> created = new ArrayList<>();
            for (var entry : bySupplier.entrySet()) {
                List<PurchaseOrderLine> lines = new ArrayList<>();
                int lineNo = 1;
                for (PurchaseOrderDao.ReorderRow row : entry.getValue()) {
                    BigDecimal cost = row.unitCost() == null
                            ? Money.of(BigDecimal.ZERO) : Money.of(row.unitCost());
                    BigDecimal lineTotal = Money.lineAmount(row.suggestedQty(),
                            cost, null);
                    lines.add(new PurchaseOrderLine(null, lineNo++,
                            row.productId(), row.suggestedQty(), cost,
                            BigDecimal.ZERO, lineTotal));
                }
                PurchaseOrder po = insertDraft(conn, actor, entry.getKey(),
                        warehouse.getId(), null, null,
                        "generated from reorder proposals", lines);
                created.add(toResponse(conn, po.getId()));
                log.info("PO {} generated from reorder proposals by {} ({} lines)",
                        po.getPoNo(), actor.username(), lines.size());
            }
            return new ReorderDrafts(created, created.stream()
                    .mapToInt(response -> response.lines().size()).sum(), skipped);
        });
    }

    /** One DRAFT purchase order written with its lines, totals and audit. */
    private PurchaseOrder insertDraft(Connection conn, AuthContext actor,
                                      long supplierId, long warehouseId,
                                      java.time.LocalDate expectedDate, String currency,
                                      String notes, List<PurchaseOrderLine> lines)
            throws SQLException {
        PurchaseOrder po = new PurchaseOrder(supplierId, warehouseId, Dates.today());
        po.setPoNo(documentNumbers.next(conn, DocumentNumbers.DocType.PURCHASE_ORDER));
        po.setExpectedDate(expectedDate);
        po.setCurrency(currency == null || currency.isBlank() ? "USD" : currency);
        po.setNotes(notes);
        po.setCreatedBy(actor.userId());
        computeTotals(po, lines);
        poDao.insert(conn, po);
        poDao.replaceLines(conn, po.getId(), lines);
        audit.logAction(conn, actor, AuditLogs.PO_CREATED, "PURCHASE_ORDER",
                po.getId(), po.getPoNo(), null, toResponse(conn, po.getId()));
        return po;
    }

    /** Draft POs generated from reorder proposals. */
    public record ReorderDrafts(List<Response> purchaseOrders, int totalLines,
                                int skippedLines) {
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.PURCHASE_ORDER_WRITE);
        requireNonEmptyLines(request.lines());

        return tm.inTransaction(conn -> {
            PurchaseOrder po = poDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Purchase order", id));
            if (!po.isEditable()) {
                throw new IllegalStateTransitionException("Purchase order", id,
                        po.getStatus(), "edit");
            }
            var before = toResponse(conn, id);

            var supplier = findSupplier(conn, request.supplierCode());
            var warehouse = findWarehouse(conn, request.warehouseCode());
            List<PurchaseOrderLine> lines = resolveLines(conn, request.lines());
            po.setSupplierId(supplier.getId());
            po.setWarehouseId(warehouse.getId());
            po.setExpectedDate(parseExpectedDate(request.expectedDate()));
            po.setNotes(Validators.optionalText(request.notes(), "notes", 500));
            computeTotals(po, lines);
            if (!poDao.update(conn, po)) {
                throw new OptimisticLockException("Purchase order", id);
            }
            poDao.replaceLines(conn, id, lines);
            audit.logAction(conn, actor, AuditLogs.PO_UPDATED, "PURCHASE_ORDER", id,
                    po.getPoNo(), before, toResponse(conn, id));
            return toResponse(conn, id);
        });
    }

    public Response submit(AuthContext actor, long id) {
        actor.require(Permission.PURCHASE_ORDER_WRITE);
        return transition(actor, id, PurchaseOrder.STATUS_DRAFT,
                PurchaseOrder.STATUS_SUBMITTED, AuditLogs.PO_SUBMITTED, "submit");
    }

    public Response approve(AuthContext actor, long id) {
        actor.require(Permission.PURCHASE_ORDER_APPROVE);
        return tm.inTransaction(conn -> {
            PurchaseOrder po = poDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Purchase order", id));
            if (!PurchaseOrder.STATUS_SUBMITTED.equals(po.getStatus())) {
                throw new IllegalStateTransitionException("Purchase order", id,
                        po.getStatus(), "approve");
            }
            if (poDao.findLines(conn, id).isEmpty()) {
                throw new ValidationException("lines",
                        "cannot approve an order without lines");
            }
            if (!poDao.markApproved(conn, id, actor.userId())) {
                throw new IllegalStateTransitionException("Purchase order", id,
                        "SUBMITTED", "approve");
            }
            audit.logAction(conn, actor, AuditLogs.PO_APPROVED, "PURCHASE_ORDER", id,
                    po.getPoNo(), null, null);
            webhookService.enqueueEvent(conn, WebhookEvents.PURCHASE_ORDER_APPROVED,
                    java.util.Map.of("poId", id, "poNo", po.getPoNo(),
                            "supplierId", po.getSupplierId(),
                            "warehouseId", po.getWarehouseId()));
            log.info("PO {} approved by {}", po.getPoNo(), actor.username());
            return toResponse(conn, id);
        });
    }

    public Response cancel(AuthContext actor, long id) {
        actor.require(Permission.PURCHASE_ORDER_WRITE);
        return tm.inTransaction(conn -> {
            PurchaseOrder po = poDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Purchase order", id));
            if (PurchaseOrder.STATUS_CLOSED.equals(po.getStatus())
                    || PurchaseOrder.STATUS_CANCELLED.equals(po.getStatus())
                    || PurchaseOrder.STATUS_RECEIVED.equals(po.getStatus())) {
                throw new IllegalStateTransitionException("Purchase order", id,
                        po.getStatus(), "cancel");
            }
            boolean receivedAnything = poDao.findLines(conn, id).stream()
                    .anyMatch(l -> l.getQtyReceived().signum() > 0);
            if (receivedAnything) {
                throw new IllegalStateTransitionException("Purchase order", id,
                        po.getStatus() + " (already has receipts)", "cancel");
            }
            if (!poDao.updateStatus(conn, id, po.getStatus(),
                    PurchaseOrder.STATUS_CANCELLED)) {
                throw new OptimisticLockException("Purchase order", id);
            }
            audit.logAction(conn, actor, AuditLogs.PO_CANCELLED, "PURCHASE_ORDER", id,
                    po.getPoNo(), null, null);
            return toResponse(conn, id);
        });
    }

    public Response close(AuthContext actor, long id) {
        actor.require(Permission.PURCHASE_ORDER_WRITE);
        return transition(actor, id, PurchaseOrder.STATUS_RECEIVED,
                PurchaseOrder.STATUS_CLOSED, AuditLogs.PO_CLOSED, "close");
    }

    private Response transition(AuthContext actor, long id, String from, String to,
                                String auditAction, String verb) {
        return tm.inTransaction(conn -> {
            PurchaseOrder po = poDao.lockById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Purchase order", id));
            if (!from.equals(po.getStatus())) {
                throw new IllegalStateTransitionException("Purchase order", id,
                        po.getStatus(), verb);
            }
            if (!poDao.updateStatus(conn, id, from, to)) {
                throw new OptimisticLockException("Purchase order", id);
            }
            audit.logAction(conn, actor, auditAction, "PURCHASE_ORDER", id,
                    po.getPoNo(), null, null);
            return toResponse(conn, id);
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.PURCHASE_ORDER_READ);
        return tm.withConnection(conn -> toResponse(conn, id));
    }

    public PageRequest.Page<Response> search(AuthContext actor, String status,
                                             Long supplierId, Long warehouseId,
                                             String dateFrom, String dateTo,
                                             PageRequest page) {
        actor.require(Permission.PURCHASE_ORDER_READ);
        return tm.withConnection(conn -> {
            long total = poDao.countSearch(conn, status, supplierId, warehouseId,
                    dateFrom, dateTo);
            List<Response> content = new ArrayList<>();
            for (PurchaseOrder po : poDao.search(conn, status, supplierId, warehouseId,
                    dateFrom, dateTo, page.size(), page.offset())) {
                content.add(toResponseReadonly(conn, po));
            }
            return PageRequest.Page.of(content, page, total);
        });
    }

    // ---- helpers ----

    private void requireNonEmptyLines(List<LineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException("lines", "at least one line is required");
        }
    }

    private com.ims.domain.supplier.Supplier findSupplier(Connection conn,
                                                          String supplierCode)
            throws SQLException {
        String code = Validators.requireText(supplierCode, "supplierCode", 20)
                .trim().toUpperCase();
        var supplier = supplierDao.findByCode(conn, code)
                .orElseThrow(() -> new ValidationException("supplierCode",
                        "unknown supplier: " + code));
        if (!supplier.isActive()) {
            throw new ValidationException("supplierCode",
                    "supplier is inactive: " + supplier.getCode());
        }
        return supplier;
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

    private java.time.LocalDate parseExpectedDate(java.time.LocalDate value) {
        if (value == null) {
            return Dates.today().plusDays(14);
        }
        return value;
    }

    private String parseCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "USD";
        }
        String value = currency.trim().toUpperCase();
        if (!value.matches("[A-Z]{3}")) {
            throw new ValidationException("currency", "must be a 3-letter ISO code");
        }
        return value;
    }

    /** Resolve product SKUs to ids and compute line totals (in transaction). */
    private List<PurchaseOrderLine> resolveLines(Connection conn,
                                                 List<LineRequest> requests)
            throws SQLException {
        List<PurchaseOrderLine> resolved = new ArrayList<>();
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
            BigDecimal cost = Money.of(Validators.requireNonNegative(request.unitCost(),
                    field + ".unitCost"));
            BigDecimal taxPct = request.taxPct() == null ? BigDecimal.ZERO
                    : Validators.requireNonNegative(request.taxPct(),
                    field + ".taxPct");
            BigDecimal gross = Money.lineAmount(qty, cost, null);
            BigDecimal lineTotal = Money.add(gross, Money.tax(gross, taxPct));
            resolved.add(new PurchaseOrderLine(null, lineNo, product.getId(), qty, cost,
                    taxPct, lineTotal));
            lineNo++;
        }
        return resolved;
    }

    private void computeTotals(PurchaseOrder po, List<PurchaseOrderLine> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (PurchaseOrderLine line : lines) {
            BigDecimal gross = Money.lineAmount(line.getQtyOrdered(), line.getUnitCost(),
                    null);
            subtotal = subtotal.add(gross);
            tax = tax.add(Money.tax(gross, line.getTaxPct()));
        }
        po.setSubtotal(Money.of(subtotal));
        po.setTaxTotal(Money.of(tax));
        po.setTotal(Money.add(po.getSubtotal(), po.getTaxTotal()));
    }

    private Response toResponse(Connection conn, long id) throws SQLException {
        PurchaseOrder po = poDao.findById(conn, id)
                .orElseThrow(() -> new NotFoundException("Purchase order", id));
        return toResponseReadonly(conn, po);
    }

    private Response toResponseReadonly(Connection conn, PurchaseOrder po)
            throws SQLException {
        String supplierCode = supplierDao.findById(conn, po.getSupplierId())
                .map(s -> s.getCode()).orElse("?");
        String supplierName = supplierDao.findById(conn, po.getSupplierId())
                .map(s -> s.getName()).orElse("?");
        String warehouseCode = warehouseDao.findById(conn, po.getWarehouseId())
                .map(w -> w.getCode()).orElse("?");
        List<LineResponse> lines = new ArrayList<>();
        for (PurchaseOrderLine line : poDao.findLines(conn, po.getId())) {
            var product = productDao.findById(conn, line.getProductId());
            lines.add(LineResponse.from(line,
                    product.map(p -> p.getSku()).orElse("?"),
                    product.map(p -> p.getName()).orElse("?")));
        }
        return Response.from(po, supplierCode, supplierName, warehouseCode, lines);
    }
}
