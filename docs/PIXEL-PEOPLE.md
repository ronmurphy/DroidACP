# Pixel People

Small pixel people, one per agent, posed by what the agent is doing. DroidACP shows them
above the message box, one per PlasmaLLM tab; TabDesk may show them too. The idea and the
design are the user's: "each Person is an Agent, and they each do different things, and
tapping an agent swaps to its chat."

## In DroidACP

- **Where:** a strip above the message box, when PlasmaLLM (companion protocol 8 or later)
  has two or more tabs with a chat. It hides while the keyboard is up, and scrolls
  sideways when there are many.
- **Tap** a person: opens that tab's chat. A chat another tab has only brings its tab to
  the front, so nothing stops, and no "Stop the agent's work?" question.
- **Long press:** a floating label with the tab's name, the agent and what it's doing, for
  a few seconds. TalkBack reads the same: "plasmaLLM (Claude): reading files". With
  PlasmaLLM's break room (protocol 19), it's a menu instead: the same name and doing, and
  *Take a break*.
- **The break room** (protocol 19): a couch at the strip's end (the `room` pose: `head none`
  and a grid of its own), with how many are on it. Those on a break sit there instead of on
  the strip, while their agents go on working. A tap lists them, each in its pose, with a
  spinner when its agent works; tapping one brings it back. PlasmaLLM keeps who's on a break,
  so the phone, the tablet and its window agree, and brings one back by itself when its tab
  asks for approval or comes to the front.
- **The chat you're in** is highlighted. Its pose comes from what DroidACP sees of the
  chat itself (`ChatState.activity` in `core/.../people/Activity.kt`). The other tabs' poses
  come from PlasmaLLM's per-tab `activity` (protocol 9); before that, it's working or idle.

## What the agent is doing, and its pose

The words are PlasmaLLM's per-tab `activity` vocabulary (protocol 9), which DroidACP also
uses for the chat on screen. Anything new is drawn as plain work, so a newer PlasmaLLM never
breaks an older DroidACP.

| Activity | Pose |
| --- | --- |
| `idle`, `waiting` (a wake_me is pending) | `sleep`: asleep in the chair, Zzz |
| `thinking`, `think`, `starting` | `think`: looking up, a thought bubble |
| `replying` | `talk`: pointing, a speech bubble |
| `read` | `book`: reading a book in the chair |
| `edit`, `delete`, `move` | `write`: at a desk with paper and a pencil |
| `search` | `search`: a magnifying glass |
| `fetch` | `fetch`: binoculars |
| `execute`, `tool`, `working` | `laptop`: on the laptop |
| `permission`, `signin` | `ask`: a hand up, "?" |
| `stopping` | `stop`: both hands up, "!" |
| `tablet` | `tablet`: holding a tablet |
| `subagent` | `helper`: a tiny copy of the same person beside them |
| `note` | `plane`: a paper plane (only that a note went out, never what it says) |
| `error` | `oops`: a sweat drop |
| `stopped` (the agent isn't running) | `empty`: an empty chair |

Ideas waiting for later, from all three agents: a coffee mug for a long command, a flask for
tests and a hammer for builds (from the tool's title), plan mode at a drafting table, a
message from the Claude app on a phone, the tablet's refusals told apart (a locked door, a
queue, an hourglass, a dark tablet), 🛑 hold on a bench by a stop sign, a d20 in D&D Beyond,
and animation (2 to 4 frames, stopped when unseen and with "remove animations").

## A person's look

Each agent designs its own Pixel Person once, from the options in `sprites.txt`, and keeps
it: "a legitimate Identity Pixel Person for the agent, how they see themselves." The choice
is stored with the agent's ID and reused in every pose. Until an agent has chosen, it gets a
default look that stays the same for its project folder, with the folder's first letter on
its shirt and its agent's colours as the shirt.

PlasmaLLM keeps the choices: each agent calls its `pixel_person` tool once per project (only
the user can let it choose again, in PlasmaLLM's settings), and PlasmaLLM sends the look as
each tab's `person` in `_plasmallm/state` (companion protocol 10). DroidACP draws an id it
doesn't know as that part of the default look.

| Option | Ids |
| --- | --- |
| `skin` | `pale`, `light`, `medium-light`, `medium`, `medium-dark`, `dark` |
| `hair` | `short`, `long`, `bob`, `spiky`, `curly`, `bun`, `ponytail`, `mohawk`, `buzz`, `swoop`, `bald` |
| `haircolor` | `black`, `dark-brown`, `brown`, `auburn`, `ginger`, `blond`, `platinum`, `grey`, `white`, `blue`, `teal`, `green`, `pink`, `purple`, `red` |
| `accessories` (up to 2) | `glasses`, `roundglasses`, `sunglasses`, `cap`, `beanie`, `beret`, `headphones`, `bow`, `flower`, `headband`, `crown`, `earring`, `bowtie`, `scarf` |
| `letter` | `A`-`Z`, `0`-`9`, or none |
| `shirt` | `red`, `orange`, `yellow`, `lime`, `green`, `teal`, `cyan`, `blue`, `navy`, `purple`, `pink`, `maroon`, `brown`, `grey`, `black`, `white`, or none (the agent's colours) |

Each person stands on a short bar in its agent's colours, so people from different agents
stay apart whatever they wear. The idea is the user's: "the bar ... frees up the shirt for
y'all to customize." The bar is in every pose, even the empty chair of an agent that isn't
running.

| Agent (its ACP `agentInfo` name contains) | Bar (and shirt, until one is chosen) |
| --- | --- |
| `claude` | Anthropic's clay orange |
| `gemini` | Google's four colours (side by side on the bar, stripes on a shirt) |
| `codex`, `openai` | near black |
| `copilot` | purple |
| `qwen` | violet |
| `mistral`, `vibe` | orange and yellow stripes |
| `deepseek` | blue |
| `opencode` | terminal grey |
| `goose` | white |
| `ollama`, `llama`, `lmstudio` (local models) | grey |
| anything else | plain white |

## The sprite file

`core/src/main/resources/com/droidacp/core/people/sprites.txt` is the one description of
the people: the palette, the options, the head, hair and accessories, shared parts (torsos,
legs, the chair), the poses and their bubbles, and a 3 x 5 font for the shirt's letter. Its
format is described at its top. It's plain text so other apps (TabDesk) can draw the same
people from the same file.

- **DroidACP's reader:** `core/.../people/PixelPeople.kt` (`render(pose, look)` gives 32 x 32
  ARGB pixels); `app/.../ui/PeopleStrip.kt` draws them at a whole number of screen pixels
  per sprite pixel, with no smoothing.
- **Looking at them while drawing:** `python3 -I tools/pixelpeople/preview.py out.png`
  draws every pose; `sheet=looks` draws every hair style, accessory and shirt;
  `poses=think:talk scale=10` draws a few bigger.
- **Tests:** `PixelPeopleTest` draws every pose and option, and checks the shirts, the
  default looks, the activity of a chat and the strip's people.
