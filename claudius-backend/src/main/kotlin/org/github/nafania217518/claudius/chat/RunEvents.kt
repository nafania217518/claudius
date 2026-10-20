package org.github.nafania217518.claudius.chat

// M12: one run's event log + its live listeners. seq starts at 0; `done` is always the last event.
// Replay and append share one lock, so a subscriber sees every event exactly once, in order.
// Listeners run under the lock: they must not block.
// ponytail: the log lives in memory and is lost on a server restart.
// message: the turn's user text ("" for a run restored after a restart). label: the model label.
class RunEvents(val workspaceId: String, val runId: String, val message: String = "", val label: String = "") {
    data class Event(val seq: Int, val event: String, val data: String)

    private val log = mutableListOf<Event>()
    private val listeners = mutableListOf<(Event) -> Unit>()

    @Synchronized
    fun append(event: String, data: String) {
        val e = Event(log.size, event, data)
        log.add(e)
        listeners.toList().forEach { it(e) }
    }

    // M12: the highest seq in the log, -1 when empty.
    @Synchronized
    fun lastSeq(): Int = log.size - 1

    // Replays the events with seq > after, then streams the live ones. Returns the unsubscribe.
    @Synchronized
    fun subscribe(after: Int, listener: (Event) -> Unit): () -> Unit {
        log.drop(after + 1).forEach(listener)
        listeners.add(listener)
        return { synchronized(this) { listeners.remove(listener) } }
    }
}
