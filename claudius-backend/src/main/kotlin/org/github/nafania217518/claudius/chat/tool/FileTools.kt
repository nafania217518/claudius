package org.github.nafania217518.claudius.chat.tool

import org.springframework.stereotype.Component
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

// M6 read-only tools: autoRun, no approval. M7 mutating tools: fail-closed defaults, so the
// loop asks the user first.

private fun schema(vararg required: String, optional: List<String> = emptyList()): String {
    val props = (required.toList() + optional).joinToString(",") { "\"$it\":{\"type\":\"string\"}" }
    return """{"type":"object","properties":{$props},"required":[${required.joinToString(",") { "\"$it\"" }}]}"""
}

private fun existingFile(root: Path, path: String): Path =
    resolveInWorkspace(root, path).also { require(it.isRegularFile()) { "not a file: $path" } }

@Component
class FileRead : Tool {
    override val name = "fileRead"
    override val description = "Read a UTF-8 text file in the workspace."
    override val inputSchema = schema("path")
    override val isReadOnly = true
    override val isConcurrencySafe = true
    override val isDestructive = false

    override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String {
        val text = Files.readString(existingFile(workspaceDir, parseArgs(arguments).requireString("path")))
        return if (text.length <= MAX) text else text.take(MAX) + "\n[truncated: ${text.length} chars total]"
    }

    private companion object { const val MAX = 100_000 }
}

@Component
class Glob : Tool {
    override val name = "glob"
    override val description = "List workspace files matching a glob pattern, e.g. **/*.kt or *.md."
    override val inputSchema = schema("pattern")
    override val isReadOnly = true
    override val isConcurrencySafe = true
    override val isDestructive = false

    override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String =
        capLines(workspaceFiles(workspaceDir, parseArgs(arguments).requireString("pattern")).map { it.first })
}

@Component
class Grep : Tool {
    override val name = "grep"
    override val description = "Search workspace files for a Java regex; optional glob filters the files."
    override val inputSchema = schema("pattern", optional = listOf("glob"))
    override val isReadOnly = true
    override val isConcurrencySafe = true
    override val isDestructive = false

    override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String {
        val args = parseArgs(arguments)
        val regex = Regex(args.requireString("pattern"))
        val out = mutableListOf<String>()
        for ((rel, p) in workspaceFiles(workspaceDir, args.optString("glob"))) {
            if (Files.size(p) > MAX_BYTES) continue
            val lines = try {
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).lines()
            } catch (e: CharacterCodingException) {
                continue
            }
            lines.forEachIndexed { i, line -> if (regex.containsMatchIn(line)) out += "$rel:${i + 1}: $line" }
            if (out.size > MAX_LINES) break
        }
        return capLines(out, MAX_LINES)
    }

    private companion object {
        const val MAX_BYTES = 1_000_000L
        const val MAX_LINES = 200
    }
}

@Component
class FileWrite : Tool {
    override val name = "fileWrite"
    override val description = "Create or overwrite a UTF-8 text file in the workspace."
    override val inputSchema = schema("path", "content")

    override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String {
        val args = parseArgs(arguments)
        val path = args.requireString("path")
        val bytes = args.requireString("content").toByteArray()
        val p = resolveInWorkspace(workspaceDir, path)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
        return "wrote ${bytes.size} bytes to $path"
    }
}

@Component
class FileEdit : Tool {
    override val name = "fileEdit"
    override val description = "Replace one unique occurrence of old_string with new_string in a workspace file."
    override val inputSchema = schema("path", "old_string", "new_string")

    override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String {
        val args = parseArgs(arguments)
        val path = args.requireString("path")
        val old = args.requireString("old_string")
        val p = existingFile(workspaceDir, path)
        val text = Files.readString(p)
        val count = if (old.isEmpty()) 0 else text.split(old).size - 1
        require(count != 0) { "old_string not found in $path" }
        require(count == 1) { "old_string occurs $count times in $path; add context to make it unique" }
        Files.writeString(p, text.replace(old, args.requireString("new_string")))
        return "edited $path"
    }
}
