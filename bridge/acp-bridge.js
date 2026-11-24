#!/usr/bin/env node
// SPDX-License-Identifier: MIT
//
// acp-bridge.js — expose a stdio ACP agent over WebSocket so DroidACP can reach it.
//
// ACP (Agent Client Protocol) agents such as `claude-agent-acp`, `codex-acp` or
// `gemini --acp` talk JSON-RPC 2.0 over stdin/stdout, one JSON message per line.
// An Android app can't spawn those processes on your computer, so this bridge does
// it for you:
//
//     DroidACP  ──WebSocket──▶  acp-bridge.js  ──stdin/stdout──▶  ACP agent
//
// Every WebSocket *text frame* carries exactly one JSON-RPC message (the same
// framing the official ACP SDK uses for its WebSocket transport). Each WebSocket
// connection gets its own freshly spawned agent process, which is stopped when the
// connection closes.
//
// Zero dependencies: only Node's built-in modules are used, so you can copy this
// single file anywhere that has Node 18+ and run it. The WebSocket server is
// implemented by hand below (RFC 6455) — it's a good way to learn how WebSockets
// actually work on the wire.
//
// Usage:
//   node acp-bridge.js [options] -- <agent command> [agent args...]
//
// Examples:
//   node acp-bridge.js -- npx -y @agentclientprotocol/claude-agent-acp
//   node acp-bridge.js --port 9000 --cwd ~/code -- gemini --acp
//   node acp-bridge.js -- node mock-agent.js          (fake agent for testing)
//
// Run with --help for all options.

'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const https = require('node:https');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { StringDecoder } = require('node:string_decoder');
const { EventEmitter } = require('node:events');

// ---------------------------------------------------------------------------
// Command line parsing
// ---------------------------------------------------------------------------

const HELP = `
acp-bridge — serve a stdio ACP agent over WebSocket

Usage: node acp-bridge.js [options] -- <agent command> [args...]

Options:
  --port <n>          Port to listen on (default 8765)
  --host <addr>       Address to bind (default 0.0.0.0 = all interfaces)
  --token <secret>    Token clients must send. Default: $ACP_BRIDGE_TOKEN, or a
                      random token saved in ~/.config/droidacp/bridge-token
  --no-auth           Accept clients without a token (only on a network you trust!)
  --cwd <dir>         Working directory for the agent process (default: current)
  --tls-cert <file>   PEM certificate: serve wss:// instead of ws://
  --tls-key <file>    PEM private key for --tls-cert
  --max-clients <n>   Max simultaneous connections/agents (default 4)
  --verbose, -v       Log every JSON-RPC message (great for learning ACP)
  --help, -h          Show this help
`;

function parseArgs(argv) {
  const opts = {
    port: 8765,
    host: '0.0.0.0',
    token: process.env.ACP_BRIDGE_TOKEN || null,
    noAuth: false,
    cwd: process.cwd(),
    tlsCert: null,
    tlsKey: null,
    maxClients: 4,
    verbose: false,
    command: null,
    args: [],
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    // Everything after `--` belongs to the agent command.
    if (arg === '--') {
      opts.command = argv[i + 1] || null;
      opts.args = argv.slice(i + 2);
      break;
    }
    const next = () => {
      if (i + 1 >= argv.length) fail(`Missing value for ${arg}`);
      return argv[++i];
    };
    switch (arg) {
      case '--port': opts.port = Number(next()); break;
      case '--host': opts.host = next(); break;
      case '--token': opts.token = next(); break;
      case '--no-auth': opts.noAuth = true; break;
      case '--cwd': opts.cwd = expandHome(next()); break;
      case '--tls-cert': opts.tlsCert = next(); break;
      case '--tls-key': opts.tlsKey = next(); break;
      case '--max-clients': opts.maxClients = Number(next()); break;
      case '--verbose': case '-v': opts.verbose = true; break;
      case '--help': case '-h': process.stdout.write(HELP); process.exit(0); break;
      default: fail(`Unknown option ${arg} (did you forget "--" before the agent command?)`);
    }
  }

  if (!opts.command) fail('No agent command given. Example: node acp-bridge.js -- npx -y @agentclientprotocol/claude-agent-acp');
  if (!Number.isInteger(opts.port) || opts.port <= 0 || opts.port > 65535) fail('Invalid --port');
  if (Boolean(opts.tlsCert) !== Boolean(opts.tlsKey)) fail('--tls-cert and --tls-key must be used together');
  if (!fs.existsSync(opts.cwd)) fail(`--cwd directory does not exist: ${opts.cwd}`);
  return opts;
}

