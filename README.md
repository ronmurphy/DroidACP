# DroidACP

An Android client for the **[Agent Client Protocol](https://agentclientprotocol.com)** (ACP):
chat with coding agents such as Claude Code, Codex or Gemini CLI from an Android tablet
or phone, with a home-screen widget.

```
 Android tablet                         your computer (or Termux)
┌──────────────┐   WebSocket (ws/wss)  ┌───────────────┐  stdin/stdout  ┌───────────┐
│   DroidACP   │ ◀──────────────────▶ │ acp-bridge.js │ ◀────────────▶ │ ACP agent │
└──────────────┘   JSON-RPC 2.0        └───────────────┘   JSON-RPC     └───────────┘
```

ACP agents normally run as a subprocess of an editor and talk over stdin/stdout. A
tablet can't start processes on your computer, so `bridge/acp-bridge.js` (one file,
no dependencies) runs next to the agent and exposes it over a WebSocket.

## Install

1. Open this repository's **Releases** page on the tablet, tap the newest
   `DroidACP-0.1.N.apk`, and open it. (The first time, Android asks you to allow
   installs from your browser.)
2. Every push to the repo builds a new release through GitHub Actions
   (`.github/workflows/android.yml`), and newer builds install over older ones.

Requires Android 8.0+; built for Android 15 (API 35).

## Connect an agent

On the computer that has your code (Node.js 18+):

```sh
cd DroidACP/bridge
node acp-bridge.js --cwd ~/code/myapp -- npx -y @agentclientprotocol/claude-agent-acp
```

It prints something like:

```
  connect the app to one of:
           ws://192.168.1.20:8765
  token:   Zx8kQ...
```

In the app, tap **Add agent**, then enter that address, the token, and the
project folder (an absolute path on that computer). Tap the agent to connect.

Other agents: put a different command after `--`:

| Agent | Command after `--` |
| --- | --- |
| Claude Code | `npx -y @agentclientprotocol/claude-agent-acp` |
| Codex | `npx -y @agentclientprotocol/codex-acp` |
| Gemini CLI | `gemini --acp` |
| Fake agent for testing | `node mock-agent.js` (in `bridge/`) |

Sign in to the agent on that computer first (e.g. run `claude` once and `/login`).
To reach your computer from outside your home network, use something like
[Tailscale](https://tailscale.com). See [bridge/README.md](bridge/README.md) for all options.

## What it does

- Streams the agent's replies, rendered as Markdown (code blocks with Copy, tables, lists)
- Shows **tool calls** (reads, searches, commands) with their status, input and output
- Shows **file edits as diffs**
- **Permission prompts**: allow once, always allow, or reject, as offered by the agent
- **Plans**: the agent's to-do list, as a collapsible bar
- **Modes and models**: session modes plus ACP config options (model, reasoning level, toggles)
- **Slash commands**: type `/` to see what the agent offers
- **Images**: attach photos or screenshots when the agent supports them
- **Session history**: list and reopen earlier sessions (`session/list`, `session/load`)
- **Reconnect** after a dropped connection, continuing the session (`session/resume` / `session/load`)
- **Stays connected in the background** (foreground service), and notifies you when
  the agent finishes or needs permission while you're in another app
- **Home-screen widget**: the agent's status and latest reply, plus an **Ask** button
  that jumps straight to the text box (it reconnects to your last agent if needed)
- **Protocol log**: every JSON-RPC message in and out, for debugging and learning ACP
- Ctrl+Enter sends, if you use a keyboard cover

## Code tour

The project has two Gradle modules, so the protocol logic can be read and tested
without anything Android-specific:

**`core/`: plain Kotlin, no Android** (`./gradlew :core:test`)

| File | What it does |
| --- | --- |
| `rpc/JsonRpcConnection.kt` | JSON-RPC 2.0 in both directions: matching requests to responses, answering the agent's requests, ordered notifications |
| `acp/AcpClient.kt` | The ACP methods: `initialize`, `session/new`, `session/prompt`, `session/cancel`, ... |
| `acp/AcpTypes.kt` | Kotlin models + forgiving JSON parsing for session updates, tool calls, plans, config options |
| `acp/WebSocketTransport.kt` | OkHttp WebSocket: one JSON-RPC message per text frame |
| `chat/Transcript.kt` | Pure functions that fold `session/update`s into the chat state (easy to unit test) |
| `chat/AgentController.kt` | Owns a connection, exposes `StateFlow<ChatState>` to the UI |
| `markdown/Markdown.kt`, `diff/LineDiff.kt` | Small Markdown parser, line-diff (longest common subsequence) |

**`app/`: the Android app**

| File | What it does |
| --- | --- |
| `DroidAcpApp.kt` | Application: holds the controller; wires it to the service, widget and notifications |
| `MainActivity.kt` | Screen navigation, image picking, connects UI actions to the controller |
| `ui/*.kt` | Jetpack Compose screens (chat, composer, tool cards, dialogs, profiles, log) |
| `service/AgentService.kt` | Foreground service that keeps the connection alive in the background |
| `widget/AgentWidget.kt` | Home-screen widget (RemoteViews + `res/layout/widget_agent.xml`) |

**`bridge/`: Node.js, zero dependencies**

| File | What it does |
| --- | --- |
| `acp-bridge.js` | WebSocket server written from scratch (RFC 6455 framing), one agent process per connection |
| `mock-agent.js` | Fake ACP agent that demos streaming, tools, diffs, plans, permissions and cancellation |

## Build it yourself

Needs JDK 17 and the Android SDK (or just push and let GitHub Actions build it).

```sh
./gradlew :core:test          # protocol unit tests
./gradlew :app:assembleRelease  # APK in app/build/outputs/apk/release/
```

End-to-end test against the real bridge:

```sh
node bridge/acp-bridge.js --port 18765 --token test -- node bridge/mock-agent.js &
DROIDACP_BRIDGE_URL=ws://127.0.0.1:18765 DROIDACP_BRIDGE_TOKEN=test ./gradlew :core:test
```

## Notes

- **Security:** anyone who can reach the bridge with the token can make the agent
  read, edit and run things on that computer. Keep the token private and use a
  private network.
- **Signing:** `keystore/droidacp-dev.jks` is a development key committed on purpose,
  so every build has the same signature and updates install cleanly. Before
  distributing the app to anyone, replace it with a private key kept out of git.
- The app stores agent addresses and tokens in its private app storage.
- DroidACP advertises no file-system or terminal capabilities to the agent, because
  the agent runs on another machine and uses its own tools there.
