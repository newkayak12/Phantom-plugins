# Phantom Local Index

`dev.phantom.local-index` · [한국어](./KOR.md)

Reuses index data between checkouts of the same project on this machine, so a new worktree or clone does not index files that another checkout already indexed.

- **Local only:** data is read and written on this machine; nothing is sent anywhere.
- **Advanced Settings:** a switch in Settings | Advanced Settings | Phantom Local Index will come with the first version that reuses data (not in 0.1.0).
- **Turn off:** disable the plugin in Settings | Plugins.

0.1.0 is the skeleton: the plugin registers with the platform's indexing infrastructure and passes every index through unchanged.
