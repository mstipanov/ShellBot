#!/usr/bin/env python3
"""
Shell bot with pseudo-terminal (pty) support for interactive programs.

Usage:
    shell-bot -c "./adding_game.py"

This wrapper:
- Uses pseudo-terminal (pty) for interactive programs
- Forwards I/O between terminal and command
- Works with programs that need terminal control
"""

import argparse
import sys
import os
import pty
import select
import termios
import tty
import signal
import fcntl
import errno


class ShellBotPTY:
    """Wrapper that uses pseudo-terminal for interactive programs."""

    def __init__(self, command: str):
        self.command = command
        self.pid = None
        self.master_fd = None
        self.running = True

    def run(self) -> int:
        """
        Run the command in a pseudo-terminal.
        Returns the exit code of the command.
        """
        try:
            # Save terminal settings
            stdin_fd = sys.stdin.fileno()
            old_termios = termios.tcgetattr(stdin_fd)

            # Create pseudo-terminal
            pid, master_fd = pty.fork()

            if pid == 0:
                # Child process - run the command
                # Set up environment
                os.environ['TERM'] = 'xterm-256color'

                # Parse command
                import shlex
                args = shlex.split(self.command)

                # Execute command
                if len(args) > 1:
                    os.execvp(args[0], args)
                else:
                    os.execlp('bash', 'bash', '-c', self.command)

            else:
                # Parent process - forward I/O
                self.pid = pid
                self.master_fd = master_fd

                try:
                    # Set terminal to raw mode for our own input
                    tty.setraw(stdin_fd)

                    # Forward I/O between terminal and pty
                    return self._forward_io()

                finally:
                    # Restore terminal settings
                    termios.tcsetattr(stdin_fd, termios.TCSADRAIN, old_termios)

        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            return 1
        finally:
            self._cleanup()

    def _forward_io(self) -> int:
        """Forward I/O between terminal and pty."""
        stdin_fd = sys.stdin.fileno()
        stdout_fd = sys.stdout.fileno()

        # Set stdin to non-blocking
        fl = fcntl.fcntl(stdin_fd, fcntl.F_GETFL)
        fcntl.fcntl(stdin_fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)

        while self.running:
            try:
                # Check which file descriptors are ready
                read_fds = [stdin_fd, self.master_fd]
                ready_fds, _, _ = select.select(read_fds, [], [], 0.1)

                for fd in ready_fds:
                    if fd == stdin_fd:
                        # Terminal -> PTY
                        try:
                            data = os.read(stdin_fd, 8192)
                            if data:
                                # Forward to pty
                                os.write(self.master_fd, data)
                        except (OSError, IOError) as e:
                            if e.errno != errno.EAGAIN:
                                break

                    elif fd == self.master_fd:
                        # PTY -> Terminal
                        try:
                            data = os.read(self.master_fd, 8192)
                            if data:
                                # Forward to terminal
                                os.write(stdout_fd, data)
                                sys.stdout.flush()
                            else:
                                # PTY closed (process exited)
                                self.running = False
                                break
                        except (OSError, IOError):
                            self.running = False
                            break

            except (select.error, KeyboardInterrupt):
                # Ctrl+C or other interruption
                self.running = False
                break
            except Exception:
                self.running = False
                break

        # Wait for child process
        try:
            _, status = os.waitpid(self.pid, 0)
            return os.WEXITSTATUS(status)
        except ChildProcessError:
            return 1

    def _cleanup(self):
        """Cleanup resources."""
        if self.master_fd:
            try:
                os.close(self.master_fd)
            except:
                pass
            self.master_fd = None

        if self.pid:
            try:
                os.kill(self.pid, signal.SIGTERM)
            except:
                pass
            self.pid = None


def main() -> int:
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="Wrapper for interactive CLI programs using pseudo-terminal."
    )
    parser.add_argument(
        "-c", "--command",
        required=True,
        help="The command to wrap"
    )

    args = parser.parse_args()

    bot = ShellBotPTY(args.command)
    return bot.run()


if __name__ == "__main__":
    sys.exit(main())