function expandHome(p) {
  return p.startsWith('~') ? path.join(os.homedir(), p.slice(1)) : p;
}

function fail(message) {
  process.stderr.write(`acp-bridge: ${message}\n${HELP}`);
  process.exit(2);
}

// Reuse one random token across runs so you only type it into the app once.
function loadOrCreateToken() {
  const dir = path.join(os.homedir(), '.config', 'droidacp');
  const file = path.join(dir, 'bridge-token');
  try {
    const existing = fs.readFileSync(file, 'utf8').trim();
    if (existing) return existing;
  } catch { /* not created yet */ }
  const token = crypto.randomBytes(18).toString('base64url');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(file, token + '\n', { mode: 0o600 });
  return token;
}

// ---------------------------------------------------------------------------
// Small logging helpers
// ---------------------------------------------------------------------------

function log(...parts) {
  const time = new Date().toISOString().slice(11, 19);
  console.error(`[${time}]`, ...parts);
}

function preview(text, max = 300) {
  return text.length > max ? `${text.slice(0, max)}… (${text.length} chars)` : text;
}

// ---------------------------------------------------------------------------
// A minimal RFC 6455 WebSocket connection (server side)
// ---------------------------------------------------------------------------

// Magic string from the RFC; the handshake proves the server understood WebSocket.
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_MESSAGE_BYTES = 64 * 1024 * 1024; // refuse absurdly large messages

const OPCODE = { CONTINUATION: 0x0, TEXT: 0x1, BINARY: 0x2, CLOSE: 0x8, PING: 0x9, PONG: 0xa };

/**
 * Wraps a raw TCP/TLS socket after the HTTP upgrade and turns WebSocket frames
 * into 'message' events (strings). Emits: 'message', 'close'.
 */
class WsConnection extends EventEmitter {
  constructor(socket) {
    super();
    this.socket = socket;
    this.buffer = Buffer.alloc(0); // bytes received but not yet parsed
    this.fragments = [];           // pieces of a fragmented message
    this.fragmentBytes = 0;
    this.closeSent = false;
    this.closed = false;
    this.alive = true;

    socket.setNoDelay(true);
    socket.on('data', (chunk) => this.feed(chunk));
    socket.on('error', (err) => log('socket error:', err.message));
    socket.on('close', () => this.finish());

    // Heartbeat: ping every 30s; if the previous ping got no pong, the peer is gone.
    this.heartbeat = setInterval(() => {
      if (!this.alive) { log('client stopped answering pings, dropping it'); socket.destroy(); return; }
      this.alive = false;
      this.writeFrame(OPCODE.PING, Buffer.alloc(0));
    }, 30_000);
  }

  /** Append bytes from the socket and parse as many complete frames as we can. */
  feed(chunk) {
    this.buffer = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : chunk;
    while (!this.closed) {
      const frame = this.parseFrame();
      if (!frame) break; // need more bytes
      this.handleFrame(frame);
    }
  }

