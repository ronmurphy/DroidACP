#!/usr/bin/env node
// SPDX-License-Identifier: MIT
//
// mock-agent.js — a fake ACP agent for testing DroidACP without an LLM.
//
// It speaks the real Agent Client Protocol (JSON-RPC 2.0, one message per line on
// stdin/stdout) and exercises most of what the app can display: streamed markdown,
// "thinking", tool calls with diffs, a plan, permission prompts, modes, config
// options, slash commands, session list/load and cancellation.
//
//   node acp-bridge.js -- node mock-agent.js
//   node acp-bridge.js -- node mock-agent.js --require-auth   (test the login flow)
//
// Reading this file top to bottom is a decent tour of the protocol from the
// agent's side.

'use strict';

const fs = require('node:fs');
const readline = require('node:readline');

const REQUIRE_AUTH = process.argv.includes('--require-auth');
const LOG_FILE = process.env.MOCK_AGENT_LOG || null; // optional: record every message we receive

let authenticated = !REQUIRE_AUTH;
let nextRequestId = 1;
const waitingForClient = new Map(); // our outgoing request id -> resolve function
const sessions = new Map();         // sessionId -> { cwd, mode, model, history: [], turn }

// ---------------------------------------------------------------------------
// JSON-RPC plumbing
// ---------------------------------------------------------------------------

function send(message) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...message }) + '\n');
}

function respond(id, result) { send({ id, result }); }
function respondError(id, code, message, data) { send({ id, error: { code, message, ...(data === undefined ? {} : { data }) } }); }
function notify(method, params) { send({ method, params }); }

/** Send a request to the client and wait for its answer. */
function request(method, params) {
  const id = `agent-${nextRequestId++}`;
  return new Promise((resolve, reject) => {
    waitingForClient.set(id, { resolve, reject });
    send({ id, method, params });
  });
}

