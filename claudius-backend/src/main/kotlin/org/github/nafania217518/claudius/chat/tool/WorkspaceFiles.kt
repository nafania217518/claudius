package org.github.nafania217518.claudius.chat.tool

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isRegularFile

// Shared by glob and grep: regular files under the workspace (links skipped: a link may point
// outside), .claudius skipped, as sorted
// "/"-separated relative paths. A pattern without "/" matches the file name only.
// Java's glob needs a "/" for "**/", so a leading "**/" also tries the rest at the root:
// "**/*.kt" matches "a.kt" as well as "src/b.kt", as models expect.
internal fun workspaceFiles(root: Path, pattern: String?): List<Pair<String, Path>> {
    val base = root.toRealPath()
    val matcher = pattern?.let { p ->
        val fs = FileSystems.getDefault()
        val globs = listOfNotNull(p, p.removePrefix("**/").takeIf { it != p }).map { fs.getPathMatcher("glob:$it") }
        java.nio.file.PathMatcher { path -> globs.any { it.matches(path) } }
    }
    return Files.walk(base).use { s ->
        s.filter { it.isRegularFile(NOFOLLOW_LINKS) }
            .map { base.relativize(it) to it }
            .filter { (rel, _) -> rel.getName(0).toString() != ".claudius" }
            .filter { (rel, _) -> matcher == null || matcher.matches(if ('/' in pattern!!) rel else rel.fileName) }
            .map { (rel, p) -> rel.joinToString("/") to p }
            .toList()
    }.sortedBy { it.first }
}

// Cap a line list at max, then "[truncated]"; none → "no matches".
internal fun capLines(lines: List<String>, max: Int = 200): String =
    when {
        lines.isEmpty() -> "no matches"
        lines.size > max -> (lines.take(max) + "[truncated]").joinToString("\n")
        else -> lines.joinToString("\n")
    }
