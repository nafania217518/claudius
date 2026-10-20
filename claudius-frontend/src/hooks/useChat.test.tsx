import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, cleanup } from '@testing-library/react'
import { useChat } from './useChat'
import type { StreamHandlers } from '../api/chat'
import { listMessages, type ChatMessage } from '../api/workspaces'
import { allSent, eventsFor, installFakeWs, lastWs, onChat } from '../test/fakeWs'

// The hook is pure orchestration: mock the REST api, fake the WebSocket, and drive
// the run by emitting event frames synchronously inside act().
vi.mock('../api/workspaces')
const chatFrame = () => allSent().find(m => m.type === 'chat')

describe('useChat', () => {
  beforeEach(() => {
    installFakeWs()
    vi.mocked(listMessages).mockReset()
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('no-ops send when there is no active workspace id', () => {
    const { result } = renderHook(() => useChat())
    act(() => {
      result.current.send('hello')
    })
    expect(result.current.messages).toHaveLength(0)
    expect(chatFrame()).toBeUndefined()
  })

  it('no-ops on blank text even with a workspace id', () => {
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('    ')
    })
    expect(result.current.messages).toHaveLength(0)
    expect(chatFrame()).toBeUndefined()
  })

  it('appends a user turn and streams assistant deltas into the next turn', () => {
    onChat((_m, handlers) => {
      handlers.onToken('Hel')
      handlers.onToken('lo')
      handlers.onDone()
      return Promise.resolve()
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    const msgs = result.current.messages
    expect(msgs).toHaveLength(2)
    expect(msgs[0]!.role).toBe('user')
    expect(msgs[0]!.content).toBe('Hi')
    expect(msgs[1]!.role).toBe('assistant')
    expect(msgs[1]!.content).toBe('Hello')
    expect(result.current.streaming).toBe(false)
  })

  it('streams reasoning deltas into the assistant message thinking', () => {
    onChat((_m, handlers) => {
      handlers.onReasoning?.('Let me')
      handlers.onReasoning?.(' think')
      handlers.onToken('Answer')
      handlers.onDone()
      return Promise.resolve()
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    const msgs = result.current.messages
    expect(msgs[1]!.thinking).toBe('Let me think')
    expect(msgs[1]!.content).toBe('Answer')
    expect(result.current.streaming).toBe(false)
  })

  it('loadTranscript refills the thread and toggles loading', async () => {
    vi.mocked(listMessages).mockResolvedValue([
      { id: 's1:0', role: 'user', content: 'old', createdAt: 0 },
    ])
    const { result } = renderHook(() => useChat('s1'))
    await act(async () => {
      await result.current.loadTranscript('s1')
    })
    expect(result.current.loading).toBe(false)
    expect(result.current.messages).toHaveLength(1)
    expect(result.current.messages[0]!.content).toBe('old')
  })

  it('loadTranscript surfaces the error and clears loading on failure', async () => {
    vi.mocked(listMessages).mockRejectedValue(new Error('nope'))
    const { result } = renderHook(() => useChat('s1'))
    await act(async () => {
      await result.current.loadTranscript('s1')
    })
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBe('nope')
  })

  it('merges a tool call and its result into the assistant turn tools', () => {
    onChat((_m, handlers) => {
      handlers.onToolCall?.({ id: 'c1', name: 'CurrentTime', arguments: '', result: '' })
      handlers.onToolResult?.({ id: 'c1', name: 'CurrentTime', arguments: '', result: '12:34' })
      handlers.onToken('ok')
      handlers.onDone()
      return Promise.resolve()
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('time?')
    })
    const tools = result.current.messages[1]!.tools
    expect(tools).toHaveLength(1)
    expect(tools![0]!.id).toBe('c1')
    expect(tools![0]!.name).toBe('CurrentTime')
    expect(tools![0]!.result).toBe('12:34')
    expect(result.current.messages[1]!.content).toBe('ok')
    expect(result.current.streaming).toBe(false)
  })

  it("tool output deltas append to the matching tool's live output", () => {
    onChat((_m, handlers) => {
      handlers.onToolCall?.({ id: 'c1', name: 'bash', arguments: '{}', result: '' })
      handlers.onToolOutput?.({ id: 'c1', name: 'bash', arguments: '', result: 'a\n' })
      handlers.onToolOutput?.({ id: 'c1', name: 'bash', arguments: '', result: 'b\n' })
      handlers.onDone()
      return Promise.resolve()
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('run')
    })
    const tools = result.current.messages[1]!.tools
    expect(tools).toHaveLength(1)
    expect(tools![0]!.output).toBe('a\nb\n')
    expect(tools![0]!.result).toBe('')
  })

  it('tool output for an unknown id is ignored', () => {
    onChat((_m, handlers) => {
      handlers.onToolCall?.({ id: 'c1', name: 'bash', arguments: '{}', result: '' })
      handlers.onToolOutput?.({ id: 'zz', name: 'bash', arguments: '', result: 'x' })
      handlers.onDone()
      return Promise.resolve()
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('run')
    })
    const tools = result.current.messages[1]!.tools
    expect(tools).toHaveLength(1)
    expect(tools![0]!.output).toBeUndefined()
  })

  it('appends a tool result that has no matching call', () => {
    onChat((_m, handlers) => {
      handlers.onToolResult?.({ id: 'c9', name: 'Bash', arguments: '', result: 'done' })
      handlers.onDone()
      return Promise.resolve()
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('go')
    })
    const tools = result.current.messages[1]!.tools
    expect(tools).toHaveLength(1)
    expect(tools![0]!.name).toBe('Bash')
    expect(tools![0]!.result).toBe('done')
  })

  it('fills a result in place, leaving sibling calls untouched', () => {
    onChat((_m, handlers) => {
      handlers.onToolCall?.({ id: 'c1', name: 'A', arguments: '', result: '' })
      handlers.onToolCall?.({ id: 'c2', name: 'B', arguments: '', result: '' })
      handlers.onToolResult?.({ id: 'c1', name: 'A', arguments: '', result: 'r1' })
      handlers.onDone()
      return Promise.resolve()
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('go')
    })
    const tools = result.current.messages[1]!.tools
    expect(tools).toHaveLength(2)
    expect(tools![0]!.result).toBe('r1')
    expect(tools![1]!.name).toBe('B')
    expect(tools![1]!.result).toBe('')
  })

  it('completes an empty turn without writing into the assistant message', () => {
    onChat((_m, handlers) => {
      handlers.onDone()
      return Promise.resolve()
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    // No token/reasoning/tools: the flush early-returns, so the assistant
    // stays empty and only the two seeded turns remain.
    expect(result.current.messages).toHaveLength(2)
    expect(result.current.messages[1]!.content).toBe('')
    expect(result.current.streaming).toBe(false)
  })

  it('an error event lands on the turn and streaming clears when the run ends', () => {
    let handlers!: Required<StreamHandlers>
    onChat((_m, h) => {
      handlers = h
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    act(() => {
      handlers.onError('model exploded')
    })
    // The error shows at once; the turn stays live until the server ends it.
    expect(result.current.messages.at(-1)!.error).toBe('model exploded')
    expect(result.current.streaming).toBe(true)
    act(() => {
      handlers.onDone()
    })
    expect(result.current.streaming).toBe(false)
  })

  it('a failed turn leaves no buffered text and the next turn renders live', async () => {
    // Drive the animation frame by hand so the flush timing is explicit.
    const frames: FrameRequestCallback[] = []
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb))
    vi.stubGlobal('cancelAnimationFrame', () => {})
    let first = true
    onChat((m, handlers) => {
      if (first) {
        first = false
        handlers.onToken('partial')
        lastWs().receive({ type: 'error', runId: m.runId, message: 'boom' })
      } else handlers.onToken('x')
    })
    const { result } = renderHook(() => useChat('s1'))
    await act(async () => {
      await result.current.send('one')
    })
    expect(result.current.messages.at(-1)!.error).toBe('boom')
    act(() => {
      result.current.send('two')
    })
    // The second turn must schedule its own frame; run it before any done.
    expect(frames.length).toBeGreaterThan(0)
    act(() => {
      frames[frames.length - 1]!(0)
    })
    expect(result.current.messages.at(-1)!.content).toBe('x')
  })

  it('stop keeps streaming until the run sends done', () => {
    let handlers!: Required<StreamHandlers>
    onChat((_m, h) => {
      handlers = h
    })
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    act(() => {
      result.current.stop()
    })
    expect(result.current.streaming).toBe(true)
    act(() => handlers.onDone())
    expect(result.current.streaming).toBe(false)
    // A user-initiated stop is not an error.
    expect(result.current.error).toBeNull()
  })

  it('events of another run or workspace are ignored', () => {
    onChat(() => {})
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    act(() => {
      eventsFor(lastWs(), 's1', 'other').onToken('x')
      eventsFor(lastWs(), 's2', chatFrame().runId).onDone()
      lastWs().receive({ type: 'state', workspaceId: 's2', runId: null })
      lastWs().receive({ type: 'error', runId: 'other', message: 'not mine' })
    })
    expect(result.current.streaming).toBe(true)
    expect(result.current.error).toBeNull()
    // A run-less error still shows.
    act(() => lastWs().receive({ type: 'error', message: 'bad frame' }))
    expect(result.current.error).toBe('bad frame')
  })

  it('a stale transcript load is ignored', async () => {
    const deferred: Array<(v: ChatMessage[]) => void> = []
    vi.mocked(listMessages).mockImplementation(
      () => new Promise<ChatMessage[]>(res => deferred.push(res)),
    )
    const { result } = renderHook(() => useChat('s1'))
    let first!: Promise<void>
    let second!: Promise<void>
    act(() => {
      first = result.current.loadTranscript('a')
      second = result.current.loadTranscript('b')
    })
    await act(async () => {
      deferred[1]!([{ id: 'b:0', role: 'user', content: 'from b', createdAt: 0 }])
      await second
    })
    await act(async () => {
      deferred[0]!([{ id: 'a:0', role: 'user', content: 'from a', createdAt: 0 }])
      await first
    })
    expect(result.current.messages[0]!.content).toBe('from b')
    expect(result.current.loading).toBe(false)
  })

  // M7: a permission event parks the call until the user answers it.
  const perm = { id: 'c1', name: 'Bash', arguments: '{"cmd":"ls"}', result: '' }
  const pendingStream = () => {
    let end!: () => void
    onChat((_m, handlers) => {
      handlers.onPermission(perm)
      end = handlers.onDone
    })
    return () => end()
  }

  it('a permission event exposes the pending approval', () => {
    pendingStream()
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    expect(result.current.approval).toEqual(perm)
  })

  it('answer(false) posts a rejection', async () => {
    pendingStream()
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    await act(async () => {
      await result.current.answer(false)
    })
    expect(allSent()).toContainEqual({
      type: 'answer',
      runId: chatFrame().runId,
      id: 'c1',
      approved: false,
    })
  })

  it('answer without a pending approval is a no-op', async () => {
    const { result } = renderHook(() => useChat('s1'))
    await act(async () => {
      await result.current.answer(true)
    })
    expect(allSent().some(m => m.type === 'answer')).toBe(false)
  })

  it('the pending approval clears when the stream ends', async () => {
    const end = pendingStream()
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    act(() => end())
    expect(result.current.approval).toBeNull()
  })

  it('stop clears the pending approval', async () => {
    pendingStream()
    const { result } = renderHook(() => useChat('s1'))
    act(() => {
      result.current.send('Hi')
    })
    await act(async () => {
      await result.current.stop()
    })
    expect(result.current.approval).toBeNull()
  })
})
