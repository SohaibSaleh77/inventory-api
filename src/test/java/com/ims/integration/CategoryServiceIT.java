package com.ims.integration;

import com.ims.common.exception.NotFoundException;
import com.ims.common.exception.ResourceInUseException;
import com.ims.common.exception.ValidationException;
import com.ims.domain.category.dto.CategoryDtos.CreateRequest;
import com.ims.domain.category.dto.CategoryDtos.Response;
import com.ims.domain.category.dto.CategoryDtos.TreeNode;
import com.ims.domain.category.dto.CategoryDtos.UpdateRequest;
import com.ims.domain.product.dto.ProductDtos;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Category tree integrity: cycles, siblings, references, tree building. */
class CategoryServiceIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void buildsTreeHierarchy() {
        env.app.categoryService.create(users.manager,
                new CreateRequest(null, "ROOT-A", "Root A", null));
        env.app.categoryService.create(users.manager,
                new CreateRequest("ROOT-A", "CHILD-A1", "Child A1", null));
        env.app.categoryService.create(users.manager,
                new CreateRequest("CHILD-A1", "GRAND-A1", "Grand A1", null));

        List<TreeNode> tree = env.app.categoryService.tree(users.viewer, false);
        TreeNode root = tree.stream()
                .filter(n -> "ROOT-A".equals(n.code()))
                .findFirst().orElseThrow();
        assertEquals(1, root.children().size());
        assertEquals("CHILD-A1", root.children().get(0).code());
        assertEquals(1, root.children().get(0).children().size());
        assertEquals("GRAND-A1", root.children().get(0).children().get(0).code());
    }

    @Test
    void rejectsSelfParentAndCycles() {
        Response child = env.app.categoryService.create(users.manager,
                new CreateRequest(null, "CY-CHILD", "Cycle Child", null));
        // self parent
        assertThrows(ValidationException.class, () ->
                env.app.categoryService.update(users.manager, child.id(),
                        new UpdateRequest("CY-CHILD", "Cycle Child", null, null)),
                "category cannot be its own parent");
        // direct cycle: parent's parent set to its child
        Response grandchild = env.app.categoryService.create(users.manager,
                new CreateRequest("CY-CHILD", "CY-GRAND", "Cycle Grand", null));
        assertThrows(ValidationException.class, () ->
                env.app.categoryService.update(users.manager, child.id(),
                        new UpdateRequest("CY-GRAND", "Cycle Child", null, null)),
                "parent below its own descendant would create a cycle");
    }

    @Test
    void siblingNamesUniqueUnderSameParent() {
        env.app.categoryService.create(users.manager,
                new CreateRequest(null, "SIB-PARENT", "Sibling Parent", null));
        env.app.categoryService.create(users.manager,
                new CreateRequest("SIB-PARENT", "SIB-ONE", "Same Name", null));
        assertThrows(ValidationException.class, () ->
                env.app.categoryService.create(users.manager, new CreateRequest(
                        "SIB-PARENT", "SIB-TWO", "Same Name", null)),
                "duplicate sibling name rejected");
        // same name under a DIFFERENT parent is fine
        env.app.categoryService.create(users.manager,
                new CreateRequest(null, "SIB-OTHER", "Same Name", null));
    }

    @Test
    void duplicateCodeRejected() {
        env.app.categoryService.create(users.manager,
                new CreateRequest(null, "DUP-CODE", "First", null));
        assertThrows(ValidationException.class, () ->
                env.app.categoryService.create(users.manager,
                        new CreateRequest(null, "DUP-CODE", "Second", null)));
    }

    @Test
    void deleteProtectedByChildrenAndProducts() {
        Response parent = env.app.categoryService.create(users.manager,
                new CreateRequest(null, "DEL-PARENT", "Del Parent", null));
        env.app.categoryService.create(users.manager,
                new CreateRequest("DEL-PARENT", "DEL-CHILD", "Del Child", null));
        assertThrows(ResourceInUseException.class, () ->
                env.app.categoryService.delete(users.manager, parent.id()));

        Response leaf = env.app.categoryService.create(users.manager,
                new CreateRequest(null, "DEL-LEAF", "Del Leaf", null));
        fx.product(users.manager, fx.unique("PRD"), "DEL-LEAF",
                new java.math.BigDecimal("1"), new java.math.BigDecimal("2"), null);
        assertThrows(ResourceInUseException.class, () ->
                env.app.categoryService.delete(users.manager, leaf.id()),
                "category with products cannot be deleted");
    }

    @Test
    void unknownParentCodeRejected() {
        assertThrows(ValidationException.class, () ->
                env.app.categoryService.create(users.manager,
                        new CreateRequest("NOPE", "X", "X", null)));
    }

    @Test
    void unknownCategory404() {
        assertThrows(NotFoundException.class, () ->
                env.app.categoryService.get(users.viewer, 999999L));
    }
}
