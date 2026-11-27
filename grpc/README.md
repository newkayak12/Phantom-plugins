# Support for gRPC

`dev.phantom.grpc` · [한국어](./KOR.md)

Editing support for Protocol Buffers (`.proto`) files, the interface definition language of gRPC:

- Highlighting of keywords, scalar types, strings, numbers and comments; syntax errors for broken statements, with the
  rest of the file still parsed.
- A structure view of messages, enums, services and rpcs with their fields, oneofs, enum values and nested types.
- Go to Declaration, Find Usages and rename for message and enum types. Type names resolve the way `protoc` does: the
  innermost enclosing message first, then each outer scope, then the package root; `.pkg.Type` starts at the root.
  Only the file itself and the files it imports are searched.
- Go to Declaration on an `import` path: the file next to the importing one, otherwise a project or library file whose
  path ends with the import path (the closest one when several match).
- Line and block comments, brace matching.

The plugin reads only `.proto` files and does nothing until one is opened: no startup work and no network access.

Not yet: navigation between an rpc, the generated grpc-java / grpc-kotlin stubs and their implementations, calling an
rpc, and a warning for generated code older than its `.proto`. The plugin cannot be installed next to the JetBrains
Protocol Buffers plugin, since both register the `.proto` file type.

gRPC and Protocol Buffers are names of their respective projects; this plugin is not affiliated with or endorsed by
them.
