package org.github.nafania217518.claudius.chat

import reactor.core.publisher.Sinks
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// One live run. stop() flips the flag and completes the signal; the signal replays to a
// late subscriber, so takeUntilOther ends a model stream that starts after the stop.
// M7: answers holds one future per tool call waiting for the user; stop() rejects them all.
// M9: sub-agents share their parent's Run, so Stop ends them too.
internal class Run {
    val stopped = AtomicBoolean(false)
    val signal: Sinks.Empty<Void> = Sinks.empty()
    val answers = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val working = mutableSetOf<Thread>()

    fun stop() {
        stopped.set(true)
        signal.tryEmitEmpty()
        answers.values.forEach { it.complete(false) }
        synchronized(working) { working.forEach { it.interrupt() } }
    }

    // M12: a tool body that Stop interrupts (bash then kills its process tree). The lock makes
    // sure no interrupt lands after the body; a leftover flag is cleared.
    fun <T> interruptible(body: () -> T): T {
        val t = Thread.currentThread()
        synchronized(working) { if (stopped.get()) t.interrupt(); working.add(t) }
        try {
            return body()
        } finally {
            synchronized(working) { working.remove(t) }
            Thread.interrupted()
        }
    }

    // Registers the future BEFORE emit, so an instant answer is never lost; a stop
    // that already happened completes it false.
    fun ask(id: String, emit: () -> Unit): Boolean {
        val future = CompletableFuture<Boolean>()
        answers[id] = future
        try {
            if (stopped.get()) future.complete(false)
            emit()
            return future.get()
        } finally {
            answers.remove(id)
        }
    }

    fun answer(id: String, approved: Boolean) {
        answers[id]?.complete(approved)
    }
}
