DELETE FROM sessions WHERE expires < now() RETURNING id, user_id;
SELECT NEXTVAL(order_seq);
