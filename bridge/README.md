# acp-bridge

ACP agents talk JSON-RPC over **stdin/stdout**, which an Android app can't reach on
another computer. `acp-bridge.js` runs next to the agent and exposes it over a
**WebSocket** (one JSON-RPC message per text frame — the same framing as the
official ACP SDK's WebSocket transport).

It is a single file with **no dependencies** — just Node.js 18 or newer.

```sh
# Claude Code
node acp-bridge.js --cwd ~/code/myapp -- npx -y @agentclientprotocol/claude-agent-acp

# Codex
node acp-bridge.js -- npx -y @agentclientprotocol/codex-acp

# Gemini CLI
node acp-bridge.js -- gemini --acp

# No agent at all: a fake one for trying the app
node acp-bridge.js -- node mock-agent.js
```

On start it prints the addresses to put in the app and a token:

```
  DroidACP bridge ready
  agent:   npx -y @agentclientprotocol/claude-agent-acp
  connect the app to one of:
           ws://127.0.0.1:8765
           ws://192.168.1.20:8765
  token:   Zx8...
```

The token is generated once and saved in `~/.config/droidacp/bridge-token`, so it
stays the same between runs. Override it with `--token` or `ACP_BRIDGE_TOKEN`.

| Option | Meaning |
| --- | --- |
| `--port 8765` | Port to listen on |
| `--host 0.0.0.0` | Interface to bind (`127.0.0.1` = this machine only) |
| `--token <t>` | Token clients must send (`Authorization: Bearer <t>` or `?token=<t>`) |
| `--no-auth` | Don't require a token (trusted networks only!) |
| `--cwd <dir>` | Working directory for the agent process |
| `--tls-cert`/`--tls-key` | Serve `wss://` with your own certificate |
| `--max-clients 4` | Simultaneous connections (each gets its own agent process) |
| `-v`, `--verbose` | Print every JSON-RPC message — a great way to learn ACP |

## Security

Whoever can connect can make the agent read, edit and run things on that computer.
Keep the token secret, prefer a private network such as [Tailscale](https://tailscale.com),
and don't expose the port to the internet. For `wss://`, put the bridge behind
Caddy/nginx or `tailscale serve`, or pass `--tls-cert/--tls-key`.

## Running on the tablet itself (Termux)

In [Termux](https://termux.dev): `pkg install nodejs`, copy these two files over,
install your agent with npm, run the bridge, and connect the app to
`ws://127.0.0.1:8765`. Whether a given agent runs well under Termux depends on the agent.
