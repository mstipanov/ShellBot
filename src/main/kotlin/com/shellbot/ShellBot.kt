package com.shellbot

/**
 * ShellBot - Kotlin implementation of shell_bot_pty_fixed.py
 *
 * Uses ProcessBuilder.inheritIO() to give the child process direct access
 * to the parent's terminal. This means programs that require a real terminal
 * (like claude, vim, htop, etc.) work correctly, including full TUI rendering,
 * ANSI escape codes, and interactive input.
 */
class ShellBot(private val command: String) {
    private var process: Process? = null

    /**
     * Run the command with inherited terminal I/O.
     * Returns the exit code of the command.
     */
    fun run(): Int {
        try {
            val processBuilder = ProcessBuilder("bash", "-c", command)
            processBuilder.inheritIO()

            val env = processBuilder.environment()
            env.putIfAbsent("TERM", "xterm-256color")

            process = processBuilder.start()

            // Ensure the child process is killed if the JVM shuts down unexpectedly
            val shutdownHook = Thread {
                process?.let { p ->
                    if (p.isAlive) {
                        p.destroy()
                    }
                }
            }
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            val exitCode = process!!.waitFor()

            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // JVM is already shutting down
            }

            return exitCode
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            return 1
        }
    }

    /**
     * Stop the ShellBot and kill the process.
     */
    fun stop() {
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                try {
                    p.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (p.isAlive) {
                        p.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    // Ignore
                }
            }
        }
        process = null
    }
}
