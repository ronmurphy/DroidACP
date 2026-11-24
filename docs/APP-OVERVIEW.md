# DroidACP: how it works, what works, what's next

A briefing for another developer (or another Claude) who wants to understand
DroidACP, connect a desktop app to it, or improve it. It describes the code on
`main` as of late November 2026. Where this document and the code disagree, the
code wins.

## What it is

DroidACP is an Android app for chatting with coding agents. It speaks the
**Agent Client Protocol** (ACP, <https://agentclientprotocol.com>), the same
protocol editors such as Zed use to talk to Claude Code, Codex or Gemini CLI.
The app is the ACP *client*; the agent is something else.

It is meant to stay light: a chat screen, tool-call cards, diffs, permission
prompts and a home-screen widget, not an IDE.

## The ways to reach an agent

Every saved agent ("profile", `core/.../chat/Profile.kt`) has a kind. All of
them end up speaking the same ACP to the same chat screen; only the transport
differs.

| Kind | Where the agent runs | Login / billing | Status |
| --- | --- | --- | --- |
| **On a computer (bridge)** | Any ACP agent on a PC, started by `bridge/acp-bridge.js` | Whatever the agent uses, e.g. a Claude subscription in Claude Code | **Tested end to end** on a phone over Tailscale to CachyOS with Claude Code |
| **On this device** | A coding agent built into the app (`core/.../agent/`) | The user's Anthropic API key | Unit-tested; **not yet tried on a device** |
| **Cloud** | Claude Managed Agents: Anthropic hosts the agent loop and a Linux container per session (`core/.../cloud/`) | The user's Anthropic API key (tokens + container time) | Unit-tested against fakes; **not yet tried against the real service** |
| **PlasmaLLM on a computer** | PlasmaLLM's own agent process; the phone is a second screen for the chat open there | Whatever PlasmaLLM's agent uses | PlasmaLLM's side tried with an unchanged DroidACP; DroidACP's companion side unit-tested against a fake PlasmaLLM (`PlasmaLlmTest`), **not yet tried on a device** |
| *(Termux)* | A bridge running inside Termux on the same phone | As for the bridge | Not a separate kind: a bridge profile pointing at `ws://127.0.0.1:8765` |

Bridge mode is the main path for people without an API key. The two in-app
agents exist so the app also works with no computer at all.

## Architecture

```
 app/ (Android, Jetpack Compose)                 core/ (plain Kotlin, no Android)
┌──────────────────────────────┐   StateFlow   ┌───────────────────────────────────┐
│ ChatScreen, ProfilesScreen,  │ ◀──────────── │ AgentController                   │
│ widget, service, notifications│ ──actions──▶ │   └ AcpClient (JSON-RPC 2.0)      │
└──────────────────────────────┘               │        └ Transport (one per kind) │
                                               └───────────────┬───────────────────┘
                     ┌──────────────────────────┬──────────────┴───────────────┐
              WebSocketTransport         LocalTransport                 CloudTransport
              (OkHttp, ws/wss)           (in-memory pipe)               (in-memory pipe)
                     │                          │                               │
              acp-bridge.js on a PC      LocalAgent + AgentSession       CloudAgent
                     │                   (agent loop, tools,             (Managed Agents
              ACP agent over stdio        Claude Messages API)            sessions + events)
```

Two modules, deliberately split:

- **`core/`** has no Android imports at all, so the protocol, the chat state and
  both built-in agents can be unit-tested on any JVM (`./gradlew :core:test`).
- **`app/`** is the Android shell: Compose UI, a foreground service that keeps the
  connection alive in the background, notifications, the home-screen widget, and
  storage of saved agents (encrypted with an Android Keystore key).

The key seam is `Transport` (`core/.../acp/Transport.kt`): `connect()`,
`send(text)`, `close()`, plus a listener for `onOpen`, `onMessage`, `onClosed`.
One message = one JSON-RPC object. `AgentController` gets a `TransportFactory`
and never knows which kind of agent it is talking to. Adding a new way to reach
an agent (SSH, a desktop gateway, Bluetooth...) means writing one `Transport`.

### Chat state

`AgentController` owns one connection and exposes an immutable `ChatState`
(`core/.../chat/ChatState.kt`). Every ACP `session/update` is folded into it by
pure functions in `Transcript.kt`, which is why most UI behaviour is testable
without Android. Compose redraws from the `StateFlow`.

## ACP: what the client supports

`AcpClient.kt` (client → agent) and `AcpTypes.kt` (parsing, deliberately
forgiving: unknown fields and update types are ignored, never fatal).

**Requests the app sends:** `initialize`, `authenticate`, `session/new`,
`session/load`, `session/resume`, `session/list`, `session/prompt`,
`session/set_mode`, `session/set_config_option`; notification `session/cancel`.

**Agent → client requests handled:** `session/request_permission` only. The app
advertises **no** `fs` or `terminal` capabilities: a bridge agent uses its own
computer's files, and the built-in agents have their own tools. Anything else is
answered "method not found".

**`session/update` kinds shown:** agent / thought / user message chunks, tool
calls and updates (content, diffs, locations, raw input/output), plans (legacy
`plan` and `plan_update`/`plan_removed`), available commands (slash commands),
current mode, config options (select and boolean), session info (title), usage
(context meter and cost), notices, compaction progress.

**Sessions and resume:** tapping a saved agent continues its last session with
`session/load`: the agent replays the conversation as `session/update`s and the
app redraws it. The card's ⋮ menu has "Start a new session"; the chat menu has
"New session" and a history list (`session/list`). If loading fails, the app
starts a new session and says so. For a reconnect after a dropped connection the
app keeps its own transcript and uses `session/resume` when the agent offers it.

Claude Code's adapter (`@agentclientprotocol/claude-agent-acp`, checked at
v0.111) supports `loadSession`, `session/list`, `resume`, `close`, `fork` and
more, and reads its own transcripts from `~/.claude/projects/...`. So **the app
does not need to read `.claude` files itself**; doing it in the adapter also keeps
Codex and Gemini working.