  /**
   * Frame layout (RFC 6455 §5.2):
   *   byte 0: FIN(1) RSV(3) OPCODE(4)
   *   byte 1: MASK(1) LEN(7)   — LEN 126 => next 2 bytes, 127 => next 8 bytes
   *   [4 byte masking key if MASK]  [payload]
   * Returns null if the buffer doesn't hold a whole frame yet.
   */
  parseFrame() {
    const buf = this.buffer;
    if (buf.length < 2) return null;

    const fin = (buf[0] & 0x80) !== 0;
    const rsv = buf[0] & 0x70;
    const opcode = buf[0] & 0x0f;
    const masked = (buf[1] & 0x80) !== 0;
    let length = buf[1] & 0x7f;
    let offset = 2;

    if (length === 126) {
      if (buf.length < 4) return null;
      length = buf.readUInt16BE(2);
      offset = 4;
    } else if (length === 127) {
      if (buf.length < 10) return null;
      const big = buf.readBigUInt64BE(2);
      if (big > BigInt(MAX_MESSAGE_BYTES)) { this.protocolError(1009, 'message too big'); return null; }
      length = Number(big);
      offset = 10;
    }

    if (rsv !== 0) { this.protocolError(1002, 'unexpected RSV bits'); return null; }
    if (!masked) { this.protocolError(1002, 'client frames must be masked'); return null; }

    if (buf.length < offset + 4 + length) return null;
    const mask = buf.subarray(offset, offset + 4);
    offset += 4;

    // Copy the payload out and undo the XOR mask the client applied.
    const payload = Buffer.from(buf.subarray(offset, offset + length));
    for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

    this.buffer = buf.subarray(offset + length);
    return { fin, opcode, payload };
  }

  handleFrame({ fin, opcode, payload }) {
    switch (opcode) {
      case OPCODE.PING:
        this.writeFrame(OPCODE.PONG, payload);
        return;
      case OPCODE.PONG:
        this.alive = true;
        return;
      case OPCODE.CLOSE: {
        const code = payload.length >= 2 ? payload.readUInt16BE(0) : 1000;
        this.close(code === 1005 || code === 1006 ? 1000 : code);
        return;
      }
      case OPCODE.TEXT:
      case OPCODE.BINARY:
        if (this.fragments.length) { this.protocolError(1002, 'new message before previous finished'); return; }
        break;
      case OPCODE.CONTINUATION:
        if (!this.fragments.length) { this.protocolError(1002, 'unexpected continuation frame'); return; }
        break;
      default:
        this.protocolError(1002, `unknown opcode ${opcode}`);
        return;
    }

    // Data frame (possibly one fragment of a longer message).
    this.fragments.push(payload);
    this.fragmentBytes += payload.length;
    if (this.fragmentBytes > MAX_MESSAGE_BYTES) { this.protocolError(1009, 'message too big'); return; }
    if (!fin) return;

    const text = Buffer.concat(this.fragments).toString('utf8');
    this.fragments = [];
    this.fragmentBytes = 0;
    this.emit('message', text);
  }

  /** Server → client frames are never masked. Returns false if the socket is backed up. */
  writeFrame(opcode, payload) {
    if (this.closed || this.socket.destroyed || this.socket.writableEnded) return false;
    let header;
    if (payload.length < 126) {
      header = Buffer.from([0x80 | opcode, payload.length]);
    } else if (payload.length < 65536) {
      header = Buffer.alloc(4);
      header[0] = 0x80 | opcode;
      header[1] = 126;
      header.writeUInt16BE(payload.length, 2);
    } else {
      header = Buffer.alloc(10);
      header[0] = 0x80 | opcode;
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(payload.length), 2);
    }
    return this.socket.write(Buffer.concat([header, payload]));
  }

  sendText(text) {
    return this.writeFrame(OPCODE.TEXT, Buffer.from(text, 'utf8'));
  }

  /** Start the closing handshake. Reason must stay under 123 bytes. */
  close(code = 1000, reason = '') {
    if (this.closeSent) return;
    this.closeSent = true;
    const reasonBytes = Buffer.from(reason, 'utf8').subarray(0, 123);
    const payload = Buffer.alloc(2 + reasonBytes.length);
    payload.writeUInt16BE(code, 0);
    reasonBytes.copy(payload, 2);
    this.writeFrame(OPCODE.CLOSE, payload);
    this.socket.end();
    // If the client never finishes the handshake, force the socket shut.
    setTimeout(() => this.socket.destroy(), 3000).unref();
  }

  protocolError(code, reason) {
    log(`protocol error: ${reason}`);
    this.close(code, reason);
    this.finish();
  }

  finish() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.heartbeat);
    this.emit('close');
  }
}

// ---------------------------------------------------------------------------
// Wiring one WebSocket connection to one agent process
// ---------------------------------------------------------------------------

let nextClientId = 1;
let activeClients = 0;

