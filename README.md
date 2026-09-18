# Kind2 Language Server

## Gateway

The gateway is a small Node.js WebSocket bridge used to connect a browser-based client to the Java language server. It listens on a local WebSocket endpoint, starts the Java server on an ephemeral TCP port, and forwards LSP messages back and forth so the browser can talk to the server without needing a direct socket connection.

When running the LSP via the gateway, the Kind 2 binary must be present at src/web/kind2 since the web extension cannot carry the Kind 2 executable.
If you need to point the gateway at a different Kind 2 binary, set `KIND2_PATH` before starting `src/web/kind2-gateway.cjs`.

PowerShell:

```powershell
$env:KIND2_PATH = 'C:\path\to\kind2.exe'
npm run start
```

Command Prompt:

```bat
set KIND2_PATH=C:\path\to\kind2.exe
npm run start
```

For safety, the gateway only binds to 127.0.0.1 and rejects WebSocket connections whose Origin header is not allowlisted.

Default allowed origins:
- http://127.0.0.1:3000
- http://localhost:3000

To override the allowed origins, set KIND2_ALLOWED_ORIGINS to a comma-separated list. Example:

KIND2_ALLOWED_ORIGINS=http://127.0.0.1:4173,http://localhost:4173 node src/web/kind2-gateway.cjs

## Safe mode

When the language server is exposed to untrusted clients, enable safe mode so the server does not trust executable paths from the editor extension.

Set `KIND2_SAFE_MODE=TRUE` and point the server to the Kind 2 binary with `KIND2_PATH`.

In safe mode, the `kind2.kind2_path` extension setting is ignored.

In safe mode, the server ignores client-provided Kind 2 and solver binary paths. If you need custom solver locations, configure them on the server with these optional environment variables:

- `KIND2_Z3_BIN`
- `KIND2_BITWUZLA_BIN`
- `KIND2_CVC5_BIN`
- `KIND2_MATHSAT_BIN`
- `KIND2_OPENSMT_BIN`
- `KIND2_SMTINTERPOL_JAR`
- `KIND2_YICES_BIN`
- `KIND2_YICES2_BIN`
