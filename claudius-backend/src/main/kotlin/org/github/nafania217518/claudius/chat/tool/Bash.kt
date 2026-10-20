package org.github.nafania217518.claudius.chat.tool

import org.github.nafania217518.claudius.workspace.WorkspaceRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

// M8: `sh -c <command>` in the workspace dir. Fail-closed flags → ASK: every command needs
// approval. Sandbox = Landlock via the landrun CLI: rw on the workspace and a per-command temp
// dir only, read/exec on system dirs, no TCP. Landlock does NOT cover UDP, so DNS lookups (and
// other UDP) still work. The env is rebuilt from scratch in both modes: no backend secret leaks.
@Component
class Bash(
    private val registry: WorkspaceRegistry,
    @Value("\${claudius.bash.sandbox:landrun}") private val sandbox: String,
    @Value("\${claudius.bash.landrun:landrun}") private val landrun: String,
    @Value("\${claudius.bash.timeout-seconds:120}") private val timeoutSeconds: Long,
) : Tool {
    override val name = "bash"
    override val description =
        "Run `sh -c <command>` in the workspace dir. Sandboxed: only the workspace and a temp dir are " +
            "writable, no TCP network, a ${timeoutSeconds}s timeout, output capped at $MAX chars."
    override val inputSchema =
        """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}"""

    private val sandboxed = sandbox != "none"

    init {
        if (!sandboxed) log.warn("bash runs UNSANDBOXED; local dev only")
    }

    // Lazy + cached: the first command pays for the probe; a failed probe throws on every call.
    private val enforced: Result<Unit> by lazy { runCatching { probe() } }

    override fun run(arguments: String, workspaceDir: Path, onOutput: (String) -> Unit): String {
        val command = parseArgs(arguments).requireString("command")
        if (sandboxed) enforced.getOrThrow()
        return withTmp { tmp -> exec(argv(workspaceDir, tmp, command), workspaceDir, tmp, onOutput) }
    }

    private fun argv(workspace: Path, tmp: Path, command: String): List<String> =
        if (sandboxed) landrunArgv(landrun, workspace.toRealPath(), tmp, command) else listOf("sh", "-c", command)

    private fun <T> withTmp(body: (Path) -> T): T {
        val tmp = Files.createDirectories(registry.dataDir().resolve("tmp").resolve(UUID.randomUUID().toString()))
        try {
            return body(tmp)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // --best-effort silently drops Landlock on a kernel without it, so prove it bites: a file
    // under dataDir() must NOT be readable by a sandboxed `cat`.
    private fun probe() {
        val file = Files.writeString(registry.dataDir().resolve("probe-${UUID.randomUUID()}"), "probe")
        try {
            val readable = withTmp { tmp ->
                val ws = Files.createDirectories(tmp.resolve("ws"))
                val p = try {
                    start(landrunArgv(landrun, ws, tmp, "cat ${file.toRealPath()}"), ws, tmp)
                } catch (e: IOException) {
                    throw IllegalStateException(
                        "sandbox unavailable: landrun not found; set claudius.bash.sandbox=none for unsandboxed local dev")
                }
                p.inputStream.readAllBytes()
                // A hung probe counts as readable: fail closed.
                !p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() == 0
            }
            check(!readable) { "sandbox unavailable: Landlock is not enforced on this kernel" }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun start(argv: List<String>, dir: Path, tmp: Path): Process {
        val pb = ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val path = System.getenv("PATH") ?: "/usr/bin:/bin"
        pb.environment().apply {
            clear()
            put("PATH", path)
            put("HOME", tmp.toString())
            put("TMPDIR", tmp.toString())
            put("LANG", "C.UTF-8")
        }
        return pb.start()
    }

    private fun exec(argv: List<String>, dir: Path, tmp: Path, onOutput: (String) -> Unit): String {
        val p = start(argv, dir, tmp)
        val out = StringBuilder()
        var total = 0L
        val reader = Thread {
            p.inputStream.reader(Charsets.UTF_8).use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    onOutput(chunk)
                    synchronized(out) {
                        if (out.length < MAX) out.append(chunk, 0, minOf(n, MAX - out.length))
                        total += n
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        // M12: Stop interrupts this thread (Run.interruptible): kill the process tree.
        var stopped = false
        val finished = try {
            p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            stopped = true
            false
        }
        if (!finished) {
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
        }
        // ponytail: a detached grandchild (setsid) could hold the pipe open; a bounded join
        // drops its tail rather than hanging the turn. Upgrade: a cgroup/pid namespace per run.
        reader.join(5_000)
        val result = StringBuilder(synchronized(out) { out.toString() })
        fun suffix(s: String) = result.append(if (result.isEmpty()) s else "\n$s")
        if (total > MAX) suffix("[output truncated: $total chars total]")
        if (stopped) suffix("[stopped]")
        else if (!finished) suffix("[timed out after ${timeoutSeconds}s]")
        else if (p.exitValue() != 0) suffix("[exit code ${p.exitValue()}]")
        return result.toString()
    }

    private companion object {
        const val MAX = 30_000
        val log = LoggerFactory.getLogger(Bash::class.java)
    }
}

// Repeated flags, never comma lists: a comma in a path cannot widen the policy.
internal fun landrunArgv(landrun: String, workspace: Path, tmp: Path, command: String): List<String> =
    listOf(landrun, "--best-effort", "--ignore-missing") +
        listOf("/usr", "/bin", "/lib", "/lib64", "/sbin").flatMap { listOf("--rox", it) } +
        listOf("--ro", "/etc", "--rw", "/dev/null", "--rw", tmp.toString(), "--rw", workspace.toString()) +
        listOf("PATH", "HOME=$tmp", "TMPDIR=$tmp", "LANG=C.UTF-8").flatMap { listOf("--env", it) } +
        listOf("--", "sh", "-c", command)