function startAgentSession(ws, opts, remote) {
  const id = nextClientId++;
  const tag = `#${id}`;
  activeClients++;
  log(`${tag} client connected from ${remote}; starting agent: ${[opts.command, ...opts.args].join(' ')}`);

  const isWindows = process.platform === 'win32';
  const child = spawn(opts.command, opts.args, {
    cwd: opts.cwd,
    env: process.env,
    stdio: ['pipe', 'pipe', 'pipe'],
    shell: isWindows, // lets `npx`, `.cmd` shims etc. resolve on Windows
    windowsHide: true,
  });

  let agentAlive = true;
  let stdoutWaiting = false; // true while we wait for the WebSocket to drain
  let stdinWaiting = false;  // true while we wait for the agent's stdin to drain

  // --- agent stdout → WebSocket: one line = one JSON-RPC message = one frame ---
  const decoder = new StringDecoder('utf8');
  let pending = '';
  child.stdout.on('data', (chunk) => {
    pending += decoder.write(chunk);
    let newline;
    while ((newline = pending.indexOf('\n')) !== -1) {
      const line = pending.slice(0, newline).replace(/\r$/, '');
      pending = pending.slice(newline + 1);
      if (!line.trim()) continue;
      if (opts.verbose) log(`${tag} agent → app ${preview(line)}`);
      const ok = ws.sendText(line);
      if (!ok && !stdoutWaiting && !ws.closed) {
        // Socket buffer is full (slow network). Stop reading from the agent until it drains.
        stdoutWaiting = true;
        child.stdout.pause();
        ws.socket.once('drain', () => { stdoutWaiting = false; child.stdout.resume(); });
      }
    }
  });

  // --- agent stderr → our console, prefixed so you can tell agents apart ---
  const errDecoder = new StringDecoder('utf8');
  let errPending = '';
  child.stderr.on('data', (chunk) => {
    errPending += errDecoder.write(chunk);
    const lines = errPending.split('\n');
    errPending = lines.pop();
    for (const line of lines) if (line.trim()) log(`${tag} agent stderr: ${line}`);
  });

  // --- WebSocket → agent stdin ---
  ws.on('message', (text) => {
    if (!agentAlive) return;
    for (const message of toJsonLines(text)) {
      if (opts.verbose) log(`${tag} app → agent ${preview(message)}`);
      const ok = child.stdin.write(message + '\n');
      if (!ok && !stdinWaiting) {
        stdinWaiting = true;
        ws.socket.pause();
        child.stdin.once('drain', () => { stdinWaiting = false; ws.socket.resume(); });
      }
    }
  });
  child.stdin.on('error', (err) => log(`${tag} agent stdin error: ${err.message}`));

  child.on('error', (err) => {
    agentAlive = false;
    log(`${tag} could not start agent: ${err.message}`);
    ws.close(1011, `could not start agent: ${err.message}`);
  });

  // 'close' (not 'exit') fires after the agent's stdout has been fully read.
  child.on('close', (code, signal) => {
    agentAlive = false;
    const how = signal ? `signal ${signal}` : `code ${code}`;
    log(`${tag} agent exited (${how})`);
    ws.close(code === 0 ? 1000 : 1011, `agent exited (${how})`);
  });

  ws.on('close', () => {
    activeClients--;
    log(`${tag} client disconnected`);
    if (agentAlive) stopProcess(child);
  });
}

/**
 * ACP over stdio requires each message on a single line. A well-behaved client
 * sends compact JSON, but if a frame is pretty-printed (contains newlines) we
 * re-serialise it. If a frame holds several newline-separated messages we split it.
 */
function toJsonLines(text) {
  if (!text.includes('\n')) return [text];
  try {
    return [JSON.stringify(JSON.parse(text))];
  } catch {
    return text.split('\n').map((l) => l.trim()).filter(Boolean);
  }
}

function stopProcess(child) {
  try { child.stdin.end(); } catch { /* already closed */ }
  if (process.platform === 'win32') {
    // `shell: true` means child is cmd.exe; kill the whole tree.
    spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
    return;
  }
  child.kill('SIGTERM');
  const timer = setTimeout(() => { if (child.exitCode === null) child.kill('SIGKILL'); }, 3000);
  timer.unref();
}

