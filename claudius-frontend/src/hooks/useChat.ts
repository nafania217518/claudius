import { useCallback, useEffect, useRef, useState } from 'react'
import { dispatch, type StreamHandlers } from '../api/chat'
import { connect, wsUrl, type Frame, type Socket, type Status } from '../api/socket'
import {
  compactWorkspace,
  listMessages,
  type ChatMessage,
  type ToolInvocation,
} from '../api/workspaces'
import type { Selection } from '../components/ModelPicker'

// How it works:
// - The hook owns one WebSocket for its lifetime. Closing it never stops a run.
// - It renders one run at a time into the last assistant row.
// - beginRun installs the event handlers. Deltas go to ref buffers.
// - One rAF flush per frame writes the buffers into the row in one setState.
// - Frames: `state` joins, ends or replays a run on subscribe.
// - `started` shows a run that another tab started in this workspace.
// - `runs` lists the workspaces with a run in progress.
// - `event` feeds the rendered run's handlers, in seq order.
// - `error` ends the run with the error on its row; without a run id it is global.
// - A workspace switch stops rendering the old run. The run goes on server-side.
//   loadTranscript then refills the thread and subscribes to the new workspace.
// - `onUsage(id, tokens)` reports the workspace's new context size.
const dropPending = (m: ChatMessage[]) => (m.some(x => x.pending) ? m.filter(x => !x.pending) : m)

