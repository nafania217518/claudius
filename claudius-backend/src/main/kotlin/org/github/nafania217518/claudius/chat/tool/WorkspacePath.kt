package org.github.nafania217518.claudius.chat.tool

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

// M6: every tool path goes through here. Rejects an escape by "..", an absolute path, a
// symlink out of the workspace, and the .claudius dir (the chat DB lives there).
fun resolveInWorkspace(root: Path, path: String): Path {
    val base = root.toRealPath()
    val p = base.resolve(path).normalize()
    require(p.startsWith(base)) { "path escapes the workspace: $path" }
    var existing = p
    // NOFOLLOW: a dangling link counts as existing, so its target is checked, not its parent.
    while (!Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.parent
    val real = runCatching { existing.toRealPath() }.getOrNull()
    require(real != null && real.startsWith(base)) { "path escapes the workspace: $path" }
    val rel = base.relativize(p)
    require(rel.nameCount == 0 || rel.getName(0).toString() != ".claudius") { "path is reserved: $path" }
    return p
}
