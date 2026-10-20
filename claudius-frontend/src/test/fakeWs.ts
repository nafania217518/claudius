import { vi } from 'vitest'
import type { StreamHandlers } from '../api/chat'

// A hand-written WebSocket stand-in: frames are parsed JSON, the server side is the test.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Frame = any

export class FakeWS {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 3
  static all: FakeWS[] = []
  // New sockets count as open at once (no onopen) unless a test turns this off.
  static autoOpen = true
  static onSend: ((ws: FakeWS, m: Frame) => void) | null = null
  readyState = 0
  sent: Frame[] = []
  onopen: ((e: unknown) => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: ((e: unknown) => void) | null = null
  onerror: ((e: unknown) => void) | null = null
  url: string
  constructor(url: string) {
    this.url = url
    FakeWS.all.push(this)
    if (FakeWS.autoOpen) this.readyState = 1
  }
  send(s: string) {
    const m = JSON.parse(s)
    this.sent.push(m)
    FakeWS.onSend?.(this, m)
  }
  close() {
    this.readyState = 3
    this.onclose?.({})
  }
  open() {
    this.readyState = 1
    this.onopen?.({})
  }
  receive(m: Frame) {
    this.onmessage?.({ data: JSON.stringify(m) })
  }
}

export const lastWs = () => FakeWS.all[FakeWS.all.length - 1]!
export const allSent = () => FakeWS.all.flatMap(w => w.sent)

export function installFakeWs() {
  FakeWS.all = []
  FakeWS.autoOpen = true
  FakeWS.onSend = null
  vi.stubGlobal('WebSocket', FakeWS)
}

// Event frames for one run, built from the old StreamHandlers shape.
export function eventsFor(ws: FakeWS, workspaceId: string, runId: string, seq = { n: 0 }) {
  const emit = (event: string, data: string) =>
    ws.receive({ type: 'event', workspaceId, runId, seq: seq.n++, event, data })
  const h: Required<StreamHandlers> = {
    onToken: d => emit('token', d),
    onReasoning: d => emit('reasoning', d),
    onToolCall: o => emit('tool_call', JSON.stringify(o)),
    onToolResult: o => emit('tool_result', JSON.stringify(o)),
    onToolOutput: o => emit('tool_output', JSON.stringify(o)),
    onPermission: o => emit('permission', JSON.stringify(o)),
    onError: s => emit('error', s),
    onCompacted: o => emit('compacted', JSON.stringify(o)),
    onCompacting: () => emit('compacting', ''),
    onUsage: n => emit('usage', JSON.stringify({ contextTokens: n })),
    onDone: () => emit('done', ''),
  }
  return h
}

// Answers each `chat` frame by running `fn` with handlers that emit that run's events.
export function onChat(fn: (m: Frame, h: Required<StreamHandlers>) => void) {
  FakeWS.onSend = (ws, m) => {
    if (m.type === 'chat') fn(m, eventsFor(ws, m.workspaceId, m.runId))
  }
}
