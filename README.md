# ShellBot

A terminal wrapper that runs [Claude Code](https://docs.anthropic.com/en/docs/claude-code) inside a tmux session and lets you monitor and control it remotely via Telegram.

Claude Code is a powerful CLI agent, but long-running sessions require you to stay at your terminal. ShellBot solves this by wrapping the session in tmux, tracking Claude's state (working, idle, needs permission), and forwarding clean output to a Telegram bot you control from your phone.

## How It Works

```
┌─────────────┐      tmux session        ┌─────────────┐
│  Your       │◄──── attach/detach ─────►│  Claude     │
│  Terminal   │                          │  Code       │
└─────────────┘                          └──────┬──────┘
                                                │
                    ┌──────────────────────────►│ capture pane
                    │                           │ send keys
              ┌─────┴──────┐                    │
              │  ShellBot  │◄───────────────────┘
              │  Daemon    │
              └─────┬──────┘
                    │
                    │ Telegram Bot API
                    ▼
              ┌────────────┐
              │  Telegram  │
              │  (phone)   │
              └────────────┘
```

1. ShellBot creates a detached tmux session running `claude`
2. Background daemons capture output and poll for Telegram messages
3. The **ClaudePlugin** strips terminal UI artifacts and tracks Claude's state
4. You get notified on Telegram when Claude is idle or needs permission
5. You can send input, press Enter, or Ctrl-C directly from Telegram

## Prerequisites

- Java 21+
- Maven
- tmux
- A Telegram bot token (create one via [@BotFather](https://t.me/BotFather))

## Build and Install

```bash
./install.sh
```

This builds the project, copies the JAR to `~/bin/shellbot.jar`, and creates a `~/bin/shellbot` wrapper script. Make sure `~/bin` is in your `PATH`.

## Configuration

Provide your Telegram bot token via either:

- Environment variable: `export TELEGRAM_BOT_TOKEN=<your-token>`
- File: `~/.shellbot/telegram.token`

Optional settings in `~/.shellbot/config.properties`:

```properties
# Seconds of idle output before sending a notification (default: 10)
idle.notify.seconds=10
```

## Usage

```bash
# Run Claude Code with Telegram control
shellbot -c "claude"

# Run any command
shellbot -c "python3 train.py"

# Verbose mode
shellbot -c "claude" -v
```

ShellBot attaches you to the tmux session -- you interact with Claude Code normally in your terminal. The Telegram bot runs in the background.

## Telegram Commands

Send `/start` to your bot to claim ownership (first user only). Then:

| Command | Description |
|---------|-------------|
| `/sb_output` or `/sb_o` | Show last lines of output |
| `/sb_enter` or `/sb_e` | Send Enter key |
| `/sb_kill` | Send Ctrl-C |
| `/sb_help` | Show help |
| *(any text)* | Forwarded as keyboard input to the session |

## Claude Code Notifications

When running `claude`, the ClaudePlugin automatically detects state changes and notifies you:

- **Idle** -- Claude finished working and is waiting for input
- **Permission required** -- Claude is asking to run a tool and needs your approval

Output sent to Telegram is cleaned up: ANSI escapes, box-drawing characters, and status bar lines are stripped, leaving only the meaningful content.

## File-Based I/O

ShellBot also exposes side-channels for scripting:

- `~/.shellbot/output.txt` -- last captured output (updated every 500ms)
- `~/.shellbot/input.txt` -- write text here to inject keyboard input

## Auto-Restart

The install script's wrapper supports automatic restart. If the wrapped process exits with code 3, ShellBot restarts automatically.

## Project Structure

```
src/main/kotlin/com/shellbot/
├── Main.kt                    # Entry point, CLI parsing
├── TmuxSession.kt             # Core: tmux lifecycle, daemons, Telegram setup
├── ShellBot.kt                # Fallback when tmux is unavailable
├── plugin/
│   ├── SessionPlugin.kt       # Plugin interface
│   ├── SessionPluginLoader.kt # SPI-based plugin discovery
│   └── ClaudePlugin.kt        # State tracking and output filtering
└── telegram/
    ├── TelegramApi.kt          # Telegram Bot HTTP API client
    ├── TelegramBot.kt          # Message handling and command routing
    └── ProcessSession.kt       # Subprocess management (standalone mode)
```

## License

MIT
