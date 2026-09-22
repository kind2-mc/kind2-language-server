const net = require('node:net');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { WebSocketServer, WebSocket } = require('ws');

const WEBSOCKET_PORT = 3001;
const WEBSOCKET_HOST = '127.0.0.1';
const WEBSOCKET_PATH = '/lsp';
const KIND2_PATH = pickEnvValue(
  process.env.KIND2_PATH,
  'kind2'
);
const KIND2_Z3_BIN = pickEnvValue(
  process.env.KIND2_Z3_BIN,
  'z3'
);
const DEFAULT_ALLOWED_ORIGINS = [
  'https://vscode.dev',
  'https://insiders.vscode.dev',
  'https://github.dev',
  'http://127.0.0.1:3000',
  'http://localhost:3000',
  /^http:\/\/(?:[^./]+\.)*localhost:3000$/i,
  'http://localhost'
];

const ALLOWED_ORIGINS =
  parseAllowedOrigins(
    process.env.KIND2_ALLOWED_ORIGINS
  ) ?? DEFAULT_ALLOWED_ORIGINS;

const GATEWAY_DIR = __dirname;
const JAVA_COMMAND = path.resolve(
  GATEWAY_DIR,
  '../../build/install/kind2-language-server/bin/' +
    (process.platform === 'win32'
      ? 'kind2-language-server.bat'
      : 'kind2-language-server')
);
const JAVA_ENV = {
  KIND2_SAFE_MODE: 'true',
  KIND2_PATH: KIND2_PATH,
  KIND2_Z3_BIN: KIND2_Z3_BIN
};

function pickEnvValue(...values) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim().length > 0) {
      return value;
    }
  }

  return undefined;
}

const webSocketServer = new WebSocketServer({
  host: WEBSOCKET_HOST,
  port: WEBSOCKET_PORT,
  path: WEBSOCKET_PATH,
  verifyClient: info => {
    if (isAllowedOrigin(info.origin)) {
      return true;
    }

    console.warn(
      'Rejected WebSocket handshake from origin:',
      info.origin ?? '<missing>'
    );

    return false;
  }
});

console.log(
  `Kind2 gateway listening at ` +
  `ws://localhost:${WEBSOCKET_PORT}${WEBSOCKET_PATH}`
);

console.log(
  'Allowed WebSocket origins:',
  ALLOWED_ORIGINS.length === 0
    ? '<all origins>'
    : ALLOWED_ORIGINS
        .map(formatOriginRule)
        .join(', ')
  );

webSocketServer.on('connection', webSocket => {
  console.log('Browser connected');

  let javaProcess;
  let javaSocket;
  let browserClosed = false;

  const pendingBrowserMessages = [];

  webSocket.on('message', data => {
    const json = data.toString('utf8');

    console.log(
      'Browser -> gateway:',
      summarizeMessage(json)
    );

    if (javaSocket === undefined) {
      console.log('Queueing message until Java connects');
      pendingBrowserMessages.push(json);
      return;
    }

    writeLspMessage(javaSocket, json);
  });

  const tcpServer = net.createServer(socket => {
    if (javaSocket !== undefined) {
      socket.destroy();
      return;
    }

    console.log('Java language server connected');

    javaSocket = socket;

    connectJavaToBrowser(webSocket, javaSocket);

    for (const json of pendingBrowserMessages) {
      console.log(
        'Forwarding queued message:',
        summarizeMessage(json)
      );

      writeLspMessage(javaSocket, json);
    }

    pendingBrowserMessages.length = 0;
  });

  tcpServer.listen(0, '127.0.0.1', () => {
    const address = tcpServer.address();

    if (
      address === null ||
      typeof address === 'string'
    ) {
      closeWebSocket(
        webSocket,
        'Could not allocate Java connection port'
      );
      return;
    }

    const javaPort = address.port;

    console.log(
      `Waiting for Java on TCP port ${javaPort}`
    );

    javaProcess = spawn(
      JAVA_COMMAND,
      [String(javaPort)],
      {
        cwd: GATEWAY_DIR,
        stdio: ['ignore', 'ignore', 'pipe'],
        shell: process.platform === 'win32',
        env: {
          ...process.env,
          ...JAVA_ENV
        }
      }
    );

    javaProcess.stderr.on('data', data => {
      process.stderr.write(
        `[kind2-language-server] ${data}`
      );
    });

    javaProcess.on('error', error => {
      console.error(
        'Failed to launch Java language server:',
        error
      );

      closeWebSocket(
        webSocket,
        'Could not launch Java language server'
      );
    });

    javaProcess.on('exit', (code, signal) => {
      console.log(
        `Java language server exited: ` +
        `code=${code}, signal=${signal}`
      );

      tcpServer.close();

      if (!browserClosed) {
        closeWebSocket(
          webSocket,
          'Java language server exited'
        );
      }
    });
  });

  webSocket.on('close', () => {
    browserClosed = true;

    javaSocket?.destroy();
    tcpServer.close();

    if (
      javaProcess !== undefined &&
      !javaProcess.killed
    ) {
      javaProcess.kill();
    }
  });

  webSocket.on('error', error => {
    console.error(
      'Browser WebSocket error:',
      error
    );
  });
});

