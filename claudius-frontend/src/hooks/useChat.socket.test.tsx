import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, cleanup } from '@testing-library/react'
import { useChat } from './useChat'
import { listMessages } from '../api/workspaces'
import { FakeWS, allSent, eventsFor, installFakeWs, lastWs, onChat } from '../test/fakeWs'

// M12: the hook talks to a fake WebSocket; the test plays the server.
vi.mock('../api/workspaces')

const perm = { id: 'c1', name: 'Bash', arguments: '{"cmd":"ls"}', result: '' }
const chatFrame = () => allSent().find(m => m.type === 'chat')

describe('useChat over the socket', () => {
  beforeEach(() => {
    installFakeWs()
    vi.mocked(listMessages).mockReset()
    vi.mocked(listMessages).mockResolvedValue([
      { id: 'w1:0', role: 'user', content: 'q', createdAt: 0 },
    ])
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('send starts a run over the socket and renders its events', async () => {
    onChat((_m, h) => {
      h.onToken('Hel')
      h.onToken('lo')
      h.onDone()
    })
    const { result } = renderHook(() => useChat('w1'))
    let ok: boolean | undefined
    await act(async () => {
      ok = await result.current.send('Hi', { providerId: 'p', model: 'm' })
    })
    expect(chatFrame()).toEqual({
      type: 'chat',
      workspaceId: 'w1',
      runId: expect.any(String),
      message: 'Hi',
      providerId: 'p',
      model: 'm',
    })
    expect(result.current.messages[1]!.content).toBe('Hello')
    expect(result.current.streaming).toBe(false)
    expect(ok).toBe(true)
  })

  it('stop sends a stop message for the run', () => {
    onChat(() => {})
    const { result } = renderHook(() => useChat('w1'))
    act(() => {
      void result.current.send('Hi')
    })
    act(() => {
      void result.current.stop()
    })
    expect(allSent()).toContainEqual({ type: 'stop', runId: chatFrame().runId })
  })

  it('answer sends an answer message', () => {
    onChat((_m, h) => h.onPermission(perm))
    const { result } = renderHook(() => useChat('w1'))
    act(() => {
      void result.current.send('Hi')
    })
    expect(result.current.approval).toEqual(perm)
    act(() => {
      void result.current.answer(true)
    })
    expect(allSent()).toContainEqual({
      type: 'answer',
      runId: chatFrame().runId,
      id: 'c1',
      approved: true,
    })
    expect(result.current.approval).toBeNull()
  })

  it('a rejected chat shows its error and ends streaming', async () => {
    onChat(m => lastWs().receive({ type: 'error', runId: m.runId, message: 'busy' }))
    const { result } = renderHook(() => useChat('w1'))
    let ok: boolean | undefined
    await act(async () => {
      ok = await result.current.send('Hi')
    })
    expect(result.current.messages.at(-1)!.error).toBe('busy')
    expect(result.current.streaming).toBe(false)
    expect(ok).toBe(false)
  })

  it('after a reconnect the active workspace is subscribed again from the last seen seq', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const { result } = renderHook(() => useChat('w1'))
    act(() => lastWs().open())
    await act(async () => {
      await result.current.loadTranscript('w1')
    })
    expect(lastWs().sent).toContainEqual({ type: 'subscribe', workspaceId: 'w1', after: -1 })
    act(() => {
      lastWs().receive({ type: 'state', workspaceId: 'w1', runId: 'r1' })
      const h = eventsFor(lastWs(), 'w1', 'r1')
      h.onToken('a')
      h.onToken('b')
      h.onToken('c')
    })
    act(() => {
      lastWs().close()
      vi.advanceTimersByTime(500)
    })
    expect(FakeWS.all).toHaveLength(2)
    act(() => lastWs().open())
    expect(lastWs().sent).toEqual([{ type: 'subscribe', workspaceId: 'w1', after: 2 }])
  })

  it('opening a workspace with an active run shows the run in progress', async () => {
    const frames: FrameRequestCallback[] = []
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb))
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const { result } = renderHook(() => useChat('w1'))
    await act(async () => {
      await result.current.loadTranscript('w1')
    })
    act(() => {
      lastWs().receive({ type: 'state', workspaceId: 'w1', runId: 'r1' })
      const h = eventsFor(lastWs(), 'w1', 'r1')
      h.onToken('par')
      h.onToolCall({ ...perm, id: 'c0' })
      h.onPermission({ ...perm, id: 'c0' })
      h.onToolResult({ ...perm, id: 'c0', result: 'no' })
      h.onToolCall(perm)
      h.onPermission(perm)
    })
    act(() => frames.at(-1)!(0))
    expect(result.current.streaming).toBe(true)
    const msgs = result.current.messages
    expect(msgs.map(m => m.role)).toEqual(['user', 'assistant'])
    expect(msgs[0]!.content).toBe('q')
    expect(msgs[1]!.content).toBe('par')
    expect(msgs[1]!.tools).toHaveLength(2)
    // c0 is answered (it has a result); c1 still waits for an answer.
    expect(result.current.approval?.id).toBe('c1')
  })

  it('a run that ended while disconnected reloads the transcript', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const { result } = renderHook(() => useChat('w1'))
    act(() => lastWs().open())
    await act(async () => {
      await result.current.loadTranscript('w1')
    })
    act(() => lastWs().receive({ type: 'state', workspaceId: 'w1', runId: 'r1' }))
    expect(result.current.streaming).toBe(true)
    act(() => {
      lastWs().close()
      vi.advanceTimersByTime(500)
    })
    vi.mocked(listMessages).mockResolvedValue([
      { id: 'w1:0', role: 'user', content: 'q', createdAt: 0 },
      { id: 'w1:1', role: 'assistant', content: 'full answer', createdAt: 0 },
    ])
    await act(async () => {
      lastWs().open()
      lastWs().receive({ type: 'state', workspaceId: 'w1', runId: null })
    })
    expect(result.current.streaming).toBe(false)
    expect(result.current.messages.at(-1)!.content).toBe('full answer')
  })

  it('switching workspaces does not stop a run', async () => {
    onChat(() => {})
    const { result, rerender } = renderHook(({ id }) => useChat(id), {
      initialProps: { id: 'w1' },
    })
    act(() => {
      void result.current.send('Hi')
    })
    rerender({ id: 'w2' })
    await act(async () => {
      await result.current.loadTranscript('w2')
    })
    expect(allSent().some(m => m.type === 'stop')).toBe(false)
    expect(allSent().at(-1)).toEqual({ type: 'subscribe', workspaceId: 'w2', after: -1 })
  })

  it('closing the page sends nothing that stops the run', () => {
    onChat(() => {})
    const { result, unmount } = renderHook(() => useChat('w1'))
    act(() => {
      void result.current.send('Hi')
    })
    unmount()
    window.dispatchEvent(new Event('beforeunload'))
    window.dispatchEvent(new Event('pagehide'))
    expect(allSent().some(m => m.type === 'stop')).toBe(false)
    expect(allSent().some(m => m.type === 'chat')).toBe(true)
  })

  const restart = async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const frames: FrameRequestCallback[] = []
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb))
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const hook = renderHook(() => useChat('w1'))
    act(() => lastWs().open())
    await act(async () => {
      await hook.result.current.loadTranscript('w1')
    })
    act(() => {
      lastWs().receive({ type: 'state', workspaceId: 'w1', runId: 'r1', lastSeq: -1 })
      const h = eventsFor(lastWs(), 'w1', 'r1')
      h.onToken('a')
      h.onToken('b')
      h.onToken('c')
    })
    act(() => {
      lastWs().close()
      vi.advanceTimersByTime(500)
    })
    await act(async () => {
      lastWs().open()
      lastWs().receive({ type: 'state', workspaceId: 'w1', runId: 'r1', lastSeq: 0 })
    })
    return { ...hook, frames }
  }

  it('a state frame whose last seq is behind ours resets and replays the run', async () => {
    const { result, frames } = await restart()
    expect(listMessages).toHaveBeenCalledTimes(2)
    expect(lastWs().sent.at(-1)).toEqual({ type: 'subscribe', workspaceId: 'w1', after: -1 })
    act(() => {
      lastWs().receive({ type: 'state', workspaceId: 'w1', runId: 'r1', lastSeq: 0 })
      const h = eventsFor(lastWs(), 'w1', 'r1')
      h.onToken('abc')
      // A duplicate of seq 0 is not applied twice.
      lastWs().receive({
        type: 'event',
        workspaceId: 'w1',
        runId: 'r1',
        seq: 0,
        event: 'token',
        data: 'abc',
      })
    })
    act(() => frames.at(-1)!(0))
    expect(result.current.messages.map(m => m.role)).toEqual(['user', 'assistant'])
    expect(result.current.messages[1]!.content).toBe('abc')
    expect(result.current.streaming).toBe(true)
  })

  it('a restored permission after a restart shows the approval dialog', async () => {
    const { result } = await restart()
    act(() => {
      lastWs().receive({ type: 'state', workspaceId: 'w1', runId: 'r1', lastSeq: 1 })
      const h = eventsFor(lastWs(), 'w1', 'r1')
      h.onToolCall(perm)
      h.onPermission(perm)
    })
    expect(result.current.approval).toEqual(perm)
    act(() => result.current.answer(false))
    expect(lastWs().sent).toContainEqual({ type: 'answer', runId: 'r1', id: 'c1', approved: false })
  })

  it("a started frame for another tab's run appends the user and assistant rows and renders its events", async () => {
    const { result } = renderHook(() => useChat('w1'))
    await act(async () => {
      await result.current.loadTranscript('w1')
    })
    act(() => {
      // Another workspace's run is ignored.
      lastWs().receive({
        type: 'started',
        workspaceId: 'w2',
        runId: 'rx',
        message: 'no',
        model: '',
      })
      lastWs().receive({
        type: 'started',
        workspaceId: 'w1',
        runId: 'r9',
        message: 'from tab 2',
        model: 'P/m',
      })
      const h = eventsFor(lastWs(), 'w1', 'r9')
      h.onToken('hey')
      h.onDone()
    })
    const msgs = result.current.messages
    expect(msgs.map(m => m.role)).toEqual(['user', 'user', 'assistant'])
    expect(msgs[1]!.content).toBe('from tab 2')
    expect(msgs[2]!.model).toBe('P/m')
    expect(msgs[2]!.content).toBe('hey')
    expect(result.current.streaming).toBe(false)
  })

  it('a started frame for our own run is ignored', async () => {
    onChat(m =>
      lastWs().receive({
        type: 'started',
        workspaceId: 'w1',
        runId: m.runId,
        message: m.message,
        model: '',
      }),
    )
    const { result } = renderHook(() => useChat('w1'))
    act(() => {
      void result.current.send('Hi')
    })
    expect(result.current.messages.map(m => m.role)).toEqual(['user', 'assistant'])
  })

  it("the state frame's model labels the joined assistant row", async () => {
    const { result } = renderHook(() => useChat('w1'))
    await act(async () => {
      await result.current.loadTranscript('w1')
    })
    act(() =>
      lastWs().receive({
        type: 'state',
        workspaceId: 'w1',
        runId: 'r1',
        lastSeq: -1,
        model: 'P/m',
      }),
    )
    expect(result.current.messages.at(-1)!.model).toBe('P/m')
  })

  it('a runs frame updates the list of running workspaces', () => {
    const { result } = renderHook(() => useChat('w1'))
    expect(result.current.running).toEqual([])
    act(() => lastWs().receive({ type: 'runs', workspaceIds: ['w1', 'w3'] }))
    expect(result.current.running).toEqual(['w1', 'w3'])
  })

  it('a run error lands on the assistant turn, not in the global error', async () => {
    onChat((_m, h) => {
      h.onToken('part')
      h.onError('boom')
      h.onDone()
    })
    const { result } = renderHook(() => useChat('w1'))
    let ok: boolean | undefined
    await act(async () => {
      ok = await result.current.send('Hi')
    })
    expect(ok).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.messages.at(-1)!.error).toBe('boom')

    // A rejected run appends to the same turn's error.
    onChat((m, h) => {
      h.onError('first')
      lastWs().receive({ type: 'error', runId: m.runId, message: 'busy' })
    })
    await act(async () => {
      ok = await result.current.send('again')
    })
    expect(ok).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.messages.at(-1)!.error).toBe('first\nbusy')
  })

  it('an error frame without a run id sets the global error', () => {
    const { result } = renderHook(() => useChat('w1'))
    act(() => lastWs().receive({ type: 'error', message: 'bad frame' }))
    expect(result.current.error).toBe('bad frame')
  })
})
