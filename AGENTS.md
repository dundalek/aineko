# Aineko

Aineko is a tool for managing CLI coding agents like Claude Code.
Uses Zellij multiplexer to handle terminal sessions.

## Key Concepts

**Seances**: Agent sessions managed by Aineko (named to avoid confusion with Zellij/Claude sessions)

- Each seance maps to a Zellijsession
- Multiple seances can run across different projects
- Multiple seances per project are supported
- Status tracked via Claude Code hooks to prioritize user attention

## Architecture

**Technology**:
- Clojure Babashka CLI script
- Single file implementation in `src/aineko/main.clj`
- Uses `babashka.fs` for file operations and XDG helpers

**State Management**:
- State stored in XDG state dir: `~/.local/state/aineko/seances/SEANCE_ID.edn`
- Hook events tracked to determine seance status

**Sandbox Communication**:
- To pierce through sandbox uses unix domain sockets in project directory `.tmp/aineko/sockets/` and `AINEKO_SOCKET_PATH` env var is set with a location of the socket.
- Outside of sandbox socket listener starts when new seance is created and runs in background, it implements watchdog loop which terminates some time after zellij session is exited.
- Inside sandbox Claude Code hooks invoke `aineko handle`, which forwards the message to the socket.

**Claude Integration**:
- Hooks configured via `aineko setup`
- Subscribes to events: Notification, SessionStart, SessionEnd, Stop, PreToolUse, UserPromptSubmit, SubagentStop
- Sends desktop notifications for permission prompts and idle states
- Documentation for Claude Code hooks is available at: https://docs.anthropic.com/en/docs/claude-code/hooks  

## Development

Run tests with `bb test:once`.

Generate code coverage report using `bb test:coverage`.

## Code Style

- Avoid unnecessary try/catch wrapping in function bodies
- Error handling only selectively at top-level entrypoints
- Rely on default error handlers; add custom messages only when needed for UX 