function update(sessionId, update) {
  notify('session/update', { sessionId, update });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Static data the agent advertises
// ---------------------------------------------------------------------------

const MODES = {
  currentModeId: 'ask',
  availableModes: [
    { id: 'ask', name: 'Ask', description: 'Ask before changing files' },
    { id: 'auto', name: 'Auto', description: 'Edit files without asking' },
    { id: 'plan', name: 'Plan', description: 'Only plan, never edit' },
  ],
};

function configOptions(session) {
  return [
    {
      id: 'model',
      name: 'Model',
      category: 'model',
      type: 'select',
      currentValue: session.model,
      options: [
        { value: 'mock-fast', name: 'Mock Fast', description: 'Quick replies' },
        { value: 'mock-smart', name: 'Mock Smart', description: 'Longer replies' },
      ],
    },
    {
      id: 'verbose',
      name: 'Show thinking',
      type: 'boolean',
      currentValue: session.verbose,
    },
  ];
}

const COMMANDS = [
  { name: 'demo', description: 'Run the full demo (tools, plan, permission)', input: null },
  { name: 'echo', description: 'Repeat your text back', input: { hint: 'text to echo' } },
  { name: 'long', description: 'Stream a long answer (test cancel)', input: null },
];

// ---------------------------------------------------------------------------
// Request handlers (client → agent)
// ---------------------------------------------------------------------------

const handlers = {
  initialize(params) {
    return {
      protocolVersion: 1,
      agentCapabilities: {
        loadSession: true,
        promptCapabilities: { image: true, audio: false, embeddedContext: true },
        sessionCapabilities: { list: {} },
      },
      authMethods: REQUIRE_AUTH ? [{ id: 'mock-login', name: 'Mock login', description: 'Pretend to sign in (always succeeds)' }] : [],
      agentInfo: { name: 'mock-agent', title: 'Mock Agent', version: '1.0.0' },
    };
  },

  authenticate(params) {
    if (params.methodId !== 'mock-login') throw rpcError(-32602, `Unknown auth method ${params.methodId}`);
    authenticated = true;
    return {};
  },

  'session/new'(params) {
    requireAuth();
    const sessionId = `mock-${Date.now().toString(36)}`;
    const session = { cwd: params.cwd, mode: MODES.currentModeId, model: 'mock-fast', verbose: true, history: [], turn: null, title: null };
    sessions.set(sessionId, session);
    // Commands are announced with a notification right after the session exists.
    setTimeout(() => update(sessionId, { sessionUpdate: 'available_commands_update', availableCommands: COMMANDS }), 10);
    return { sessionId, modes: { ...MODES, currentModeId: session.mode }, configOptions: configOptions(session) };
  },

  'session/list'(params) {
    const list = [...sessions.entries()]
      .filter(([, s]) => !params.cwd || s.cwd === params.cwd)
      .map(([sessionId, s]) => ({ sessionId, cwd: s.cwd, title: s.title || 'Untitled', updatedAt: new Date().toISOString() }));
    return { sessions: list };
  },

  async 'session/load'(params) {
    requireAuth();
    const session = sessions.get(params.sessionId);
    if (!session) throw rpcError(-32002, 'Session not found');
    // Replay the conversation so the client can rebuild its transcript.
    for (const entry of session.history) {
      update(params.sessionId, {
        sessionUpdate: entry.role === 'user' ? 'user_message_chunk' : 'agent_message_chunk',
        content: { type: 'text', text: entry.text },
      });
    }
    setTimeout(() => update(params.sessionId, { sessionUpdate: 'available_commands_update', availableCommands: COMMANDS }), 10);
    return { modes: { ...MODES, currentModeId: session.mode }, configOptions: configOptions(session) };
  },

  'session/set_mode'(params) {
    const session = getSession(params.sessionId);
    session.mode = params.modeId;
    update(params.sessionId, { sessionUpdate: 'current_mode_update', currentModeId: params.modeId });
    return {};
  },

  'session/set_config_option'(params) {
    const session = getSession(params.sessionId);
    if (params.configId === 'model') session.model = params.value;
    else if (params.configId === 'verbose') session.verbose = Boolean(params.value);
    else throw rpcError(-32602, `Unknown config option ${params.configId}`);
    return { configOptions: configOptions(session) };
  },

  async 'session/prompt'(params) {
    const session = getSession(params.sessionId);
    const text = (params.prompt || [])
      .map((block) => (block.type === 'text' ? block.text : block.type === 'image' ? '[image]' : `[${block.type}]`))
      .join('\n');
    session.history.push({ role: 'user', text });
    if (!session.title) {
      session.title = text.slice(0, 40);
      update(params.sessionId, { sessionUpdate: 'session_info_update', title: session.title, updatedAt: new Date().toISOString() });
    }

    const turn = { cancelled: false };
    session.turn = turn;
    try {
      const reply = await runTurn(params.sessionId, session, text, turn);
      session.history.push({ role: 'agent', text: reply });
      return { stopReason: turn.cancelled ? 'cancelled' : 'end_turn' };
    } finally {
      session.turn = null;
    }
  },
};

// Notifications (client → agent, no reply expected)
const notificationHandlers = {
  'session/cancel'(params) {
    const session = sessions.get(params.sessionId);
    if (session && session.turn) session.turn.cancelled = true;
  },
};

function requireAuth() {
  if (!authenticated) throw rpcError(-32000, 'Authentication required');
}

function getSession(id) {
  const session = sessions.get(id);
  if (!session) throw rpcError(-32002, `Unknown session ${id}`);
  return session;
}

function rpcError(code, message) {
  const err = new Error(message);
  err.rpcCode = code;
  return err;
}

// ---------------------------------------------------------------------------
// The "model": scripted turns
// ---------------------------------------------------------------------------

async function stream(sessionId, turn, kind, text, delay = 25) {
  // Break text into small chunks to imitate token streaming.
  const pieces = text.match(/[\s\S]{1,12}/g) || [];
  for (const piece of pieces) {
    if (turn.cancelled) return;
    update(sessionId, { sessionUpdate: kind, content: { type: 'text', text: piece } });
    await sleep(delay);
  }
}

async function runTurn(sessionId, session, text, turn) {
  const say = (t, d) => stream(sessionId, turn, 'agent_message_chunk', t, d);
  const think = (t) => (session.verbose ? stream(sessionId, turn, 'agent_thought_chunk', t, 10) : Promise.resolve());

  if (text.startsWith('/echo')) {
    const reply = text.slice(5).trim() || '(nothing to echo)';
    await say(reply);
    return reply;
  }

  if (text.startsWith('/long')) {
    let reply = '';
    for (let i = 1; i <= 60 && !turn.cancelled; i++) {
      const line = `Line ${i}: the quick brown fox jumps over the lazy dog.\n`;
      reply += line;
      await say(line, 40);
    }
    return reply;
  }

  if (!text.startsWith('/demo')) {
    await think(`The user said "${text.slice(0, 60)}". I am a mock agent, so I'll explain what I can do.`);
    const reply =
      `Hi! I'm the **DroidACP mock agent** (mode: \`${session.mode}\`, model: \`${session.model}\`).\n\n` +
      `You said:\n\n> ${text.split('\n').join('\n> ')}\n\n` +
      'Try one of these:\n' +
      '- `/demo` — tool calls, a plan, a diff and a permission prompt\n' +
      '- `/long` — a long streamed answer (tap stop to cancel)\n' +
      '- `/echo hello` — echo text back\n\n' +
      '```kotlin\nfun main() {\n    println("Hello from ACP!")\n}\n```\n';
    await say(reply);
    return reply;
  }

  // --- the full demo ---
  await think('Let me plan this out: read a file, search, then propose an edit.');

  const plan = (statuses) =>
    update(sessionId, {
      sessionUpdate: 'plan',
      entries: [
        { content: 'Read the README', priority: 'high', status: statuses[0] },
        { content: 'Search for TODOs', priority: 'medium', status: statuses[1] },
        { content: 'Fix the greeting', priority: 'high', status: statuses[2] },
      ],
    });
  plan(['in_progress', 'pending', 'pending']);

  // Tool call 1: read
  update(sessionId, {
    sessionUpdate: 'tool_call', toolCallId: 'read-1', title: 'Read README.md', kind: 'read', status: 'in_progress',
    locations: [{ path: `${session.cwd}/README.md`, line: 1 }], rawInput: { path: 'README.md' },
  });
  await sleep(600);
  if (turn.cancelled) return '';
  update(sessionId, {
    sessionUpdate: 'tool_call_update', toolCallId: 'read-1', status: 'completed',
    content: [{ type: 'content', content: { type: 'text', text: '# Demo project\n\nSays hello to the world.' } }],
  });
  plan(['completed', 'in_progress', 'pending']);

  // Tool call 2: search
  update(sessionId, { sessionUpdate: 'tool_call', toolCallId: 'grep-1', title: 'Search for "TODO"', kind: 'search', status: 'pending' });
  await sleep(300);
  update(sessionId, { sessionUpdate: 'tool_call_update', toolCallId: 'grep-1', status: 'in_progress' });
  await sleep(500);
  if (turn.cancelled) return '';
  update(sessionId, {
    sessionUpdate: 'tool_call_update', toolCallId: 'grep-1', status: 'completed',
    content: [{ type: 'content', content: { type: 'text', text: 'src/main.kt:2: // TODO: friendlier greeting' } }],
  });
  plan(['completed', 'completed', 'in_progress']);

  await say('Found one TODO. I want to change the greeting in `src/main.kt`.\n\n');

  // Tool call 3: an edit that needs permission (unless mode is auto)
  const edit = {
    toolCallId: 'edit-1', title: 'Edit src/main.kt', kind: 'edit', status: 'pending',
    locations: [{ path: `${session.cwd}/src/main.kt`, line: 2 }],
    content: [{
      type: 'diff', path: `${session.cwd}/src/main.kt`,
      oldText: 'fun main() {\n    // TODO: friendlier greeting\n    println("hi")\n}\n',
      newText: 'fun main() {\n    println("Hello, world! 👋")\n}\n',
    }],
  };
  update(sessionId, { sessionUpdate: 'tool_call', ...edit });

  let allowed = session.mode === 'auto';
  if (session.mode === 'plan') {
    allowed = false;
  } else if (!allowed) {
    const answer = await request('session/request_permission', {
      sessionId,
      toolCall: { toolCallId: 'edit-1', title: 'Edit src/main.kt', kind: 'edit' },
      options: [
        { optionId: 'allow', name: 'Allow', kind: 'allow_once' },
        { optionId: 'allow-always', name: 'Always allow edits', kind: 'allow_always' },
        { optionId: 'reject', name: 'Reject', kind: 'reject_once' },
      ],
    });
    if (answer.outcome.outcome === 'selected') {
      allowed = answer.outcome.optionId.startsWith('allow');
      if (answer.outcome.optionId === 'allow-always') {
        session.mode = 'auto';
        update(sessionId, { sessionUpdate: 'current_mode_update', currentModeId: 'auto' });
      }
    }
  }
  if (turn.cancelled) return '';

  update(sessionId, { sessionUpdate: 'tool_call_update', toolCallId: 'edit-1', status: 'in_progress' });
  await sleep(400);
  update(sessionId, { sessionUpdate: 'tool_call_update', toolCallId: 'edit-1', status: allowed ? 'completed' : 'failed' });
  plan(['completed', 'completed', allowed ? 'completed' : 'pending']);

  // A terminal-style command shown via rawInput/rawOutput
  update(sessionId, {
    sessionUpdate: 'tool_call', toolCallId: 'run-1', title: 'Run tests', kind: 'execute', status: 'in_progress',
    rawInput: { command: './gradlew test' },
  });
  await sleep(700);
  update(sessionId, {
    sessionUpdate: 'tool_call_update', toolCallId: 'run-1', status: 'completed',
    content: [{ type: 'content', content: { type: 'text', text: '```\nBUILD SUCCESSFUL in 3s\n4 tests completed\n```' } }],
  });

  update(sessionId, { sessionUpdate: 'usage_update', used: 12345, size: 200000, cost: { amount: 0.0123, currency: 'USD' } });

  const reply = allowed
    ? 'Done! The greeting is friendlier now and **all tests pass**. ✅'
    : 'Okay, I left the file unchanged. Let me know if you change your mind.';
  await say(reply);
  return reply;
}

// ---------------------------------------------------------------------------
// Main loop: read one JSON message per line from stdin
// ---------------------------------------------------------------------------

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

rl.on('line', async (line) => {
  if (!line.trim()) return;
  if (LOG_FILE) fs.appendFileSync(LOG_FILE, line + '\n');

  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    respondError(null, -32700, 'Parse error');
    return;
  }

  // A response to one of *our* requests (e.g. request_permission).
  if (msg.method === undefined && msg.id !== undefined) {
    const waiter = waitingForClient.get(msg.id);
    if (waiter) {
      waitingForClient.delete(msg.id);
      if (msg.error) waiter.reject(new Error(msg.error.message));
      else waiter.resolve(msg.result);
    }
    return;
  }

  // A notification (no id).
  if (msg.id === undefined || msg.id === null) {
    const handler = notificationHandlers[msg.method];
    if (handler) handler(msg.params || {});
    return;
  }

  // A request.
  const handler = handlers[msg.method];
  if (!handler) {
    respondError(msg.id, -32601, `Method not found: ${msg.method}`);
    return;
  }
  try {
    const result = await handler(msg.params || {});
    respond(msg.id, result);
  } catch (err) {
    respondError(msg.id, err.rpcCode || -32603, err.message);
  }
});

rl.on('close', () => process.exit(0));
