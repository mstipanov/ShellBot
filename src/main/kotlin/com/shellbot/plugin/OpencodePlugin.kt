package com.shellbot.plugin

import java.util.concurrent.atomic.AtomicReference

/**
 * Plugin for opencode (https://opencode.ai) sessions.
 *
 * Unlike Claude Code — which is a plain scrolling CLI — opencode renders a
 * multi-region TUI. The visible pane is split into up to three sections:
 *
 *   1. The main output section (left) — the assistant's messages, tool calls
 *      and their output. This is the content we want to stream to Telegram.
 *   2. The context / status sidebar (right) — "Context", token/price counters,
 *      MCP, LSP, agent info.
 *   3. The bottom chrome — the active-model line, input box, horizontal
 *      delimiter and the "esc interrupt ... ctrl+p commands" help bar.
 *
 * [filterOutput] therefore isolates section 1: it strips ANSI, truncates each
 * row at the sidebar boundary (dropping the right-hand panel), and drops the
 * bottom chrome rows and mid-scroll header fragments, so Telegram streams only
 * the output section.
 *
 * [checkForNotifications] tracks opencode state (WORKING / IDLE /
 * PERMISSION_REQUIRED) from the isolated output, mirroring [ClaudePlugin].
 */
class OpencodePlugin : SessionPlugin {

    override val name = "OpencodePlugin"

    /** Column at which the right-hand context sidebar begins. Rows are truncated here. */
    private var sidebarColumn: Int = 165

    private enum class OpencodeState { UNKNOWN, WORKING, IDLE, PERMISSION_REQUIRED }

    private val state = AtomicReference(OpencodeState.UNKNOWN)
    private var idleSinceTime: Long = 0
    private var lastNotificationTime: Long = 0

    override fun matches(command: String): Boolean {
        val cmd = command.trim().lowercase()
        return cmd.contains("opencode")
    }

    override fun onUserInput() {
        state.set(OpencodeState.WORKING)
    }

    override fun processImage(filePath: String): String? {
        return "Process this image $filePath"
    }

    override fun processAudio(filePath: String): String {
        return "Transcribe audio file to text $filePath"
    }

    override fun checkForNotifications(currentOutput: String, idleSeconds: Long): List<String> {
        val lines = isolateOutputSection(currentOutput)

        if (lines.isEmpty()) return emptyList()

        val newState = detectState(lines)
        val previous = state.getAndSet(newState)

        if (newState == OpencodeState.IDLE && previous != OpencodeState.IDLE) {
            idleSinceTime = System.currentTimeMillis()
        }

        if (idleSeconds > 0) {
            if (newState == OpencodeState.PERMISSION_REQUIRED) {
                return listOf(SessionPlugin.NOTIFICATION_PERMISSION)
            }
            if (newState == OpencodeState.IDLE) {
                val now = System.currentTimeMillis()
                val timeInIdleState = (now - idleSinceTime) / 1000
                if (timeInIdleState >= idleSeconds && (now - lastNotificationTime) > (idleSeconds * 1000)) {
                    lastNotificationTime = now
                    return listOf(SessionPlugin.NOTIFICATION_IDLE)
                }
            }
            return emptyList()
        }

        if (newState == previous) return emptyList()

        return when (newState) {
            OpencodeState.IDLE -> {
                lastNotificationTime = System.currentTimeMillis()
                listOf(SessionPlugin.NOTIFICATION_IDLE)
            }
            OpencodeState.PERMISSION_REQUIRED -> listOf(SessionPlugin.NOTIFICATION_PERMISSION)
            else -> emptyList()
        }
    }

    /**
     * Isolates the main output section of the opencode TUI and returns its lines.
     *
     * See the class doc for the section layout. Returns the trailing, trimmed
     * output-section lines (similar cardinality to the default /o behavior).
     */
    override fun filterOutput(rawOutput: String): List<String> {
        return isolateOutputSection(rawOutput).takeLast(10)
    }

    override fun getModelInfo(currentOutput: String): String? {
        return detectModel(currentOutput)
    }

    override fun getContextInfo(currentOutput: String): String? {
        return detectContextUsage(currentOutput)
    }

    override fun getSessionTitle(currentOutput: String): String? {
        return detectSessionTitle(currentOutput)
    }

    // ---- Output-section isolation ----

    /**
     * Main output-section extraction: strip ANSI, drop the right-hand sidebar
     * column and the bottom chrome rows, then clean up blank padding.
     */
    private fun isolateOutputSection(rawOutput: String): List<String> {
        val stripped = stripAnsi(rawOutput)

        val isolated = stripped.lines()
            .map { takeUpToSidebar(it) }
            .dropBottomChrome()

        // Collapse runs of blank lines so wrapped multi-line assistant text stays
        // readable on Telegram but stray divider rows are removed.
        return isolated
            .map { stripInputBorder(it) }
            .map { it.trimEnd() }
            .filter { isMeaningfulLine(it) }
            .reduceBlankRuns()
            .trimTrailingBlank()
    }

