# DroidACP

An Android client for the **[Agent Client Protocol](https://agentclientprotocol.com)** (ACP):
chat with coding agents from an Android tablet or phone, with a home-screen widget.

It can talk to four kinds of agent:

- **On this device**: a coding agent built into the app. It calls Claude with your own
  Anthropic API key and works on a folder on the device. No computer needed.
- **Cloud**: the agent runs in a Linux container hosted by Anthropic, with a real
  shell, git and package managers. Also needs only an API key.
- **On a computer**: Claude Code, Codex, Gemini CLI or any other ACP agent, reached
  through a small bridge.
- **PlasmaLLM on a computer**: a second screen for [PlasmaLLM](#be-a-second-screen-for-plasmallm),
  a desktop ACP client for KDE Plasma. No bridge or token.

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
  reopen the session later from the Chats panel (tap the chat's title).
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

## Be a second screen for PlasmaLLM

PlasmaLLM is a desktop ACP client for KDE Plasma (Qt). Its **Settings → Phone access**
lets DroidACP join the chat open on the computer, over Tailscale: the phone shows that
chat with its history, and whatever either side types or answers shows on both.

1. In PlasmaLLM, turn on Settings → Phone access. It listens on the computer's Tailscale
   address, port 8766, and only lets in devices signed in to the same Tailscale account,
   so there is no token.
2. Here, Add agent → **PlasmaLLM on a computer**, and enter the computer's Tailscale name
   (e.g. `my-laptop`). The folder is optional: it is only used for new chats.

Then:

- **Tapping the agent joins the chat open in PlasmaLLM**, whatever you had open here
  before. The menu's *Start a new chat on the computer* starts a new one there.
- **The phone follows the computer.** When PlasmaLLM opens another chat, so does the
  phone, with a note saying so (also when it finds the computer on another chat after a
  dropped connection). Opening a chat from the Chats panel here opens it on the computer
  too. Every phone and tablet joined to PlasmaLLM shows the same chat: the one open there,
  or with PlasmaLLM's tabs, the tab in front. A turn in a tab left behind keeps working
  there, and the note says so.
- **Prompts typed on the computer show up here**, with the reply, and the chat shows
  as working. Stop works for them too. So do messages sent from the Claude app or
  claude.ai when PlasmaLLM's Remote Control is on; they're marked "You – remote
  message", in orange, as in PlasmaLLM.
- **A permission prompt answered on the computer closes here**: the first answer counts.
- A notification tells you when a turn ends while DroidACP is in the background.
  Its **Reply** button sends an answer to that chat without opening the app. If
  PlasmaLLM has moved to another chat, or is already working on a message, the
  notification says so, and tapping it puts your message back in the message box.

DroidACP says it's a companion when it connects, and uses PlasmaLLM's extensions only
when PlasmaLLM answers in kind; anything else on that port is treated as a plain ACP
agent. The protocol is described in PlasmaLLM's `docs/companion-protocol.md`
(DroidACP speaks version 8); DroidACP's side is `core/.../acp/PlasmaLlm.kt`. The bridge
stays: use it for computers without PlasmaLLM.

### Letting PlasmaLLM's agent use this tablet (TabDesk)

On a tablet with [TabDesk](https://github.com/ronmurphy/tabLauncher) installed, a
PlasmaLLM agent's form has **Let the agent use this tablet (through TabDesk)**. With it
on, the agent in PlasmaLLM's chat gets TabDesk's tools for this tablet: for now, the apps
you let it use and which one is in front, and how busy the tablet is (CPU, memory,
battery, each running app's share). DroidACP
only passes the calls on; TabDesk decides what's allowed, and asks you once to approve
DroidACP (TabDesk → Customize → Agent). PlasmaLLM has its own switch for it, "Let the
agent use a connected tablet", and Claude Code fixes a chat's tools when the chat
starts, so the tablet works in chats started after both are on.

- **A bar over the message box** says when the agent is using the tablet, with
  **Stop**, and what keeps it from working otherwise (TabDesk not approved yet, agents
  switched off there, an older PlasmaLLM). Stop here, or on TabDesk's own notification,
  also stops the turn; the agent can use the tablet again after your next message.
- **🛑 Hold**, beside Stop on the bar and in the chat's ⋮ menu at any time, is the
  lasting one: it keeps the agent off the tablet entirely (screen reads and screenshots
  included) until you choose **Let it go on** in DroidACP on the tablet. It stops the
  turn too if the agent is using the tablet at that moment; otherwise the agent's other
  work goes on, and its next tablet call is refused. Your next message doesn't lift it,
  and neither does a restart. Useful when the tablet watches something private, a baby
  monitor say. The agent is told the tablet is on hold, and that asking again won't
  lift it.
- **🛑 from anywhere:** a message starting with 🛑, or one that's just "stop the tablet"
  (or "stop the device", "hold the tablet"), puts the tablet on hold too, whichever
  device you send it from: your phone, the laptop, the Claude app. The tablet's DroidACP
  sees the message in the chat and holds itself; the phone needs no TabDesk.
- **Lifting a hold from anywhere:** the message **"let the tablet go on"** (or "release
  the tablet"), whole, lets it go on again, sent from any device, or from the phone's ⋮
  menu → **Let the tablet go on…**, which asks first. It counts only when a person sent it:
  PlasmaLLM marks every turn as a person's or its own, so the same words from the agent
  (a wake_me, a note, a turn Claude starts by itself) or from the chat's history do nothing.
- **🛑 in the message box**, beside stop while the agent is working in a PlasmaLLM chat,
  on every device, the phone too: it stops the agent's reply, then sends a 🛑, which puts
  the tablet on hold as above. PlasmaLLM takes no message while the agent works, so the
  reply is stopped first. On the tablet itself the hold is set at once as well. The stop
  square beside it only stops the reply.
- **Quiet on this device**: no "finished", permission or problem notifications from
  this agent on the tablet, when your phone already has them.
- A permission the agent asks for while it's using the tablet is answered on your
  phone or laptop, not on the tablet; the tool card says so while it waits.
- An agent that uses the tablet connects again by itself after the connection drops,
  and after the tablet restarts (once it's unlocked the first time), if it was connected
  then. Disconnecting by hand stops both. Turning the setting on offers to let DroidACP
  stay connected in the background.
- **Files**: when the agent copies a file to or from the tablet's shared `TabDeskAgent`
  folder (PlasmaLLM's own file tools, through TabDesk), the pieces pass through DroidACP
  on the tablet, and every device shows how far it has got on the tool's card and in the
  notification ("Copying report.pdf to the tablet: 3 of 8 MB").
- On any device, for any agent, the agent's form offers **Let DroidACP run in the
  background** (Android's own battery dialog) until it's allowed, so the connection and
  its notifications aren't stopped while the screen is off.

The calls follow the companion protocol's tablet extension (version 2 of it), and
TabDesk's side is its `docs/agent-control.md`. DroidACP's side is
`core/.../tablet/TabletRelay.kt` and `app/.../tablet/TabDeskLink.kt`.

## What it does

- Streams the agent's replies, rendered as Markdown (code blocks with Copy, tables, lists)
- Shows **tool calls** (reads, searches, commands) with their status, input and output
- Shows **file edits as diffs**
- **Long tool cards start closed** (over about 20 lines): the header says how long ("120 lines", or
  "+12 −3" for an edit), and a tap opens them full screen, with lines wrapped or scrolling sideways.
  Lines over 2,000 characters (minified code, say) are cut in the chat; the permission dialog
  always shows everything. Commands show as the agent wrote them, with real line breaks, not as JSON
- **Two views**, under the gear at the top of the chat, for every agent on the device:
  **Developer** shows everything above (tool calls, thinking, plans); **Standard** shows just the
  conversation and its pictures (a tool's screenshot or image shows on its own, without its card),
  for people who only chat. A device that had agents before keeps Developer; a new one starts
  with Standard
- **Opening a chat** builds its history out of sight ("Opening the chat… 240 messages so far")
  and shows it once, at its end, instead of racing past as it arrives
- **Permission prompts**: allow once, always allow, or reject, as offered by the agent
- **Plans**: the agent's to-do list, as a collapsible bar
- **Modes and models**: session modes plus ACP config options (model, reasoning level, toggles),
  under the gear at the top of the chat
- **The message box**: one rounded box with **+** (attach a picture), the text, and send, which
  becomes a stop square while the agent works. Under it, a thin **context bar** fills as the chat
  uses the agent's context, fading through amber to red as it nears full (and compaction); it
  drops back after a compaction, and a tap shows the numbers
- **Pixel People** (with PlasmaLLM and two or more tabs): above the message box, one small
  pixel person per PlasmaLLM tab, posed by what its agent is doing: asleep when idle, a laptop
  for commands, a book for reading files, a desk and pencil for edits, a magnifier for searches,
  a hand up when it needs your OK, a tablet for the tablet tools, and more. Each agent chooses
  its own look, shirt included; the bar under its feet is its agent's colours (Claude's
  orange, Gemini's four colours, ...). **Tap** a person to open its
  chat (that never stops anyone's work), **long press** for its name and what it's doing. The
  chat you're in is highlighted, and the strip hides while you type. With PlasmaLLM's
  **break room** (protocol 19), the long press also offers *Take a break*: the person moves to
  the couch at the strip's end, so a full strip stays tidy, while its agent goes on working.
  Tap the room to see who's in it, and tap one to bring it back. It comes back by itself when
  it needs your OK or its chat is opened, and the phone, the tablet and PlasmaLLM's window
  all show the same room. See [docs/PIXEL-PEOPLE.md](docs/PIXEL-PEOPLE.md)
- **Pictures** in the chat (screenshots, the tablet's camera) open full screen with a tap:
  pinch or double-tap to zoom, drag to look around. *Save* puts one in Pictures/DroidACP (Android 10 and
  later), and *Share* offers it to other apps (Photos, Files, a message), as the agent sent it
- **Slash commands**: type `/` to see what the agent offers
- **Images**: attach photos or screenshots when the agent supports them
- **Managing saved agents**: each agent's ⋮ menu has *Edit*, *Copy and edit* (a new agent with the same settings, e.g. to point it at another folder; the original stays as it is) and *Delete* (asks first; only the saved settings go, not files or conversations)
- **Chats, folders and bookmarks**: tapping an agent continues your last conversation, replayed by the agent (`session/load`). **Tap the chat's title** for the Chats panel:
  - pick a folder (one you used before, or browse the computer), then start a new chat there or open one of its chats (`session/list`);
  - bookmark a chat with ☆ to come back to it in one tap, from any folder. Before a bookmark opens, DroidACP checks the agent still has that chat, and offers to remove the bookmark if not;
  - **other agents, one per PlasmaLLM tab** (protocol 15): *New tab with…* opens a new tab on the computer with Claude, Codex, GitHub Copilot or another agent PlasmaLLM lists, in the folder picked. The chat you were on goes on working in its own tab. Agents that can't start yet are greyed out, saying why. Before PlasmaLLM downloads one (npx or uvx), DroidACP asks once for each version. Each tab in the list says its agent (when it isn't Claude) and has ✕ to close it, asking first if its agent is working. A tab whose agent needs signing in, or didn't start, is shown as it is on the computer, with *Close the tab*. Each agent keeps its own chats: the list is the front tab's agent's ("Codex's chats"), and a bookmarked chat opens only once a tab with its agent is in front;
  - switching while the agent is working asks first, since it stops that work. With PlasmaLLM (protocol 8), the panel lists the chats open in PlasmaLLM's other tabs first, with a spinner on the ones working, and marks them in the folder lists and bookmarks ("in another tab"). Opening one of those only brings its tab to the front, so it never asks: the chat you leave goes on working in its own tab. Only a new chat, or one no tab has, asks while the agent works;
  - don't open a chat that's still running in a terminal on the computer: two Claude processes would then work on it. PlasmaLLM (protocol 3) catches this: it says where the chat is open, and offers to close that Claude first, while it's idle;
  - nothing of the chat you leave carries over: its permission questions are cancelled, anything it still sends is ignored, and the agent is told to close it (`session/close`, when it supports that) so it frees what it held. Each chat has its own context on the agent, and a chat you've left costs no tokens.
- **A project's to-do list** (PlasmaLLM, protocol 17): ⋮ → *To do list* shows the chat's folder's TASKLIST.md, the list PlasmaLLM's To do tab and its agents keep. Open items first (tap one for its notes), *Done* for the rest, *The file* for the Markdown as it is. It's read again by itself when it changes on the computer. Read only for now
- **Reconnect** after a dropped connection, continuing the session (`session/resume` / `session/load`)
- **Stays connected in the background** (foreground service), and notifies you when
  the agent finishes or needs permission while you're in another app
- **Home-screen widget**: the chat itself, without opening the app. It shows your
  messages, the replies and the tool calls as they happen, and more of them when you
  make the widget bigger. **Ask** opens a small box over the home screen to type or
  speak a message, **Stop** ends a turn, and **Allow / Deny** answer the agent's
  permission questions. Tap the chat to open the app. If nothing is connected, Ask
  offers to reconnect to your last agent.
- **Protocol log**: every JSON-RPC message in and out, for debugging and learning ACP
- Ctrl+Enter sends, if you use a keyboard cover

## Code tour

For a fuller description of how the pieces fit, what is tested and what could come
next, see [docs/APP-OVERVIEW.md](docs/APP-OVERVIEW.md).

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
| `chat/AgentController.kt` | Owns a connection, exposes `StateFlow<ChatState>` to the UI; follows PlasmaLLM's open chat |
| `acp/PlasmaLlm.kt` | PlasmaLLM's companion protocol: addresses, the `initialize` declaration, `_plasmallm/...` notifications and error reasons |
| `markdown/Markdown.kt`, `diff/LineDiff.kt` | Small Markdown parser, line-diff (longest common subsequence) |
| `people/PixelPeople.kt`, `people/Activity.kt`, `people/Strip.kt` | The Pixel People: reading and drawing `resources/.../people/sprites.txt`, what an agent is doing and its pose, the strip's people |

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
| `service/ReplyReceiver.kt` | The Reply box in "agent finished" notifications: sends the answer to the chat the notification came from |
| `widget/AgentWidget.kt` | Home-screen widget (RemoteViews + `res/layout/widget_agent.xml`) |
| `widget/AgentWidgetService.kt` | The widget's chat list (rows from `core/.../chat/ChatDigest.kt`) |
| `widget/WidgetActions.kt` | The widget's Stop, Allow and Deny buttons |
| `QuickAskActivity.kt` | The widget's Ask: a small box over the home screen, with voice input |

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
./gradlew :app:assembleDebug    # debug APK, signed with the public dev key
```

Release APKs are signed with a private key kept out of git. Put its four Gradle
properties in `~/.gradle/gradle.properties`:

```properties
DROIDACP_RELEASE_STORE_FILE=/path/to/droidacp-release.jks
DROIDACP_RELEASE_STORE_PASSWORD=...
DROIDACP_RELEASE_KEY_ALIAS=droidacp
DROIDACP_RELEASE_KEY_PASSWORD=...
```

Without them the release APK comes out unsigned. GitHub Actions gets the key from
the repo's secrets `DROIDACP_RELEASE_KEYSTORE` (the keystore, base64) and
`DROIDACP_RELEASE_PASSWORD`, and won't publish a release without them.

End-to-end test against the real bridge:

```sh
node bridge/acp-bridge.js --port 18765 --token test -- node bridge/mock-agent.js &
DROIDACP_BRIDGE_URL=ws://127.0.0.1:18765 DROIDACP_BRIDGE_TOKEN=test ./gradlew :core:test
```

## Notes

- **Security:** anyone who can reach the bridge with the token can make the agent
  read, edit and run things on that computer. Keep the token private and use a
  private network.
- **Signing:** releases are signed with a private key kept out of git (see "Build it
  yourself"). Android only installs an update signed with the same key, and apps that
  trust DroidACP (TabDesk's agent control) check it. `keystore/droidacp-dev.jks` is a
  public development key, committed on purpose, for debug builds only. Releases before
  25 November 2026 were signed with it; going from one of those to a newer release
  means uninstalling DroidACP once.
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
