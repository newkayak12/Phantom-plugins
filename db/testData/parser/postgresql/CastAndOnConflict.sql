SELECT x::text, y::numeric(10, 2) FROM t WHERE name ILIKE 'a%';
INSERT INTO t (a, b) VALUES (1, 2) ON CONFLICT (a) DO UPDATE SET b = excluded.b RETURNING *;
