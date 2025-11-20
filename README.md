# Aineko

> Back in his room, the Aineko mewls for attention and strops her head against his ankle. She's a late-model Sony, thoroughly upgradeable: Manfred's been working on her in his spare minutes, using an open source development kit to extend her suite of neural networks.

Aineko is a CLI/TUI tool for managing multiple Claude Code agent sessions across projects and devices.

Got an idea while on a walk? Start coding tasks on your phone and let agents work in the background.
Then review and make complex edits later in the IDE on your computer.
Use those free moments on your phone throughout the day productively instead of doomscrolling.

- **Mobile coding**: Use voice input on your phone to kick off tasks to AI agents
- **Asynchronous workflow**: Do other things while waiting for agents to complete their work.
- **Parallelize tasks**: Let agents work on multiple projects and tasks in parallel, switch between sessions as they complete or require input.

**Status**: 🚧 [Scrappy Fiddle](https://dundalek.com/entropic/scrappy-fiddles) 🚧  
Claude code used heavily, once feature set settles will need code cleanup pass, weeding out bugs and improving error handling.

### Features

- Terminal sessions managed using Zellij multiplexer
- Interactive session picker with status indication (waiting, working, idle)
- Access from mobile devices over SSH (for example Termux on Android)
- Works with sandboxed agents (communication tunneled through a file socket)
- Can also run other agents like Codex, Gemini, or Amp (but without status tracking)

### Installation

Prerequisites:

- [babashka](https://babashka.org/) - for running the CLI script
- [zellij](https://zellij.dev/) - multiplexer for handling terminal sessions
- [fzf](https://github.com/junegunn/fzf) - fuzzy finder for interactive session switching

Install using [bbin](https://github.com/babashka/bbin) (prerequisites need to be installed separately):

```sh
bbin install io.github.dundalek/aineko
```

Install using Nix (includes prerequisites):

```sh
nix profile install github:dundalek/aineko
```

Or run it directly without installing:

```sh
nix run github:dundalek/aineko
```

### Quick Start

Sessions are called "seances" to avoid confusion, since both Zellij and Claude also have the concept of sessions.

Start a new seance or attach to an existing one based on the current directory. This will open a shell in the attached Zellij session.
Then run a command to start the agent (you can specify your own sandbox wrappers or other agents like Codex, Gemini, or Amp).
It also works well to run `aineko open` inside an IDE terminal.

```sh
aineko open

# after session shell opens, start the agent:
claude
```

To switch to another seance, detach by pressing `ctrl+o` then `d`.

Then list active seances with the interactive switcher:

```sh
aineko
```

To start multiple seances in a given project, use `new` which always starts a new seance:

```sh
aineko new
```

Optionally specify a different name for the seance:

```sh
aineko new my-feature
```

Set up Claude hooks to subscribe to status updates (modifies `~/.claude/settings.json`):

```sh
aineko setup
```

## Usage

All options:

```
Aineko - manage coding agent sessions

Usage: aineko [COMMAND] [OPTIONS]

Commands:
  (default)             Select and attach to a seance (interactive)
  open, o               Open existing seance for current directory or create new one
  new, n [NAME]         Start a new seance (defaults to current directory name)
  list, l, ls           List all active seances and their status
  watch, w              Select with auto-refresh every 5 seconds

  setup                 Configure Claude Code hooks for aineko
  status                Print diagnostics information
  detail SEANCE_ID      Show detailed information about a seance
  help, h, --help, -h   Show this help message

Status indicators:
  > waiting
  ○ idle
  ● working
  ? unknown
```

### Mobile Setup

1. Install a terminal app, for example [Termux](https://termux.dev/) on Android.
2. Set up SSH access to your development machine (computer or remote server).
    - [Tailscale](https://tailscale.com/) can be used for connecting to a computer from anywhere.
    - Recommendation: Configure SSH key authentication and disable password login.
3. Use Google Voice typing keyboard for voice-based input.
   - In Termux, swipe the special keys toolbar left to be able to use voice typing.

### Tips

Use complementary tools:

- [git worktrees](https://git-scm.com/docs/git-worktree) to work on multiple tasks in the same project in parallel
  - After adding and switching to a worktree, open a new seance
- [Beads](https://github.com/steveyegge/beads) to split work into manageable size and track tasks to prevent context ballooning out of control
  - After adding a Claude skill for beads, it works great with voice dictation. Useful for collecting tasks on the go, just tell what needs to be done and the agent will invoke the CLI to create tasks and manage their dependencies.
- [Lazygit](https://github.com/jesseduffield/lazygit) for quick review and commit of small changes (single letter bindings are quite convenient on mobile)
- [Zoxide](https://github.com/ajeetdsouza/zoxide) for switching project directories
- [Fish shell](https://fishshell.com/) has great autocomplete suggestions to minimize the amount of typing on mobile

Set up aliases to save typing, e.g. `ai` for aineko, `wt` to create a worktree, `z` for zoxide to switch projects.

## Limitations and Workarounds
- Additional typing latency depending on the connection
  - (tried [mosh](https://mosh.org/) but that seems to break scrollback - instead of scrolling, it sends up/down arrows and cycles through shell history)
- Terminal scrollback can be inconvenient when copying text longer than a single screen
  - For example, cannot use copying in neovim because scrollback does not work.
  - When selecting text, zellij automatically copies to clipboard, but includes weird characters instead of newlines.
  - Workaround: Ask the agent to write the content into a markdown file.
- Screen width limited to phone size (56 columns on my Pixel vs the usual 80)
- SSH connections may hang
  - There does not seem to be a way to interrupt/reconnect in Termux - need to start new Termux session and connect to SSH again.
- Scrolling over SSH can be janky
  - In the future could consider alternative approaches to [terminal sessions](https://ansuz.sooke.bc.ca/entry/389) that do not break scrollback.

## Future Ideas

- Attach to corresponding seance when clicking the desktop notification
- Custom TUI for better experience - smoother status refresh, mouse/touch support
- System tray integration on desktop - showing indicator icon, menu to list and open seances
- Status tracking for other agents besides Claude - would [ACP](https://agentcommunicationprotocol.dev/) be useful?
- Push notifications for mobile when agents complete tasks - is there a way to do it without needing to publish an app?

## License

MIT
