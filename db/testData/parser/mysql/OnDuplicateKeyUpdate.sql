INSERT INTO `counters` (name, hits) VALUES ('home', 1)
ON DUPLICATE KEY UPDATE hits = hits + 1;
REPLACE INTO t (a) VALUES (1);
