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

Terminal: 

```terminal
export KIND2_PATH="/path/to/kind2"
npm run start
```

For safety, the gateway only binds to 127.0.0.1 and rejects WebSocket connections whose Origin header is not allowlisted.

The set of default allowed origins is defined in `kind2-gateway.cjs`

To override the allowed origins, set KIND2_ALLOWED_ORIGINS to a comma-separated list. Example:

```terminal
export KIND2_ALLOWED_ORIGINS=http://127.0.0.1:4173,http://localhost:4173
npm run start
```

Setting the variable to `*` will allow all origins: `export KIND2_ALLOWED_ORIGINS="*"`

## Safe mode

When the language server is exposed to untrusted clients, enable safe mode so the server does not trust executable paths from the editor extension.

Set `KIND2_SAFE_MODE=TRUE` and point the server to the Kind 2 binary with `KIND2_PATH`.

In safe mode, the `kind2.kind2_path` extension setting is ignored.

In safe mode, the server ignores client-provided Kind 2 and solver binary paths. For the language server to function, you must configure at least one solver on the server by setting the solver paths with these optional environment variables:

- `KIND2_Z3_BIN`
- `KIND2_BITWUZLA_BIN`
- `KIND2_CVC5_BIN`
- `KIND2_MATHSAT_BIN`
- `KIND2_OPENSMT_BIN`
- `KIND2_SMTINTERPOL_JAR`
- `KIND2_YICES_BIN`
- `KIND2_YICES2_BIN`

The gateway will look for only `./src/web/z3` and `./src/web/kind2` automatically. So an alternative to setting the `KIND2_PATH` and `KIND2_*_BIN` is to include `kind2` and `z3` at `./src/web/`. If `KIND2_PATH` or `KIND2_Z3_BIN` are set, then those will be the paths used instead.

### Safe mode resource limits

When safe mode is enabled, each execution of Kind 2 is run inside a Docker container with CPU and memory limits.

The gateway supports the following environment variables for configuring these limits:

- `KIND2_SAFE_MODE_CPU`
- `KIND2_SAFE_MODE_MEMORY`
- `KIND2_SAFE_MODE_SWAP`

If these variables are not set, the gateway uses the following defaults:

```text
KIND2_SAFE_MODE_CPU=2.0
KIND2_SAFE_MODE_MEMORY=2g
KIND2_SAFE_MODE_SWAP=2g
```

`KIND2_SAFE_MODE_CPU` specifies the maximum CPU capacity available to each Kind 2 execution, measured in CPU cores. Fractional values are allowed. For example, `0.5` limits an execution to approximately half of one CPU core, while `1.5` allows up to the equivalent of one and a half CPU cores.

`KIND2_SAFE_MODE_MEMORY` specifies the maximum amount of physical memory available to each Kind 2 execution.

`KIND2_SAFE_MODE_SWAP` specifies the maximum combined amount of physical memory and swap available to each Kind 2 execution. For example, setting both `KIND2_SAFE_MODE_MEMORY=2g` and `KIND2_SAFE_MODE_SWAP=2g` prevents the container from using additional swap beyond the 2 GB physical memory limit.

Memory values must be positive integers followed by a unit. Supported units are:

- `g` for gigabytes
- `m` for megabytes
- `k` for kilobytes
- `b` for bytes

For example:

```terminal
export KIND2_SAFE_MODE_CPU=0.5
export KIND2_SAFE_MODE_MEMORY=1g
export KIND2_SAFE_MODE_SWAP=1g
npm run start
```

PowerShell:

```powershell
$env:KIND2_SAFE_MODE_CPU = '0.5'
$env:KIND2_SAFE_MODE_MEMORY = '1g'
$env:KIND2_SAFE_MODE_SWAP = '1g'
npm run start
```

Command Prompt:

```bat
set KIND2_SAFE_MODE_CPU=0.5
set KIND2_SAFE_MODE_MEMORY=1g
set KIND2_SAFE_MODE_SWAP=1g
npm run start
```

These settings are passed by the gateway to the language server when it starts. The gateway currently defaults to `2.0` CPUs, `2g` of physical memory, and `2g` of combined memory and swap when the corresponding environment variables are not provided.
