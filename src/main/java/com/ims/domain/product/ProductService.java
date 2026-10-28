package com.ims.domain.product;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.PayloadTooLargeException;
import com.ims.common.exception.OptimisticLockException;
import com.ims.common.exception.ResourceInUseException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Csv;
import com.ims.common.util.Money;
import com.ims.common.util.PageRequest;
import com.ims.common.util.Quantities;
import com.ims.common.util.Validators;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.category.CategoryDao;
import com.ims.domain.product.dto.ProductDtos.CreateRequest;
import com.ims.domain.product.dto.ProductDtos.Response;
import com.ims.domain.product.dto.ProductDtos.UpdateRequest;
import com.ims.security.AuthContext;
import com.ims.security.Permission;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Product master data management. */
public final class ProductService {

    private final TransactionManager tm;
    private final ProductDao productDao;
    private final CategoryDao categoryDao;
    private final AuditLogger audit;

    public ProductService(TransactionManager tm, ProductDao productDao,
                          CategoryDao categoryDao, AuditLogger audit) {
        this.tm = tm;
        this.productDao = productDao;
        this.categoryDao = categoryDao;
        this.audit = audit;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.PRODUCT_WRITE);
        String sku = Validators.requireCode(request.sku(), "sku", 40);
        String name = Validators.requireText(request.name(), "name", 200);

