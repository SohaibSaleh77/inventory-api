package com.ims.domain.category.dto;

import com.ims.domain.category.Category;

import java.time.Instant;
import java.util.List;

/** DTO records for categories, including the tree projection. */
public final class CategoryDtos {

    private CategoryDtos() {
    }

    public record CreateRequest(String parentCode, String code, String name,
                                String description) {
    }

    public record UpdateRequest(String parentCode, String name, String description,
                                Boolean active) {
    }

    public record Response(Long id, Long parentId, String code, String name,
                           String description, boolean active, int version,
                           Instant createdAt, Instant updatedAt) {

        public static Response from(Category c) {
            return new Response(c.getId(), c.getParentId(), c.getCode(), c.getName(),
                    c.getDescription(), c.isActive(), c.getVersion(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record TreeNode(Long id, String code, String name, boolean active,
                           List<TreeNode> children) {

        public static TreeNode from(Category c, List<TreeNode> children) {
            return new TreeNode(c.getId(), c.getCode(), c.getName(), c.isActive(), children);
        }
    }
}
