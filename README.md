# Kind2 Language Server

## Gateway

The gateway is a small Node.js WebSocket bridge used to connect a browser-based client to the Java language server. It listens on a local WebSocket endpoint, starts the Java server on an ephemeral TCP port, and forwards LSP messages back and forth so the browser can talk to the server without needing a direct socket connection.

When running the LSP via the gateway, the Kind 2 binary must be present at src/web/kind2 since the web extension cannot carry the Kind 2 executable.

For safety, the gateway only binds to 127.0.0.1 and rejects WebSocket connections whose Origin header is not allowlisted.

Default allowed origins:
- http://127.0.0.1:3000
- http://localhost:3000

To override the allowed origins, set KIND2_ALLOWED_ORIGINS to a comma-separated list. Example:

KIND2_ALLOWED_ORIGINS=http://127.0.0.1:4173,http://localhost:4173 node src/web/kind2-gateway.cjs
