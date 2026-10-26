-- ============================================================================
-- V007: Reporting views
-- ============================================================================

CREATE OR REPLACE VIEW v_stock_levels AS
SELECT
    si.id                    AS stock_item_id,
    si.product_id,
    p.sku                    AS product_sku,
    p.name                   AS product_name,
    si.warehouse_id,
    w.code                   AS warehouse_code,
    w.name                   AS warehouse_name,
    si.qty_on_hand,
    si.qty_reserved,
    si.qty_on_hand - si.qty_reserved AS qty_available,
    si.avg_unit_cost,
    si.qty_on_hand * si.avg_unit_cost AS stock_value,
    p.reorder_point
FROM stock_items si
JOIN products   p ON p.id = si.product_id
JOIN warehouses w ON w.id = si.warehouse_id;

CREATE OR REPLACE VIEW v_product_stock_summary AS
SELECT
    p.id            AS product_id,
    p.sku,
    p.name,
    p.unit_of_measure,
    p.reorder_point,
    p.reorder_qty,
    COALESCE(SUM(si.qty_on_hand), 0)  AS total_on_hand,
    COALESCE(SUM(si.qty_reserved), 0) AS total_reserved,
    COALESCE(SUM(si.qty_on_hand), 0) - COALESCE(SUM(si.qty_reserved), 0) AS total_available,
    COUNT(si.id)                       AS warehouses_with_stock
FROM products p
LEFT JOIN stock_items si
       ON si.product_id = p.id
      AND si.qty_on_hand <> 0
GROUP BY p.id, p.sku, p.name, p.unit_of_measure, p.reorder_point, p.reorder_qty;

CREATE OR REPLACE VIEW v_low_stock AS
SELECT
    p.id        AS product_id,
    p.sku,
    p.name,
    p.reorder_point,
    p.reorder_qty,
    COALESCE(SUM(si.qty_on_hand), 0) AS total_on_hand
FROM products p
LEFT JOIN stock_items si ON si.product_id = p.id
WHERE p.is_active = 1
  AND p.reorder_point > 0
GROUP BY p.id, p.sku, p.name, p.reorder_point, p.reorder_qty
HAVING COALESCE(SUM(si.qty_on_hand), 0) <= p.reorder_point;
