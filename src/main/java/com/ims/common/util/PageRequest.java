package com.ims.common.util;

import java.util.List;

/**
 * Pagination contract. Pages are 1-based; page size is capped to protect the
 * database from unbounded scans.
 */
public record PageRequest(int page, int size, String sortBy, String sortDir) {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 25;

    public static PageRequest of(Integer page, Integer size, String sortBy, String sortDir) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String dir = "desc".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
        return new PageRequest(p, s, sortBy, sortDir == null ? "asc" : dir);
    }

    public int offset() {
        return (page - 1) * size;
    }

    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

        public static <T> Page<T> of(List<T> content, PageRequest request, long total) {
            int totalPages = request.size() == 0 ? 0
                    : (int) Math.ceil((double) total / request.size());
            return new Page<>(content, request.page(), request.size(), total, totalPages);
        }

        public boolean hasNext() {
            return page < totalPages;
        }

        public boolean hasPrevious() {
            return page > 1;
        }
    }
}
