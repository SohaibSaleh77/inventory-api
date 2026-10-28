package com.ims.domain.category;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.OptimisticLockException;
import com.ims.common.exception.ResourceInUseException;
import com.ims.common.exception.ValidationException;
import com.ims.common.util.Validators;
import com.ims.db.TransactionManager;
import com.ims.domain.audit.AuditLogger;
import com.ims.domain.audit.AuditLogs;
import com.ims.domain.category.dto.CategoryDtos.CreateRequest;
import com.ims.domain.category.dto.CategoryDtos.Response;
import com.ims.domain.category.dto.CategoryDtos.TreeNode;
import com.ims.domain.category.dto.CategoryDtos.UpdateRequest;
import com.ims.security.AuthContext;
import com.ims.security.Permission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Category management with hierarchy integrity (no cycles, no self-parent). */
public final class CategoryService {

    private final TransactionManager tm;
    private final CategoryDao categoryDao;
    private final AuditLogger audit;

    public CategoryService(TransactionManager tm, CategoryDao categoryDao,
                           AuditLogger audit) {
        this.tm = tm;
        this.categoryDao = categoryDao;
        this.audit = audit;
    }

    public Response create(AuthContext actor, CreateRequest request) {
        actor.require(Permission.CATEGORY_WRITE);
        String code = Validators.requireCode(request.code(), "code", 30);
        String name = Validators.requireText(request.name(), "name", 100);
        String description = Validators.optionalText(request.description(), "description", 500);

        return tm.inTransaction(conn -> {
            if (categoryDao.codeExists(conn, code)) {
                throw new ValidationException("code", "already in use");
            }
            Long parentId = resolveParent(conn, request.parentCode());
            if (parentId != null && categoryDao.nameExistsUnderParent(conn, parentId, name)) {
                throw new ValidationException("name",
                        "a sibling category with this name already exists");
            }
            Category category = new Category(parentId, code, name, description);
            Category inserted = categoryDao.insert(conn, category);
            audit.logAction(conn, actor, AuditLogs.CATEGORY_CREATED, "CATEGORY",
                    inserted.getId(), code, null, Response.from(inserted));
            return Response.from(inserted);
        });
    }

    public Response update(AuthContext actor, long id, UpdateRequest request) {
        actor.require(Permission.CATEGORY_WRITE);
        String name = Validators.requireText(request.name(), "name", 100);
        String description = Validators.optionalText(request.description(), "description", 500);

        return tm.inTransaction(conn -> {
            Category category = categoryDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Category", id));
            Response before = Response.from(category);

            Long newParentId = resolveParent(conn, request.parentCode());
            if (newParentId != null) {
                if (newParentId.equals(id)) {
                    throw new ValidationException("parentCode",
                            "a category cannot be its own parent");
                }
                List<Long> ancestors = categoryDao.ancestorIds(conn, newParentId);
                if (ancestors.contains(id)) {
                    throw new ValidationException("parentCode",
                            "would create a cycle: parent is a descendant of this category");
                }
            }
            if (newParentId == null && category.getParentId() == null
                    || newParentId != null && newParentId.equals(category.getParentId())) {
                // parent unchanged; check name only against same parent scope
                if (!category.getName().equals(name)
                        && categoryDao.nameExistsUnderParent(conn, newParentId, name)) {
                    throw new ValidationException("name",
                            "a sibling category with this name already exists");
                }
            } else if (categoryDao.nameExistsUnderParent(conn, newParentId, name)) {
                throw new ValidationException("name",
                        "a sibling category with this name already exists");
            }

            category.setParentId(newParentId);
            category.setName(name);
            category.setDescription(description);
            if (request.active() != null) {
                category.setActive(request.active());
            }
            if (!categoryDao.update(conn, category)) {
                throw new OptimisticLockException("Category", id);
            }
            Category updated = categoryDao.findById(conn, id).orElseThrow();
            audit.logAction(conn, actor, AuditLogs.CATEGORY_UPDATED, "CATEGORY", id,
                    updated.getCode(), before, Response.from(updated));
            return Response.from(updated);
        });
    }

    public Response get(AuthContext actor, long id) {
        actor.require(Permission.CATEGORY_READ);
        return tm.withConnection(conn -> categoryDao.findById(conn, id)
                .map(Response::from)
                .orElseThrow(() -> new NotFoundException("Category", id)));
    }

    public List<Response> list(AuthContext actor, boolean includeInactive) {
        actor.require(Permission.CATEGORY_READ);
        return tm.withConnection(conn ->
                categoryDao.findAll(conn, includeInactive).stream()
                        .map(Response::from).toList());
    }

    /** Build the full category tree. Inactive nodes appear when requested. */
    public List<TreeNode> tree(AuthContext actor, boolean includeInactive) {
        actor.require(Permission.CATEGORY_READ);
        return tm.withConnection(conn -> {
            Map<Long, List<Category>> byParent = new LinkedHashMap<>();
            for (Category c : categoryDao.findAll(conn, includeInactive)) {
                Long parentKey = c.getParentId() == null ? 0L : c.getParentId();
                byParent.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(c);
            }
            return buildChildren(byParent, 0L);
        });
    }

    private List<TreeNode> buildChildren(Map<Long, List<Category>> byParent, long parentId) {
        List<TreeNode> nodes = new ArrayList<>();
        for (Category child : byParent.getOrDefault(parentId, List.of())) {
            nodes.add(TreeNode.from(child, buildChildren(byParent, child.getId())));
        }
        return nodes;
    }

    /** Hard delete only when unreferenced (no children, no products). */
    public void delete(AuthContext actor, long id) {
        actor.require(Permission.CATEGORY_WRITE);
        tm.inTransaction(conn -> {
            Category category = categoryDao.findById(conn, id)
                    .orElseThrow(() -> new NotFoundException("Category", id));
            long children = categoryDao.countChildren(conn, id);
            long products = categoryDao.countProducts(conn, id);
            if (children > 0) {
                throw new ResourceInUseException("Category", id,
                        children + " subcategories");
            }
            if (products > 0) {
                throw new ResourceInUseException("Category", id,
                        products + " products");
            }
            if (!categoryDao.delete(conn, id)) {
                throw new NotFoundException("Category", id);
            }
            audit.logAction(conn, actor, AuditLogs.CATEGORY_DELETED, "CATEGORY", id,
                    category.getCode(), Response.from(category), null);
            return null;
        });
    }

    private Long resolveParent(java.sql.Connection conn, String parentCode)
            throws java.sql.SQLException {
        if (parentCode == null || parentCode.isBlank()) {
            return null;
        }
        return categoryDao.findByCode(conn, parentCode.trim().toUpperCase())
                .orElseThrow(() -> new ValidationException("parentCode",
                        "unknown category code: " + parentCode))
                .getId();
    }
}
