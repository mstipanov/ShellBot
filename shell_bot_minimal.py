#!/usr/bin/env python3
"""
MINIMAL Shell bot - Just forwards stdin/stdout.
No threads, no complexity.
"""

import argparse
import subprocess
import sys
import select
import os


class MinimalShellBot:
    def __init__(self, command: str):
        self.command = command
        self.process = None

    def run(self) -> int:
        """Run command and forward I/O."""
        try:
            # Start process
            self.process = subprocess.Popen(
                self.command,
                shell=True,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=0
            )

            # Simple I/O forwarding
            return self._simple_forward()

        except Exception as e:
            print(f"Error: {e}", file=sys.stderr)
            return 1
        finally:
            if self.process:
                if self.process.poll() is None:
                    self.process.terminate()
                    self.process.wait()

    def _simple_forward(self) -> int:
        """Simple I/O forwarding without threads."""
        import fcntl

        # Set stdout/stderr to non-blocking
        stdout_fd = self.process.stdout.fileno()
        stderr_fd = self.process.stderr.fileno()

        for fd in [stdout_fd, stderr_fd]:
            fl = fcntl.fcntl(fd, fcntl.F_GETFL)
            fcntl.fcntl(fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)

        while self.process.poll() is None:
            # Check for output
            read_fds = [stdout_fd, stderr_fd]
            try:
                ready_fds, _, _ = select.select(read_fds, [], [], 0.1)
            except (select.error, KeyboardInterrupt):
                break

            # Read output
            for fd in ready_fds:
                if fd == stdout_fd:
                    try:
                        data = os.read(stdout_fd, 8192)
                        if data:
                            sys.stdout.buffer.write(data)
                            sys.stdout.buffer.flush()
                    except (OSError, IOError):
                        pass
                elif fd == stderr_fd:
                    try:
                        data = os.read(stderr_fd, 8192)
                        if data:
                            sys.stderr.buffer.write(data)
                            sys.stderr.buffer.flush()
                    except (OSError, IOError):
                        pass

        # Wait for process
        return self.process.wait()


def main():
    parser = argparse.ArgumentParser(description="Minimal shell bot")
    parser.add_argument("-c", "--command", required=True, help="Command to run")
    args = parser.parse_args()

    bot = MinimalShellBot(args.command)
    sys.exit(bot.run())


if __name__ == "__main__":
    main()