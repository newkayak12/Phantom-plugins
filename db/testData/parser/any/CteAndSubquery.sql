WITH recent AS (SELECT id FROM orders WHERE created > ?)
SELECT r.id, (SELECT max(amount) FROM payments p WHERE p.order_id = r.id) last_payment
FROM recent r;
UPDATE orders SET status = 'done' WHERE id IN (SELECT id FROM recent_orders);
