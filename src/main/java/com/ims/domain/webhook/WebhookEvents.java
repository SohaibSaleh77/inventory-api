package com.ims.domain.webhook;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical webhook event types and helpers to move between the wire format
 * (comma-separated codes stored per webhook) and typed sets.
 */
public final class WebhookEvents {

    /** A product's available stock dropped to or below its reorder point. */
    public static final String STOCK_LOW = "STOCK_LOW";

    /** A purchase order moved to APPROVED. */
    public static final String PURCHASE_ORDER_APPROVED = "PURCHASE_ORDER_APPROVED";

    /** A sales order was confirmed and its stock reserved. */
    public static final String SALES_ORDER_CONFIRMED = "SALES_ORDER_CONFIRMED";

    /** A shipment left the warehouse (full or partial fulfilment). */
    public static final String SALES_ORDER_SHIPPED = "SALES_ORDER_SHIPPED";

    /** A stocktake was posted and its variances applied to stock. */
    public static final String STOCKTAKE_POSTED = "STOCKTAKE_POSTED";

    /** Operator-triggered connectivity check (contains no business data). */
    public static final String TEST = "TEST";

    private static final List<String> ALL = List.of(
            STOCK_LOW,
            PURCHASE_ORDER_APPROVED,
            SALES_ORDER_CONFIRMED,
            SALES_ORDER_SHIPPED,
            STOCKTAKE_POSTED,
            TEST);

    private WebhookEvents() {
    }

    public static List<String> all() {
        return ALL;
    }

    /** Parse a comma-separated subscription list; blanks are ignored. */
    public static Set<String> parse(String csv) {
        Set<String> result = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(result::add);
        return result;
    }

    /** Render a subscription set back to its stored CSV form. */
    public static String render(Set<String> events) {
        return String.join(",", events);
    }

    /** True when every entry is a known event code. */
    public static boolean allKnown(Set<String> events) {
        return ALL.containsAll(events);
    }
}
