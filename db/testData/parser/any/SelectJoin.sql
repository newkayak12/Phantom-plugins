SELECT a.id, b.name total, count(*) AS n
FROM orders a
  LEFT JOIN customers b ON a.customer_id = b.id
WHERE a.status = 'open' -- comment
GROUP BY a.id, b.name
ORDER BY n DESC;