function connectJavaToBrowser(
  webSocket,
  javaSocket
) {
  let javaOutputBuffer = Buffer.alloc(0);

  javaSocket.on('data', chunk => {
    console.log(
      `Received ${chunk.length} raw bytes from Java`
    );

    javaOutputBuffer = Buffer.concat([
      javaOutputBuffer,
      chunk
    ]);

    try {
      javaOutputBuffer = extractLspMessages(
        javaOutputBuffer,
        json => {
          console.log(
            'Java -> Browser:',
            summarizeMessage(json)
          );

          if (
            webSocket.readyState === WebSocket.OPEN
          ) {
            webSocket.send(json);
          }
        }
      );
    } catch (error) {
      console.error(
        'Failed parsing Java LSP output:',
        error
      );

      javaSocket.destroy();

      closeWebSocket(
        webSocket,
        'Invalid response from Java language server'
      );
    }
  });

  javaSocket.on('close', () => {
    closeWebSocket(
      webSocket,
      'Java language server disconnected'
    );
  });

  javaSocket.on('error', error => {
    console.error(
      'Java TCP socket error:',
      error
    );

    closeWebSocket(
      webSocket,
      'Java language server connection failed'
    );
  });
}

function writeLspMessage(stream, json) {
  const body = Buffer.from(json, 'utf8');

  const header = Buffer.from(
    `Content-Length: ${body.length}\r\n\r\n`,
    'ascii'
  );

  stream.write(header);
  stream.write(body);
}

function extractLspMessages(
  buffer,
  onMessage
) {
  while (true) {
    const headerEnd =
      buffer.indexOf('\r\n\r\n');

    if (headerEnd === -1) {
      return buffer;
    }

    const header = buffer
      .subarray(0, headerEnd)
      .toString('ascii');

    const match =
      /(?:^|\r\n)Content-Length:\s*(\d+)/i.exec(
        header
      );

    if (match === null) {
      throw new Error(
        `Missing Content-Length header: ${header}`
      );
    }

    const bodyLength = Number(match[1]);
    const bodyStart = headerEnd + 4;
    const bodyEnd = bodyStart + bodyLength;

    if (buffer.length < bodyEnd) {
      return buffer;
    }

    const json = buffer
      .subarray(bodyStart, bodyEnd)
      .toString('utf8');

    onMessage(json);

    buffer = buffer.subarray(bodyEnd);
  }
}

function closeWebSocket(
  webSocket,
  reason
) {
  if (
    webSocket.readyState === WebSocket.OPEN ||
    webSocket.readyState === WebSocket.CONNECTING
  ) {
    webSocket.close(1011, reason);
  }
}

function parseAllowedOrigins(value) {
  if (typeof value !== 'string') {
    return null;
  }

  const origins = value
    .split(',')
    .map(origin => origin.trim())
    .filter(origin => origin.length > 0);

  if (origins.length === 0) {
    return [];
  }

  return origins.map(parseOriginRule);
}

function parseOriginRule(origin) {
  const regexLiteralMatch =
    /^\/(.+)\/([a-z]*)$/i.exec(origin);

  if (regexLiteralMatch === null) {
    return origin;
  }

  try {
    return new RegExp(
      regexLiteralMatch[1],
      regexLiteralMatch[2]
    );
  } catch (error) {
    const errorMessage =
      error instanceof Error
        ? error.message
        : String(error);

    console.warn(
      `Ignoring invalid origin regex ${origin}: ${errorMessage}`
    );

    return origin;
  }
}

function formatOriginRule(rule) {
  if (rule instanceof RegExp) {
    return `/${rule.source}/${rule.flags}`;
  }

  return rule;
}

function isAllowedOrigin(origin) {
  if (typeof origin !== 'string') {
    return false;
  }

  if (ALLOWED_ORIGINS.length === 0) {
    return true;
  }

  return ALLOWED_ORIGINS.some(rule => {
    if (rule instanceof RegExp) {
      rule.lastIndex = 0;
      return rule.test(origin);
    }

    return rule === origin;
  });
}

function summarizeMessage(json) {
  try {
    const message = JSON.parse(json);

    if (message.method) {
      return message.method;
    }

    if (message.error) {
      return (
        `response ${message.id ?? 'unknown'} ERROR ` +
        `${message.error.code}: ${message.error.message}`
      );
    }

    return `response ${message.id ?? 'unknown'} OK`;
  } catch {
    return 'invalid JSON';
  }
}