// ---------------------------------------------------------------------------
// HTTP server + WebSocket upgrade handshake
// ---------------------------------------------------------------------------

function tokenFromRequest(req) {
  const auth = req.headers.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(auth);
  if (match) return match[1].trim();
  try {
    return new URL(req.url, 'http://bridge').searchParams.get('token');
  } catch {
    return null;
  }
}

/** Compare secrets in constant time so response timing doesn't leak the token. */
function sameSecret(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const ha = crypto.createHash('sha256').update(a).digest();
  const hb = crypto.createHash('sha256').update(b).digest();
  return crypto.timingSafeEqual(ha, hb);
}

function rejectUpgrade(socket, status, message) {
  socket.end(`HTTP/1.1 ${status} ${http.STATUS_CODES[status]}\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n${message}\n`);
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts.noAuth && !opts.token) opts.token = loadOrCreateToken();

  const onRequest = (req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('DroidACP bridge is running. Connect to this address with a WebSocket ACP client.\n');
  };

  const server = opts.tlsCert
    ? https.createServer({ cert: fs.readFileSync(opts.tlsCert), key: fs.readFileSync(opts.tlsKey) }, onRequest)
    : http.createServer(onRequest);

  server.on('upgrade', (req, socket, head) => {
    const remote = `${socket.remoteAddress}:${socket.remotePort}`;
    if ((req.headers.upgrade || '').toLowerCase() !== 'websocket') return rejectUpgrade(socket, 400, 'Expected a WebSocket upgrade');
    const key = req.headers['sec-websocket-key'];
    if (!key || req.headers['sec-websocket-version'] !== '13') return rejectUpgrade(socket, 400, 'Unsupported WebSocket version');
    if (!opts.noAuth && !sameSecret(tokenFromRequest(req), opts.token)) {
      log(`rejected ${remote}: wrong or missing token`);
      return rejectUpgrade(socket, 401, 'Wrong or missing token');
    }
    if (activeClients >= opts.maxClients) return rejectUpgrade(socket, 503, 'Too many clients');

    // The handshake answer: base64(SHA-1(key + GUID)).
    const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
    const headers = [
      'HTTP/1.1 101 Switching Protocols',
      'Upgrade: websocket',
      'Connection: Upgrade',
      `Sec-WebSocket-Accept: ${accept}`,
    ];
    // Browsers fail the handshake if they asked for a subprotocol and we don't pick one.
    const protocols = (req.headers['sec-websocket-protocol'] || '').split(',').map((p) => p.trim()).filter(Boolean);
    if (protocols.length) headers.push(`Sec-WebSocket-Protocol: ${protocols[0]}`);
    socket.write(headers.join('\r\n') + '\r\n\r\n');

    const ws = new WsConnection(socket);
    startAgentSession(ws, opts, remote);
    if (head && head.length) ws.feed(head); // bytes that arrived together with the handshake
  });

  server.on('error', (err) => {
    log(`server error: ${err.message}`);
    process.exit(1);
  });

  server.listen(opts.port, opts.host, () => {
    const scheme = opts.tlsCert ? 'wss' : 'ws';
    console.error('');
    console.error('  DroidACP bridge ready');
    console.error(`  agent:   ${[opts.command, ...opts.args].join(' ')}`);
    console.error(`  cwd:     ${opts.cwd}`);
    console.error('  connect the app to one of:');
    for (const addr of localAddresses(opts.host)) console.error(`           ${scheme}://${addr}:${opts.port}`);
    if (opts.noAuth) console.error('  token:   (none — --no-auth; anyone on your network can use this agent!)');
    else console.error(`  token:   ${opts.token}`);
    console.error('');
  });

  const shutdown = () => { log('shutting down'); server.close(); process.exit(0); };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

function localAddresses(host) {
  if (host !== '0.0.0.0' && host !== '::') return [host];
  const found = ['127.0.0.1'];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const info of list || []) {
      if (info.family === 'IPv4' && !info.internal) found.push(info.address);
    }
  }
  return found;
}

main();