        return tm.inTransaction(conn -> {
            if (productDao.skuExists(conn, sku)) {
                throw new ValidationException("sku", "already in use");
            }
            String barcode = normalizeBarcode(request.barcode());
            if (barcode != null && productDao.barcodeExists(conn, barcode)) {
                throw new ValidationException("barcode", "already in use");
            }
            Long categoryId = resolveCategory(conn, request.categoryCode());
            String unit = normalizeUnit(request.unitOfMeasure());

            Product product = new Product(sku, name);
            product.setBarcode(barcode);
            product.setDescription(Validators.optionalText(
                    request.description(), "description", 2000));
            product.setCategoryId(categoryId);
            product.setUnitOfMeasure(unit);
            product.setStandardCost(Money.of(Validators.optionalNonNegative(
                    request.standardCost(), "standardCost")));
            product.setListPrice(Money.of(Validators.optionalNonNegative(
                    request.listPrice(), "listPrice")));
            product.setReorderPoint(Quantities.of(Validators.optionalNonNegative(
                    request.reorderPoint(), "reorderPoint")));
            product.setReorderQty(Quantities.of(Validators.optionalNonNegative(
                    request.reorderQty(), "reorderQty")));
            Product inserted = productDao.insert(conn, product);
            audit.logAction(conn, actor, AuditLogs.PRODUCT_CREATED, "PRODUCT",
                    inserted.getId(), sku, null, Response.from(inserted));
            return Response.from(inserted);
        });
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.PRODUCT_WRITE);
        String name = Validators.requireText(request.name(), "name", 200);

        return tm.inTransaction(conn -> {
            Product product = productDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Product", id));
            Response before = Response.from(product);

            if (request.barcode() != null) {
                String barcode = normalizeBarcode(request.barcode());
                if (barcode != null && !barcode.equals(product.getBarcode())
                        && productDao.barcodeExists(conn, barcode)) {
                    throw new ValidationException("barcode", "already in use");
                }
                product.setBarcode(barcode);
            }
            product.setName(name);
            if (request.description() != null) {
                product.setDescription(Validators.optionalText(
                        request.description(), "description", 2000));
            }
            if (request.categoryCode() != null) {
                product.setCategoryId(resolveCategory(conn, request.categoryCode()));
            }
            if (request.unitOfMeasure() != null) {
                product.setUnitOfMeasure(normalizeUnit(request.unitOfMeasure()));
            }
            if (request.standardCost() != null) {
                product.setStandardCost(Money.of(Validators.optionalNonNegative(
                        request.standardCost(), "standardCost")));
            }
            if (request.listPrice() != null) {
                product.setListPrice(Money.of(Validators.optionalNonNegative(
                        request.listPrice(), "listPrice")));
            }
            if (request.reorderPoint() != null) {
                product.setReorderPoint(Quantities.of(Validators.optionalNonNegative(
                        request.reorderPoint(), "reorderPoint")));
            }
            if (request.reorderQty() != null) {
                product.setReorderQty(Quantities.of(Validators.optionalNonNegative(
                        request.reorderQty(), "reorderQty")));
            }
            if (request.active() != null) {
                product.setActive(request.active());
            }
            if (!productDao.update(conn, product)) {
                throw new OptimisticLockException("Product", id);
            }
            Product updated = productDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.PRODUCT_UPDATED, "PRODUCT", id,
                    updated.getSku(), before, Response.from(updated));
            return Response.from(updated);
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.PRODUCT_READ);
        return tm.withConnection(conn -> productDao.findById(conn, id)
                .map(Response::from)
                .orElseThrow(() -> new NotFoundException("Product", id)));
    }

    public Response getBySku(AuthContext actor, String sku) {
        actor.require(Permission.PRODUCT_READ);
        return tm.withConnection(conn -> productDao.findBySku(conn, sku.trim().toUpperCase())
                .map(Response::from)
                .orElseThrow(() -> new NotFoundException("Product", sku)));
    }

    /** Scanner-style lookup: resolve a product by its barcode. */
    public Response getByBarcode(AuthContext actor, String barcode) {
        actor.require(Permission.PRODUCT_READ);
        String code = Validators.requireText(barcode, "barcode", 60).trim();
        return tm.withConnection(conn -> productDao.findByBarcode(conn, code)
                .map(Response::from)
                .orElseThrow(() -> new NotFoundException("Product barcode", code)));
    }

    public PageRequest.Page<Response> search(AuthContext actor, String search,
                                             String categoryCode, Boolean active,
                                             String sortBy, PageRequest page) {
        actor.require(Permission.PRODUCT_READ);
        return tm.withConnection(conn -> {
            Long categoryId = null;
            if (categoryCode != null && !categoryCode.isBlank()) {
                categoryId = categoryDao.findByCode(conn, categoryCode.trim().toUpperCase())
                        .orElseThrow(() -> new ValidationException("categoryCode",
                                "unknown category: " + categoryCode))
                        .getId();
            }
            ProductDao.ProductQuery query = new ProductDao.ProductQuery(
                    search, categoryId, active, null, sortBy);
            long total = productDao.countSearch(conn, query);
            var products = productDao.search(conn, query, page.size(), page.offset());
            return PageRequest.Page.of(
                    products.stream().map(Response::from).toList(), page, total);
        });
    }

    public void delete(AuthContext actor, long id) {
        actor.require(Permission.PRODUCT_WRITE);
        tm.inTransaction(conn -> {
            Product product = productDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Product", id));
            long references = productDao.countReferences(conn, id);
            if (references > 0) {
                throw new ResourceInUseException("Product", id,
                        references + " transactional references");
            }
            if (!productDao.delete(conn, id)) {
                throw new NotFoundException("Product", id);
            }
            audit.logAction(conn, actor, AuditLogs.PRODUCT_DELETED, "PRODUCT", id,
                    product.getSku(), Response.from(product), null);
            return null;
        });
    }

    private String normalizeBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        String value = barcode.trim();
        if (value.length() < 4 || value.length() > 60) {
            throw new ValidationException("barcode", "length must be 4-60");
        }
        return value;
    }

    private String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return "EA";
        }
        String value = unit.trim().toUpperCase();
        Set<String> allowed = Product.allowedUnits();
        if (!allowed.contains(value)) {
            throw new ValidationException("unitOfMeasure", "must be one of " + allowed);
        }
        return value;
    }

    private Long resolveCategory(java.sql.Connection conn, String categoryCode)
            throws java.sql.SQLException {
        if (categoryCode == null || categoryCode.isBlank()) {
            return null;
        }
        return categoryDao.findByCode(conn, categoryCode.trim().toUpperCase())
                .orElseThrow(() -> new ValidationException("categoryCode",
                        "unknown category code: " + categoryCode))
                .getId();
    }

    // ---- bulk import ----

    /** Hard cap on imported rows: protects the transaction and the audit table. */
    public static final int IMPORT_MAX_ROWS = 10_000;

    /** Per-row outcome for the import report. */
    public record ImportRowError(int line, String sku, String message) {
    }

    public record ImportResult(int created, int skipped, List<String> createdSkus,
                               List<ImportRowError> errors) {
    }

    /**
     * Bulk product onboarding from CSV text. The header row is required and
     * must contain at least sku and name; optional columns follow the create
     * request fields. Valid rows are inserted in ONE transaction; invalid
     * rows are reported and skipped. Duplicate SKUs (in file or database)
     * are skipped, not merged - the import only creates.
     */
    public ImportResult importProducts(AuthContext actor, String csvText) {
        actor.require(Permission.PRODUCT_WRITE);
        if (csvText == null || csvText.isBlank()) {
            throw new ValidationException("file", "CSV body is required");
        }
        List<List<String>> rows = Csv.parse(csvText);
        if (rows.isEmpty()) {
            throw new ValidationException("file", "CSV body is empty");
        }
        // strip a UTF-8 BOM on the header cell
        String first = rows.get(0).get(0);
        if (first != null && first.startsWith("\uFEFF")) {
            rows.get(0).set(0, first.substring(1));
        }
        List<String> header = rows.get(0).stream()
                .map(cell -> cell == null ? "" : cell.trim().toLowerCase())
                .toList();
        if (!header.contains("sku") || !header.contains("name")) {
            throw new ValidationException("file",
                    "header must contain at least the columns: sku, name");
        }
        List<List<String>> dataRows = rows.subList(1, rows.size());
        if (dataRows.size() > IMPORT_MAX_ROWS) {
            throw new PayloadTooLargeException("import accepts at most "
                    + IMPORT_MAX_ROWS + " rows, got " + dataRows.size());
        }

        return tm.inTransaction(conn -> {
            List<Response> created = new ArrayList<>();
            List<ImportRowError> errors = new ArrayList<>();
            Set<String> seenSkus = new HashSet<>();
            for (int index = 0; index < dataRows.size(); index++) {
                int lineNo = index + 2; // header occupies line 1
                List<String> row = dataRows.get(index);
                String sku = cell(row, header, "sku");
                String name = cell(row, header, "name");
                try {
                    String validatedSku = Validators.requireCode(sku, "sku", 40);
                    String validatedName = Validators.requireText(name, "name", 200);
                    if (seenSkus.contains(validatedSku)) {
                        throw new ValidationException("sku",
                                "duplicate sku within the file: " + validatedSku);
                    }
                    if (productDao.skuExists(conn, validatedSku)) {
                        throw new ValidationException("sku",
                                "already exists: " + validatedSku);
                    }
                    String barcode = normalizeBarcode(cell(row, header, "barcode"));
                    if (barcode != null && productDao.barcodeExists(conn, barcode)) {
                        throw new ValidationException("barcode",
                                "already in use: " + barcode);
                    }
                    Product product = new Product(validatedSku, validatedName);
                    product.setBarcode(barcode);
                    product.setDescription(Validators.optionalText(
                            cell(row, header, "description"), "description", 2000));
                    product.setCategoryId(resolveCategory(conn,
                            cell(row, header, "categoryCode")));
                    product.setUnitOfMeasure(normalizeUnit(
                            cell(row, header, "unitOfMeasure")));
                    product.setStandardCost(Money.of(parseOptionalDecimal(
                            cell(row, header, "standardCost"), "standardCost")));
                    product.setListPrice(Money.of(parseOptionalDecimal(
                            cell(row, header, "listPrice"), "listPrice")));
                    product.setReorderPoint(Quantities.of(parseOptionalDecimal(
                            cell(row, header, "reorderPoint"), "reorderPoint")));
                    product.setReorderQty(Quantities.of(parseOptionalDecimal(
                            cell(row, header, "reorderQty"), "reorderQty")));
                    Product inserted = productDao.insert(conn, product);
                    seenSkus.add(validatedSku);
                    audit.logAction(conn, actor, AuditLogs.PRODUCT_CREATED,
                            "PRODUCT", inserted.getId(), validatedSku, null,
                            Response.from(inserted));
                    created.add(Response.from(inserted));
                } catch (ValidationException e) {
                    errors.add(new ImportRowError(lineNo, sku,
                            e.fieldErrors().isEmpty()
                                    ? e.getMessage()
                                    : e.fieldErrors().toString()));
                }
            }
            audit.logAction(conn, actor, AuditLogs.PRODUCTS_IMPORTED, "PRODUCT",
                    null, null, null, java.util.Map.of(
                            "created", created.size(),
                            "rows", dataRows.size(),
                            "errors", errors.size()));
            List<String> skus = created.stream()
                    .map(response -> response.sku()).toList();
            return new ImportResult(created.size(), dataRows.size() - created.size(),
                    skus, errors);
        });
    }

    private static String cell(List<String> row, List<String> header, String column) {
        // header cells are lowercased during parsing; accept any case
        int index = header.indexOf(column.toLowerCase());
        if (index < 0 || index >= row.size()) {
            return null;
        }
        String value = row.get(index);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal parseOptionalDecimal(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ValidationException(field,
                    "not a valid number: " + value);
        }
    }
}
