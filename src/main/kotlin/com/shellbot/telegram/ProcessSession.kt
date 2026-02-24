package com.shellbot.telegram

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.LinkedList

/**
 * Manages a subprocess with pipe-based I/O and a ring buffer for captured output.
 */
class ProcessSession(command: String) {
    private val process: Process
    private val stdinWriter: OutputStreamWriter
    private val outputLines = LinkedList<String>()
    private val lock = Any()
    private val readerThread: Thread

    companion object {
        private const val MAX_LINES = 100
    }

    init {
        val pb = ProcessBuilder("sh", "-c", command)
        pb.redirectErrorStream(true)
        process = pb.start()

        stdinWriter = OutputStreamWriter(process.outputStream, Charsets.UTF_8)

        readerThread = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        synchronized(lock) {
                            outputLines.addLast(line!!)
                            while (outputLines.size > MAX_LINES) {
                                outputLines.removeFirst()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Process closed
            }
        }, "process-reader")
        readerThread.isDaemon = true
        readerThread.start()
    }

    fun getLastLines(n: Int): List<String> {
        synchronized(lock) {
            val count = n.coerceAtMost(outputLines.size)
            return outputLines.takeLast(count)
        }
    }

    fun sendInput(text: String) {
        try {
            stdinWriter.write(text + "\n")
            stdinWriter.flush()
        } catch (_: Exception) {
            // Process may have closed
        }
    }

    fun isAlive(): Boolean = process.isAlive

    fun kill() {
        process.destroyForcibly()
        readerThread.interrupt()
    }
}
