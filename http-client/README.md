# Phantom HTTP Client

`dev.phantom.http` · [한국어](./KOR.md)

HTTP requests in `.http` and `.rest` files, run from the editor.

- **Requests:** method, URL, headers and body separated by `###`; a ▶ gutter on each request, Run All, and Ctrl+Enter. The response view shows status, headers and a formatted body; bodies over 1 MB are shown as plain text. A request without `# @timeout` gives up after 60 seconds without a response.
- **Variables:** `{{name}}` from `@name = value` lines, from `http-client.env.json` / `http-client.private.env.json` environments, and from response handler scripts.
- **Reuse:** `import other.http` makes that file's requests and variables available; `run #name`, `run other.http` and `run #name (@var=value)` run them.
- **Checks:** broken JSON bodies, headers the Java client refuses to send (Content-Length, Host, ...), unknown methods, whitespace inside URLs, a body running into the next request without `###`, broken `{{ }}`, malformed or unknown headers, plain `http://` to remote hosts, duplicate imports, unknown `run` names and missing files.

Until 2026-10-18 this was part of the Phantom core; it is a plugin now so it updates without a new Phantom build.

Not yet: auth configurations, gRPC and WebSocket requests.