export function useChat(workspaceId?: string, onUsage?: (id: string, tokens: number) => void) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [streaming, setStreaming] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Ids of the workspaces with a run in progress.
  const [running, setRunning] = useState<string[]>([])
  // A Compact click is in flight.
  const [compacting, setCompacting] = useState(false)
  // M7: the tool call the backend waits on; mirrored in a ref so answer() stays stable.
  const [approval, setApprovalState] = useState<ToolInvocation | null>(null)
  const approvalRef = useRef<ToolInvocation | null>(null)
  const setApproval = (call: ToolInvocation | null) => {
    approvalRef.current = call
    setApprovalState(call)
  }

  // Coalesce token deltas into one state update per animation frame (index.md):
  // don't re-render the whole list once per token.
  const pending = useRef('')
  // M3: reasoning deltas ride a parallel buffer flushed in the same frame — a
  // turn's trace and its answer land in one setState, never a torn render.
  const reasoningPending = useRef('')
  // M3: the full trace for the in-flight turn, so flush() can write it whole each
  // frame without re-reading (and nullish-testing) the last message.
  const traceRef = useRef('')
  // M4: this turn's tool-call cards, accumulated live. A tool_call appends a card
  // with an empty result; the matching tool_result fills it in by id. Flushed into
  // the last message whole, like the trace, so call + result land in one render.
  const toolsRef = useRef<ToolInvocation[]>([])
  const toolsPending = useRef(false)
  const rafId = useRef(0)
  // The rendered run's id: stop/answer target it, events of other runs are dropped.
  const runRef = useRef('')
  // M12: the socket, its status, the subscribed workspace and the last seen event seq.
  const sock = useRef<Socket | null>(null)
  const [status, setStatus] = useState<Status>('connecting')
  const subRef = useRef<string | undefined>(undefined)
  const seqRef = useRef(-1)
  const handlersRef = useRef<StreamHandlers | null>(null)
  const streamingRef = useRef(false)
  const okRef = useRef(true)
  const resolveRef = useRef<((ok: boolean) => void) | null>(null)
  const onUsageRef = useRef(onUsage)
  const loadRef = useRef<(id: string) => Promise<void>>(async () => {})
  // Monotonic id source so every message gets a stable React key.
  const idSeq = useRef(0)
  // Sequence for loadTranscript: a slow earlier load must not overwrite a
  // later one when the user switches workspaces fast.
  const loadSeq = useRef(0)

  // The "Compacting context…" row; dropPending removes a leftover one.
  const placeholder = (): ChatMessage => ({
    id: `c:${(idSeq.current += 1)}`,
    role: 'summary',
    content: '',
    pending: true,
    createdAt: Date.now(),
  })

  // Drop every buffered delta and the pending frame. rafId must go back to 0:
  // the `if (!rafId.current)` gate never schedules a flush otherwise.
  const resetBuffers = () => {
    cancelAnimationFrame(rafId.current)
    rafId.current = 0
    pending.current = ''
    reasoningPending.current = ''
    toolsRef.current = []
    toolsPending.current = false
  }

  const flush = () => {
    rafId.current = 0
    // Capture the buffered text+trace+tools before clearing them: the state updater
    // runs later, so reading the refs inside it would see ''.
    const text = pending.current
    const reasoning = reasoningPending.current
    const tools = toolsPending.current ? toolsRef.current : null
    pending.current = ''
    reasoningPending.current = ''
    toolsPending.current = false
    if (!text && !reasoning && !tools) return
    setMessages(m => {
      const next = m.slice()
      const last = next[next.length - 1]
      if (!last) return m
      next[next.length - 1] = {
        ...last,
        content: last.content + text,
        thinking: traceRef.current,
        tools: tools ?? last.tools,
      }
      return next
    })
  }
  const schedule = () => {
    if (!rafId.current) rafId.current = requestAnimationFrame(flush)
  }

  // End the current run: flush what is buffered, free the composer, settle send().
  const endRun = (ok: boolean) => {
    cancelAnimationFrame(rafId.current)
    flush()
    streamingRef.current = false
    setStreaming(false)
    setApproval(null)
    setMessages(dropPending)
    resolveRef.current?.(ok)
    resolveRef.current = null
  }

  // Put a run error on the in-flight assistant row; a second error goes on a new line.
  const turnError = (message: string, assistId: string) =>
    setMessages(m =>
      dropPending(m).map(x =>
        x.id === assistId ? { ...x, error: x.error ? `${x.error}\n${message}` : message } : x,
      ),
    )
  const assistRef = useRef('')

  // Append the user row (when given) and an empty assistant row, then render run `runId`.
  const joinRun = (runId: string, workspace: string, model: string, message?: string) => {
    const assistId = `a:${(idSeq.current += 1)}`
    const user: ChatMessage[] =
      message === undefined
        ? []
        : [
            {
              id: `u:${(idSeq.current += 1)}`,
              role: 'user',
              content: message,
              createdAt: Date.now(),
            },
          ]
    setMessages(msgs => [
      ...msgs,
      ...user,
      { id: assistId, role: 'assistant', content: '', model, createdAt: Date.now() },
    ])
    beginRun(runId, assistId, workspace)
  }

  // Start rendering run `runId` into a new assistant message `assistId`.
  const beginRun = (runId: string, assistId: string, workspace: string) => {
    resetBuffers()
    traceRef.current = ''
    runRef.current = runId
    assistRef.current = assistId
    seqRef.current = -1
    okRef.current = true
    streamingRef.current = true
    setStreaming(true)
    handlersRef.current = {
      onToken: delta => {
        pending.current += delta
        schedule()
      },
      onReasoning: delta => {
        traceRef.current += delta
        reasoningPending.current += delta
        schedule()
      },
      onToolCall: call => {
        toolsRef.current = [...toolsRef.current, { ...call, result: '' }]
        toolsPending.current = true
        schedule()
      },
      onToolResult: result => {
        const tools = toolsRef.current
        const i = tools.findIndex(t => t.id === result.id)
        toolsRef.current =
          i === -1
            ? [...tools, result]
            : tools.map((t, j) => (j === i ? { ...t, result: result.result } : t))
        toolsPending.current = true
        // M12: a replayed permission that already has a result is answered.
        if (approvalRef.current?.id === result.id) setApproval(null)
        schedule()
      },
      // M8: append a live output delta to its card; an unknown id is dropped.
      onToolOutput: chunk => {
        const tools = toolsRef.current
        if (!tools.some(t => t.id === chunk.id)) return
        toolsRef.current = tools.map(t =>
          t.id === chunk.id ? { ...t, output: (t.output ?? '') + chunk.result } : t,
        )
        toolsPending.current = true
        schedule()
      },
      onPermission: call => setApproval(call),
      // M11: the placeholder, then the summary, go right before this turn's reply.
      onCompacting: () =>
        setMessages(m => {
          const i = m.findIndex(x => x.id === assistId)
          const row = placeholder()
          return i === -1 ? [...m, row] : [...m.slice(0, i), row, ...m.slice(i)]
        }),
      onCompacted: summary =>
        setMessages(m => {
          const row = { ...summary, id: `s:${(idSeq.current += 1)}` }
          const p = m.findIndex(x => x.pending)
          if (p !== -1) return m.map((x, j) => (j === p ? row : x))
          const i = m.findIndex(x => x.id === assistId)
          return i === -1 ? [...m, row] : [...m.slice(0, i), row, ...m.slice(i)]
        }),
      onUsage: n => onUsageRef.current?.(workspace, n),
      // The error shows on the turn now; the run ends with `done`.
      onError: message => {
        okRef.current = false
        turnError(message, assistId)
      },
      onDone: () => endRun(okRef.current),
    }
  }

  // M12: one socket per mounted hook. Closing it never stops a run: runs are detached.
  useEffect(() => {
    let opened = false
    const subscribe = (after: number) =>
      sock.current?.send({ type: 'subscribe', workspaceId: subRef.current, after })
    const onFrame = (m: Frame) => {
      if (m.type === 'event') {
        if (m.workspaceId !== subRef.current || m.runId !== runRef.current) return
        if (m.seq <= seqRef.current) return
        seqRef.current = m.seq
        if (handlersRef.current) dispatch(m.event, m.data, handlersRef.current)
      } else if (m.type === 'state') {
        if (m.workspaceId !== subRef.current) return
        if (m.runId === null) {
          // The run ended while we were away: the transcript has its final form.
          if (streamingRef.current) {
            endRun(true)
            void loadRef.current(m.workspaceId)
          }
        } else if (m.runId === runRef.current && m.lastSeq < seqRef.current) {
          // The server restarted and rebuilt the run's log: drop our partial turn,
          // reload the transcript and replay the log from the start.
          resetBuffers()
          runRef.current = ''
          setApproval(null)
          void loadRef.current(m.workspaceId)
        } else if (m.runId !== runRef.current || !streamingRef.current) {
          joinRun(m.runId, m.workspaceId, m.model ?? '')
        }
      } else if (m.type === 'started') {
        // The sender already renders its own run.
        if (m.workspaceId !== subRef.current || m.runId === runRef.current) return
        joinRun(m.runId, m.workspaceId, m.model ?? '', m.message)
      } else if (m.type === 'runs') {
        setRunning(m.workspaceIds)
      } else if (m.type === 'error') {
        if (!m.runId) return setError(m.message)
        if (m.runId !== runRef.current) return
        turnError(m.message, assistRef.current)
        if (streamingRef.current) endRun(false)
      }
    }
    const s = connect(wsUrl(), {
      onMessage: m => onFrame(m),
      onStatus: st => {
        setStatus(st)
        if (st !== 'open') return
        if (opened && subRef.current) subscribe(seqRef.current)
        opened = true
      },
    })
    sock.current = s
    return () => s.close()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    onUsageRef.current = onUsage
  }, [onUsage])

  // Switching workspace (or unmounting) stops rendering the old run. The run itself
  // keeps going server-side; the new subscribe replaces the old one.
  useEffect(() => {
    return () => {
      resetBuffers()
      runRef.current = ''
      handlersRef.current = null
      if (streamingRef.current) endRun(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId])

  const send = useCallback(
    // M10: `selection` picks the provider + model; resolves true when the turn
    // ended without an error, so App can remember the choice for the workspace.
    (
      text: string,
      selection?: Selection,
      model = '',
      compact?: boolean,
    ): Promise<boolean> | undefined => {
      if (!text.trim() || streaming || !workspaceId) return
      setError(null)
      const userId = `u:${(idSeq.current += 1)}`
      const assistId = `a:${(idSeq.current += 1)}`
      setMessages(m => [
        ...m,
        { id: userId, role: 'user', content: text, createdAt: Date.now() },
        { id: assistId, role: 'assistant', content: '', model, createdAt: Date.now() },
      ])
      const runId = crypto.randomUUID()
      // The server auto-subscribes the sender to this workspace.
      subRef.current = workspaceId
      beginRun(runId, assistId, workspaceId)
      return new Promise<boolean>(resolve => {
        resolveRef.current = resolve
        sock.current?.send({
          type: 'chat',
          workspaceId,
          runId,
          message: text,
          ...selection,
          ...(compact ? { compact } : {}),
        })
      })
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [streaming, workspaceId],
  )

  // Switching workspaces refills the transcript from its persisted history.
  // `loading` lets App show a "loading…" state instead of the previous
  // workspace's history while the fetch is in flight.
  const loadTranscript = useCallback(async (id: string) => {
    const seq = (loadSeq.current += 1)
    setLoading(true)
    try {
      const msgs = await listMessages(id)
      if (seq !== loadSeq.current) return
      setMessages(msgs)
      setError(null)
      // M12: watch the workspace's active run (if any) from its first event.
      subRef.current = id
      sock.current?.send({ type: 'subscribe', workspaceId: id, after: -1 })
    } catch (e) {
      if (seq !== loadSeq.current) return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }, [])

  // M12: stop the run server-side; its `done` ends streaming.
  const stop = useCallback(() => {
    setApproval(null)
    sock.current?.send({ type: 'stop', runId: runRef.current })
  }, [])

  // M7: send the user's decision for the pending call. No-op without one.
  const answer = useCallback((approved: boolean) => {
    const call = approvalRef.current
    if (!call) return
    setApproval(null)
    sock.current?.send({ type: 'answer', runId: runRef.current, id: call.id ?? '', approved })
  }, [])

  // M11: compact now; a placeholder shows until the summary replaces it.
  const compact = useCallback(async () => {
    if (!workspaceId) return
    setCompacting(true)
    const row = placeholder()
    setMessages(m => [...m, row])
    try {
      const r = await compactWorkspace(workspaceId)
      const summary = { ...r.summary, id: `s:${(idSeq.current += 1)}` }
      setMessages(m => m.map(x => (x.id === row.id ? summary : x)))
      onUsage?.(workspaceId, r.contextTokens)
    } catch (e) {
      setMessages(m => m.filter(x => x.id !== row.id))
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setCompacting(false)
    }
  }, [workspaceId, onUsage])

  useEffect(() => {
    loadRef.current = loadTranscript
  }, [loadTranscript])

  return {
    status,
    messages,
    streaming,
    loading,
    error,
    running,
    approval,
    send,
    stop,
    answer,
    loadTranscript,
    compact,
    compacting,
  }
}
