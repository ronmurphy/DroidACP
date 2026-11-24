# DroidACP

An Android client for the **[Agent Client Protocol](https://agentclientprotocol.com)** (ACP):
chat with coding agents from an Android tablet or phone, with a home-screen widget.

It can talk to three kinds of agent:

- **On this device**: a coding agent built into the app. It calls Claude with your own
  Anthropic API key and works on a folder on the device. No computer needed.
- **Cloud**: the agent runs in a Linux container hosted by Anthropic, with a real
  shell, git and package managers. Also needs only an API key.
- **On a computer**: Claude Code, Codex, Gemini CLI or any other ACP agent, reached
  through a small bridge.

```
 Built-in agent: everything inside the app
┌──────────────────────────────────────────────┐
│  chat UI  ◀── ACP, in memory ──▶  agent loop │ ── HTTPS ──▶ Claude API
│                                   + tools    │ ──▶ files and shell on the device
└──────────────────────────────────────────────┘

 Agent on a computer
┌──────────────┐   WebSocket (ws/wss)  ┌───────────────┐  stdin/stdout  ┌───────────┐
│   DroidACP   │ ◀──────────────────▶ │ acp-bridge.js │ ◀────────────▶ │ ACP agent │
└──────────────┘   JSON-RPC 2.0        └───────────────┘   JSON-RPC     └───────────┘
```

Both speak the same protocol to the same chat screen. ACP agents normally run as a
subprocess of an editor and talk over stdin/stdout; an Android app can't start
processes on your computer, so `bridge/acp-bridge.js` (one file, no dependencies) runs
next to the agent and exposes it over a WebSocket. The built-in agent skips all that:
its messages are passed along in memory.

## Install

1. Open this repository's **Releases** page on the tablet, tap the newest
   `DroidACP-0.1.N.apk`, and open it. (The first time, Android asks you to allow
   installs from your browser.)
2. Every push to the repo builds a new release through GitHub Actions
   (`.github/workflows/android.yml`), and newer builds install over older ones.

Requires Android 8.0+; built for Android 15 (API 35).

## Run the agent on the device

1. Get an API key from the [Anthropic Console](https://console.anthropic.com). Usage is
   billed to your Anthropic account.
2. In the app, tap **Add agent**, choose **On this device**, and enter a name and the key.
3. Pick the project folder (default `Documents/DroidACP`) and tap **Allow file access**
   if the app asks. Tap the agent to start.

What the built-in agent can do:

| Tool | What it does |
| --- | --- |
| Read, Write, Edit | Read files with line numbers, create files, make exact-match edits (shown as diffs) |
| Glob, Grep | Find files by name pattern and by regular expression |
| Bash | Run a shell command in the project folder |
| TodoWrite | Keep a to-do list, shown as the plan bar |
| Web search, web fetch | Run on Anthropic's side; switch off under options (searches are billed) |

Plus: permission prompts with "always allow", four modes (**Ask first**, **Accept edits**,
**Plan**, **Don't ask**), a model and effort picker, image attachments, `/init` to write
a `CLAUDE.md`, project instructions read from `CLAUDE.md` or `AGENTS.md`, saved
sessions you can reopen, and Stop.

Limits, compared with Claude Code on a computer:

- The shell is Android's own (`/system/bin/sh` with toybox). There is no git, compiler,
  package manager, Python or Node unless the device has them.
- No MCP servers, subagents, hooks or automatic context compaction yet.
- The API key is the only sign-in; a Claude subscription login is not supported.

## Run the agent in the cloud

Cloud mode uses [Claude Managed Agents](https://platform.claude.com/docs/en/managed-agents/overview)
(a beta service): Anthropic runs the agent and gives each session its own Linux
container. The app sends your messages and shows what comes back.

1. In the app, tap **Add agent**, choose **Cloud**, and enter a name and your Anthropic API key.
2. Optional: a GitHub repository address and a GitHub access token. The repository is
   cloned into the container at the start of each session. A fine-grained token with
   *Contents: Read* lets the agent clone; *Read and write* also lets it push.
3. Tap the agent to start.

What to know:

- The container has bash, git, compilers you install, and open internet access.
- Modes: **Auto** (Anthropic's server checks each action; safe ones run, risky ones ask
  or are blocked), **Ask first** (ask before commands and file changes), **Don't ask**.
- Model, effort and web search are chosen under options and apply to the next new session.
- Conversations are stored on Anthropic's side, so you can close the app mid-task and
  reopen the session later from the history list.
- The first session creates an agent and an environment named "DroidACP" in your
  Anthropic account; they are reused afterwards. You can see sessions, with full
  traces and cost, in the Anthropic Console.
- Billed to your API key: tokens, plus container time and web searches.

## Connect an agent on a computer

On the computer that has your code (Node.js 18+), from the top of a DroidACP
checkout:

```sh
git clone https://github.com/ronmurphy/DroidACP
cd DroidACP
node bridge/acp-bridge.js --cwd ~/code/myapp -- npx -y @agentclientprotocol/claude-agent-acp
```

`--cwd` is the project the agent works on (`.` for the DroidACP checkout itself).
It prints something like:

```
  connect the app to one of:
           ws://192.168.1.20:8765
  token:   Zx8kQ...
```

In the app, tap **Add agent**, choose **On a computer (bridge)**, then enter that
address, the token, and the project folder (an absolute path on that computer). Tap
the agent to connect. With Tailscale, use the `100.x` address it lists.

The add-agent screen helps with this:

- **Browse the computer's folders**: once the address and token are in, pick the
  project folder from a list instead of typing it. (The bridge answers with folder
  names only, and only with the right token.)
- **Folders used before**: the arrow in the folder field lists them; a new agent
  starts with the one you used last.
- **Copy command**, **Open Termux**, **Open Tailscale**: the command above with your
  folder filled in, and the apps you need to start the bridge over SSH and reach the
  computer. Missing apps open their download page instead.

Other agents: put a different command after `--`:

| Agent | Command after `--` |
| --- | --- |
| Claude Code | `npx -y @agentclientprotocol/claude-agent-acp` |
| Codex | `npx -y @agentclientprotocol/codex-acp` |
| Gemini CLI | `gemini --acp` |
| Fake agent for testing | `node bridge/mock-agent.js` |

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
- **Session history**: tapping an agent continues your last conversation, replayed by the agent (`session/load`); start a new one from the agent's ⋮ menu or the chat menu, or reopen older ones from the history list (`session/list`)
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
| `acp/Transport.kt`, `acp/WebSocketTransport.kt` | How messages travel: the interface, and OkHttp WebSocket (one JSON-RPC message per text frame) |
| `chat/Transcript.kt` | Pure functions that fold `session/update`s into the chat state (easy to unit test) |
| `chat/AgentController.kt` | Owns a connection, exposes `StateFlow<ChatState>` to the UI |
| `markdown/Markdown.kt`, `diff/LineDiff.kt` | Small Markdown parser, line-diff (longest common subsequence) |

**`core/.../agent/`: the built-in agent (also plain Kotlin)**

| File | What it does |
| --- | --- |
| `LocalAgent.kt` | The agent's half of ACP (`initialize`, `session/new`, `session/prompt`, ...) |
| `InProcessTransport.kt` | The in-memory pipe between the app and an agent inside it |
| `AgentSession.kt` | The agent loop: send the conversation to Claude, run the tools it asks for, repeat |
| `ModelClient.kt`, `AnthropicModelClient.kt` | A small "model" interface, and its implementation on Anthropic's official Java SDK (the only file that uses the SDK) |
| `Tool.kt`, `FileTools.kt`, `SearchTools.kt`, `ShellTool.kt` | The tools, each a small class with a JSON schema |
| `Permissions.kt` | Which tool calls run, which ask first, which are refused, per mode |
| `SystemPrompt.kt`, `SessionStore.kt` | The instructions the model gets; saving and reloading sessions |

**`core/.../cloud/`: cloud mode**

| File | What it does |
| --- | --- |
| `CloudAgent.kt` | The agent's half of ACP, where every request becomes a Managed Agents call; reconnects a dropped stream |
| `CloudEvents.kt` | Translates cloud session events into ACP updates for the chat screen |
| `CloudApi.kt`, `AnthropicCloudApi.kt` | The few API calls needed, and their implementation on the Anthropic SDK |

The agent's tests (`core/src/test/.../agent/`, `.../cloud/`) run the real app-side client against the
real agent with a scripted fake model, and the SDK adapter against a local fake API server.

**`app/`: the Android app**

| File | What it does |
| --- | --- |
| `DroidAcpApp.kt` | Application: holds the controller; picks the transport per agent; wires the controller to the service, widget and notifications |
| `data/ProfileStore.kt`, `data/SecretBox.kt` | Saved agents, encrypted with a key in the Android Keystore |
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
- **On-device agent:** it runs as the app, so it can touch what the app can touch.
  With "All files access" granted that is all of shared storage, not just the project
  folder. Edits outside the project folder always ask first, and in **Don't ask** mode
  nothing asks, so use that mode with care.
- **Cloud agent:** its container has open internet access and holds whatever
  repository you attach, so treat it like a development machine, not a vault. The
  GitHub token is kept on Anthropic's side and never enters the container.
- Saved agents (addresses, tokens, API keys) are stored in the app's private storage,
  encrypted with a key held in the Android Keystore.
- For refusals by Anthropic's safety classifiers, the built-in agent turns on the API's
  server-side fallback (a beta feature), which retries on another model.
- DroidACP advertises no file-system or terminal capabilities to agents: a bridge
  agent runs on another machine, and the built-in one has its own tools.