    /** Removes the leading "┃" glyph that opencode draws as the input/tool panel border. */
    private fun stripInputBorder(line: String): String {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("┃")) return line
        val rest = trimmed.removePrefix("┃").trimStart()
        // Preserve the original left indentation for consistent alignment.
        val indent = " ".repeat(line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))
        return indent + rest
    }

    /** Keeps only the left part of a row, dropping the right-side sidebar content. */
    private fun takeUpToSidebar(line: String): String {
        if (line.length <= sidebarColumn) return line
        return line.take(sidebarColumn).trimEnd()
    }

    /**
     * Removes opencode's bottom chrome and per-turn model lines: the active-model
     * line ("▣ Build · …"), the input-box border, the horizontal delimiter and the
     * esc/ctrl+p help bar.
     *
     * opencode re-renders a model line at the top of each turn and an input box at
     * the very bottom, and it shows tool-execution output inside a "┃"-bordered
     * panel. So two things are needed:
     *  1. Truncate everything below the LAST model line (the trailing interactive
     *     input box / status area).
     *  2. In the kept rows, drop interleaved model lines, the divider, the help
     *     bar, and bare input-box padding ("┃" alone) — but KEEP "┃"-prefixed
     *     rows that carry real tool output (e.g. "┃  $ mvn test …").
     */
    private fun List<String>.dropBottomChrome(): List<String> {
        val modelLine = Regex("^\\s*[▣⭘⬡□○▲]\\s+.*")   // active model / input prompt start

        // Step 1: keep only the rows up to the last model line (drops the trailing
        // interactive input box and status area below it).
        val lastModel = indexOfLast { modelLine.matches(it) }
        val head = if (lastModel >= 0) subList(0, lastModel) else this

        // Step 2: drop remaining interleaved chrome rows, but preserve "┃"-prefixed
        // rows that contain meaningful tool output.
        val chrome = listOf(
            Regex("^\\s*[▣⭘⬡□○▲]\\s+.*"),              // model line
            Regex("^\\s*[╹╺╻╽].*"),                     // input box top/bottom border
            Regex("^\\s*[▀▄█▓▒░_].{8,}"),               // long horizontal delimiters (blank fill)
            Regex("^\\s*esc interrupt.*"),              // help/status bar
            Regex("^\\s*ctrl\\+p commands.*"),          // help/status bar (continuation)
            Regex(".*esc interrupt.*"),                 // help/status bar (may have a leading scroll glyph)
            Regex(".*ctrl\\+p commands.*"),             // help/status bar (continuation)
            Regex("^\\s*Build · .*"),                    // model selector line (status bar)
            Regex("^\\s*~/.*:\\w+\\s*$")                 // git branch display in status bar
        )
        return head.filterNot { line ->
            chrome.any { it.matches(line) } || isBareInputPadding(line)
        }
    }

    /** "┃" alone or "┃  " with only whitespace after it = input-box padding (no content). */
    private fun isBareInputPadding(line: String): Boolean {
        if (!line.contains('┃')) return false
        return line.replace("┃", "").trim().isEmpty()
    }

    private fun isMeaningfulLine(line: String): Boolean {
        if (line.isBlank()) return true
        return when {
            line.contains("───────────────") -> false
            else -> true
        }
    }

    /** Collapses 2+ consecutive blank lines into a single blank line. */
    private fun List<String>.reduceBlankRuns(): List<String> {
        if (isEmpty()) return this
        val out = ArrayList<String>(size)
        var prevBlank = false
        for (line in this) {
            if (line.isBlank()) {
                if (prevBlank) continue
                prevBlank = true
            } else {
                prevBlank = false
            }
            out.add(line)
        }
        return out
    }

    private fun List<String>.trimTrailingBlank(): List<String> {
        return this.dropLastWhile { it.isBlank() }
    }

    // ---- State detection ----

    private fun detectState(lines: List<String>): OpencodeState {
        val lastMeaningful = lines.lastOrNull { it.isNotBlank() } ?: return OpencodeState.WORKING
        if (isInputPrompt(lastMeaningful)) return OpencodeState.IDLE
        if (hasPermissionRequest(lines)) return OpencodeState.PERMISSION_REQUIRED
        return OpencodeState.WORKING
    }

    private fun hasPermissionRequest(lines: List<String>): Boolean {
        val tail = lines.takeLast(5)
        return tail.any { line ->
            line.contains("Allow") ||
            line.contains("Do you want to proceed") ||
            line.contains("deny", ignoreCase = true) && line.contains("allow", ignoreCase = true)
        }
    }

    private fun isInputPrompt(line: String): Boolean {
        if (line.matches(Regex("^\\s*[>❯⏵❱▶►⟩»›\\$]\\s*$"))) {
            return true
        }
        return line.matches(Regex("^\\s*[>❯⏵❱▶►⟩»›\\$]\\s+.*"))
    }

    // ---- Model / context detection ----

    /**
     * Extracts the active model from opencode's model line.
     *
     * Model lines look like "   ▣  Build · DeepSeek V4 IB" or
     * "   ⭘  Plan · deepseek-v4-flash · 3.9s" — a leading mode glyph, the agent
     * name, a "·" separator and the model. A trailing " · <duration>" is stripped.
     */
    private fun detectModel(stripped: String): String? {
        val modelLine = Regex("^\\s*[▣⭘⬡□○▲]\\s+.*·.*")
        val line = stripped.lines()
            .map { stripAnsi(it) }
            .firstOrNull { modelLine.matches(it) } ?: return null

        val rest = line.trim().replace(Regex("^[▣⭘⬡□○▲]\\s*"), "")
        // Split on " · " and drop any trailing duration-like segment (e.g. "3.9s").
        val parts = rest.split(Regex("\\s*·\\s*")).filter { it.isNotBlank() }
        val noTiming = parts.filterNot { it.matches(Regex("^\\d+([.,]\\d+)?s$")) }
        val agent = noTiming.firstOrNull() ?: return null
        val model = noTiming.getOrNull(1) ?: return agent
        return "$agent · $model"
    }

    /**
     * Extracts context / token usage from opencode's right-hand sidebar.
     *
     * The Context section renders the used token count ("76,678 tokens") and the
     * percentage used ("0% used") on consecutive lines. It returns them combined,
     * e.g. "76,678 tokens · 0% used".
     */
    private fun detectContextUsage(stripped: String): String? {
        val lines = stripped.lines().map { stripAnsi(it) }
        val tokenCount = Regex("([\\d.,]+)\\s*tokens?")
        val percent = Regex("(\\d+(?:\\.\\d+)?)%\\s*(?:used|of)")

        val tokens = lines.firstNotNullOfOrNull { l ->
            tokenCount.find(l)?.groupValues?.get(1)
        }
        val percentUsed = lines.firstNotNullOfOrNull { l ->
            percent.find(l)?.groupValues?.get(1)
        }

        if (tokens == null && percentUsed == null) return null
        return listOfNotNull(tokens?.let { "$it tokens" }, percentUsed?.let { "$it% used" }).joinToString(" · ")
    }

    /**
     * Extracts the session title from the top of opencode's right-hand sidebar,
     * above the "Context" section.
     *
     * The sidebar begins at [sidebarColumn]. Its top rows show the session title
     * (possibly spanning multiple wrapped lines), followed by the "Context" header
     * and then token/percent/spent data. This collects the consecutive right-side
     * segments until the first recognizable section header / data line is reached.
     */
    private fun detectSessionTitle(stripped: String): String? {
        val strippedLines = stripped.lines().map { stripAnsi(it) }

        val titleSegments = mutableListOf<String>()
        for (line in strippedLines) {
            val seg = if (line.length <= sidebarColumn) "" else line.substring(sidebarColumn).trim()

            if (seg.isBlank()) {
                // Keep scanning past leading rows, but stop once the title has ended.
                if (titleSegments.isNotEmpty()) break
                continue
            }

            val lower = seg.lowercase()
            val sectionHeaders = listOf("context", "session", "mcp", "lsp")
            val isSectionHeader = sectionHeaders.any { header ->
                val cleaned = lower.dropWhile { it == '▾' || it == '▸' || it == '▼' }.trimStart()
                cleaned == header || cleaned.startsWith("$header ")
            }
            val isContextData = seg.matches(Regex("^[\\d.,]+\\s*[KM]?\\s*tokens?$")) ||
                seg.matches(Regex("^\\d+(?:\\.\\d+)?%\\s*(?:used|of)")) ||
                seg.contains("spent", ignoreCase = true) ||
                seg.startsWith("•")
            val isBoxDrawing = seg.all { it == '─' || it == '━' || it == '╌' || it == '╍' || it == '_' }

            if (isSectionHeader || isContextData || isBoxDrawing) break

            titleSegments.add(seg)
        }

        if (titleSegments.isEmpty()) return null
        return titleSegments.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    // ---- Utilities ----
    private fun stripAnsi(text: String): String {
        return text
            .replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")       // CSI sequences
            .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")       // OSC sequences
            .replace(Regex("\u001B\\([A-Z]"), "")                  // Charset selectors
            .replace(Regex("\u001B[=>]"), "")                      // Keypad mode
            .replace(Regex("\u001B\\[[0-9;]*[Hf]"), "")            // Cursor positioning
            .replace(Regex("[\u000E\u000F]"), "")                  // SO/SI (shift out/in)
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "") // Other C0 control chars (keep \t \n \r)
            .replace(Regex("[\u0080-\u009F]"), "")                 // C1 control chars
    }
}