## The bridge (`bridge/acp-bridge.js`)

One Node file, no dependencies, Node 18+. It is effectively the spec for anything
that wants to sit between the phone and an agent:

1. **HTTP server on a port** (default 8765, all interfaces). `--tls-cert/--tls-key`
   for `wss://`.
2. **WebSocket upgrade** on any path. Auth: `Authorization: Bearer <token>` or
   `?token=<token>`; wrong token → HTTP 401. The token is generated once and
   saved in `~/.config/droidacp/bridge-token`.
3. **Each WebSocket connection spawns its own agent process** (the command after
   `--`, in `--cwd`) and stops it when the connection closes. Max 4 at once.
4. **Framing:** one JSON-RPC message per WebSocket text frame on the app side;
   one JSON message per line on the agent's stdin/stdout. The bridge converts.
   The agent's stderr is printed to the terminal, never sent to the app.
5. **`GET /droidacp/folders?path=<dir>`** (same token) returns
   `{path, parent, home, cwd, separator, folders[], truncated}`, folder names only,
   hidden folders skipped. Without `path` it lists the home folder. Errors are
   JSON `{error}` with 401/403/404. The app uses it for its folder picker.

`bridge/mock-agent.js` is a fake ACP agent (streaming, tools, diffs, a plan,
permission prompts, modes, config options, session list/load, cancel) for
testing without spending tokens.

### Writing a different gateway (e.g. in a Qt desktop app)

Anything that implements points 2–4 above works with the app unchanged; point 5
is optional (without it, the folder picker says the bridge is too old). In Qt:
`QWebSocketServer` for the socket, `QProcess` for the agent, a line splitter on
the process's stdout.

The real design question is **one session per phone connection** (what the
bridge does now; simple) versus **sharing a live session with the desktop UI**
(a hub that forwards one agent's updates to both screens and serialises prompts
from both; much more work). Because Claude Code persists sessions to disk, a
middle ground is cheap: separate agent processes, but the phone can
`session/load` a session the desktop created (it shows up in `session/list` for
the same folder), and vice versa, as long as both don't write to it at once.

