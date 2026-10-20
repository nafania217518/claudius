import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { connect, type Status } from './socket'
import { FakeWS, installFakeWs, lastWs } from '../test/fakeWs'

describe('socket', () => {
  beforeEach(() => {
    installFakeWs()
    FakeWS.autoOpen = false
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('the socket client answers pings with pongs', () => {
    const got: unknown[] = []
    const s = connect('ws://x/api/ws', { onMessage: m => got.push(m) })
    lastWs().open()
    lastWs().receive({ type: 'ping' })
    lastWs().receive({ type: 'state', runId: null })
    expect(lastWs().sent).toEqual([{ type: 'pong' }])
    expect(got).toEqual([{ type: 'state', runId: null }])
    s.close()
  })

  it('the socket client reconnects with backoff after the connection drops', () => {
    const statuses: Status[] = []
    const s = connect('ws://x/api/ws', { onMessage: () => {}, onStatus: st => statuses.push(st) })
    lastWs().open()
    for (const delay of [500, 1000, 2000, 4000, 5000, 5000]) {
      const n = FakeWS.all.length
      lastWs().close()
      vi.advanceTimersByTime(delay - 1)
      expect(FakeWS.all.length).toBe(n)
      vi.advanceTimersByTime(1)
      expect(FakeWS.all.length).toBe(n + 1)
    }
    expect(statuses).toContain('reconnecting')
    // A successful open resets the backoff.
    lastWs().open()
    expect(statuses.at(-1)).toBe('open')
    const n = FakeWS.all.length
    lastWs().close()
    vi.advanceTimersByTime(500)
    expect(FakeWS.all.length).toBe(n + 1)
    s.close()
  })

  it('no ping for 3 seconds closes and reconnects the socket', () => {
    const s = connect('ws://x/api/ws', { onMessage: () => {} })
    lastWs().open()
    vi.advanceTimersByTime(2900)
    lastWs().receive({ type: 'ping' })
    vi.advanceTimersByTime(2900)
    expect(FakeWS.all.length).toBe(1)
    vi.advanceTimersByTime(100)
    expect(FakeWS.all[0]!.readyState).toBe(FakeWS.CLOSED)
    vi.advanceTimersByTime(500)
    expect(FakeWS.all.length).toBe(2)
    s.close()
  })

  it('messages sent while connecting are delivered once the socket opens', () => {
    const s = connect('ws://x/api/ws', { onMessage: () => {} })
    s.send({ type: 'subscribe', workspaceId: 'w', after: -1 })
    expect(lastWs().sent).toEqual([])
    lastWs().open()
    expect(lastWs().sent).toEqual([{ type: 'subscribe', workspaceId: 'w', after: -1 }])
    s.send({ type: 'pong' })
    expect(lastWs().sent).toHaveLength(2)
    s.close()
  })
})
