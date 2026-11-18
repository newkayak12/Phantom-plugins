# Phantom Key-Value Console

`dev.phantom.redis` · [한국어](./KOR.md)

A key browser and console for Redis-compatible servers (Redis, Valkey, KeyDB…), in the Redis tool window: keys found
with SCAN and a pattern (never KEYS *), values of every type (strings with JSON indented, hashes, lists, sets, sorted
sets, streams), delete, and a command line with redis-cli style output and history. The plugin speaks the Redis
protocol (RESP) itself, with password, ACL user, database and TLS; the connection opens only while the tool window is
shown. Passwords are kept in the IDE's password safe under the service name "Phantom Redis".

Redis is a trademark of Redis Ltd. This plugin is not affiliated with or endorsed by Redis Ltd., the Valkey project or
the KeyDB project.

**0.1.2**: renamed from "Redis Support" to "Phantom Key-Value Console". The plugin id (`dev.phantom.redis`), the saved
servers and the passwords stay as they were.