PlasmaLLM went the shared-session way: see [PlasmaLLM companion](#plasmallm-companion).

## PlasmaLLM companion

PlasmaLLM (a Qt desktop ACP client, a separate repository) hosts DroidACP on port
8766, over Tailscale only, letting in devices of the computer's own Tailscale
account (no token). The link between the two apps is a written spec in
PlasmaLLM's repository, `docs/companion-protocol.md`, currently version 1; changes
go through it, with a version bump. DroidACP's side:

- `ProfileKind.PlasmaLlm`: the user types the computer's Tailscale name;
  `PlasmaLlm.url()` makes it `ws://name:8766`. The folder is optional (empty =
  PlasmaLLM's open folder) and only used for "Start a new chat".
- `initialize` carries `_meta: {plasmallm: {protocol: 1}}`. PlasmaLLM answers with
  `_meta.plasmallm` (protocol, version, the open chat's `sessionId`, `cwd`, `title`,
  `busy`). Without it, DroidACP treats the other end as plain ACP.
- On every connect and reconnect, DroidACP `session/load`s the chat PlasmaLLM has
  open, with that chat's `cwd` (never the phone's saved last session: loading
  another chat would switch the computer's window to it). Loading the open chat
  costs no agent call.
- `_plasmallm/state` naming another chat → load it (states with an empty
  `sessionId` are ignored). `_plasmallm/turn` started/ended → `ChatState.remoteTurn`
  and `busy` for turns sent elsewhere, plus the background notification.
  `_plasmallm/permission_resolved` → close the prompt (answered "cancelled", which
  PlasmaLLM ignores).
- Errors carry `error.data.plasmallm.reason`: `not_open_chat` makes the phone follow
  the open chat; a failed load doesn't start a new chat of DroidACP's own (PlasmaLLM
  already did, and names it).
- Prompts typed elsewhere arrive as `user_message_chunk`s with a `messageId`;
  consecutive user chunks are only merged when their ids match.

All of this lives in `core/.../acp/PlasmaLlm.kt` and `AgentController.kt` (the
`plasma` parts of `Link`). Not built yet: a side chat (needs protocol 2), and the
PlasmaLLM-style side panel on tablets.

## The built-in agent ("On this device")

`core/.../agent/`. A small coding agent in the spirit of Claude Code:

- **Loop** (`AgentSession.kt`): send the conversation to the Claude Messages API,
  run the tool calls in the reply, append results, repeat until the model stops
  (capped at 200 requests per turn). Streaming, adaptive thinking (summarised and
  shown), prompt caching, effort setting, server-side fallback on refusals.
- **Model client** (`AnthropicModelClient.kt`) on Anthropic's official Java SDK;
  the loop itself only sees a small `ModelClient` interface (so tests use a fake,
  and other providers could be added as more implementations).
- **Tools:** Read (with line numbers), Write, Edit (exact unique match, shown as a
  diff before approval), Glob, Grep (both in pure Kotlin), Bash (Android's
  `/system/bin/sh`, timeout, output cap), TodoWrite (shown as the plan bar), and
  Anthropic's server-side web search/fetch (toggle).
- **Permissions** (`Permissions.kt`), modes named after Claude Code's: Ask first,
  Accept edits, Plan (read-only, edits/commands refused), Don't ask. Reads inside
  the project are free; edits and commands ask; "always allow" can be remembered
  per session for edits or per command word (`ls`, `git`), never for compound
  commands (`&&`, pipes, redirection).
- **Context:** system prompt plus the project's `CLAUDE.md` or `AGENTS.md`;
  `/init` writes a `CLAUDE.md`.
- **Sessions** saved as JSON in app storage (API-format messages plus the ACP
  updates for replay).
- **Limits:** no git/compilers unless the device has them; no MCP, subagents,
  hooks or context compaction yet.

## Cloud mode

`core/.../cloud/`. Wraps Claude Managed Agents (beta) as an ACP agent:

- First use creates one Managed Agents *agent* and *environment* named
  "DroidACP" in the user's account (ids remembered per API key); each chat is a
  Managed Agents *session*, created with that session's model/effort/tools as
  overrides. An optional GitHub repository is mounted, with the token kept on
  Anthropic's side.
- `session/prompt` → send `user.message`, then relay the session's event stream
  until it goes idle. Tool calls that need approval become ACP permission
  prompts (`user.tool_confirmation`). Stop → `user.interrupt`. A dropped stream
  is reopened and the gap filled from the event history, de-duplicated by id.
- Modes: Auto (the server judges each call), Ask first, Don't ask. Model, effort
  and web search apply to the next new session.
- History lives on Anthropic's side; `session/load` replays it from the events.

## Testing

```sh
./gradlew :core:test            # ~70 tests: protocol, transcript, markdown, diff,
                                # both built-in agents (scripted fake model / fake
                                # cloud), SDK adapters against a local fake server,
                                # the PlasmaLLM companion against a fake PlasmaLLM
node bridge/acp-bridge.js --port 18765 --token test -- node bridge/mock-agent.js &
DROIDACP_BRIDGE_URL=ws://127.0.0.1:18765 DROIDACP_BRIDGE_TOKEN=test ./gradlew :core:test
                                # adds end-to-end tests through the real bridge
```

The end-to-end tests also run against PlasmaLLM's phone access (with
`node bridge/mock-agent.js` as its agent): leave `DROIDACP_BRIDGE_TOKEN` empty and
set `DROIDACP_BRIDGE_CWD` to the folder PlasmaLLM has open.

GitHub Actions runs both on every push, builds a signed release APK, and
publishes it as a GitHub Release for pushes to `main` (`DroidACP-0.1.<run>.apk`).
The signing key in `keystore/` is a development key, committed on purpose so
updates install over each other.

## Known gaps and good next steps

Roughly in order of value:

1. **Try the two in-app agents on a real device** and fix what turns up. The
   riskiest part is the release build's code shrinking around the Anthropic SDK.
2. **Try the PlasmaLLM companion on a device**, then its next steps: a side chat
   (companion protocol 2) and a PlasmaLLM-like side panel on tablets.
3. **Answer agent-side `fs/*` requests** when the agent and app share files, and
   show the agent's `terminal` output: currently declined by design.
4. **Other model providers** for the built-in agent (an OpenAI-compatible client
   would cover many; Gemini has a free tier) behind the existing `ModelClient`.
5. **SSH transport**: start an ACP agent over SSH, so a PC needs nothing but an
   SSH server. Another `Transport` implementation.
6. **Context compaction** for the built-in agent's long sessions.
7. **File transfer**: download a build artefact from the PC or the cloud
   container to the phone.
8. UI polish for parity with a desktop client: per-message copy/retry, search in
   the conversation, a richer slash-command picker.

## Where to look

| To change... | Look at |
| --- | --- |
| What the chat screen shows | `app/.../ui/ChatScreen.kt`, `MessageViews.kt`, `ToolCallCard.kt` |
| How updates change the chat | `core/.../chat/Transcript.kt` (+ `TranscriptTest`) |
| Protocol requests / parsing | `core/.../acp/AcpClient.kt`, `AcpTypes.kt` |
| Connection lifecycle, resume | `core/.../chat/AgentController.kt` |
| The PlasmaLLM companion | `core/.../acp/PlasmaLlm.kt`, the `plasma` parts of `AgentController.kt` (+ `PlasmaLlmTest`) |
| Picking a transport per agent | `app/.../DroidAcpApp.kt` (`transportFactory`) |
| The add/edit agent form | `app/.../ui/ProfilesScreen.kt`, `FolderPicker.kt` |
| Colours and typography | `app/.../ui/Theme.kt`, `app/src/main/res/values/colors.xml` |
| The bridge | `bridge/acp-bridge.js`, `bridge/README.md` |
