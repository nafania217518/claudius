import { describe, it, expect, vi } from 'vitest'
import { dispatch, type StreamHandlers } from './chat'

// M12: socket `event` frames carry the payload string the SSE `data:` carried before.
const tool = { id: 'c1', name: 'Bash', arguments: '{}', result: 'r' }

describe('dispatch', () => {
  it('routes each event to its handler and parses JSON payloads', () => {
    const h = {
      onToken: vi.fn(),
      onReasoning: vi.fn(),
      onToolCall: vi.fn(),
      onToolResult: vi.fn(),
      onToolOutput: vi.fn(),
      onPermission: vi.fn(),
      onError: vi.fn(),
      onCompacting: vi.fn(),
      onCompacted: vi.fn(),
      onUsage: vi.fn(),
      onDone: vi.fn(),
    } satisfies StreamHandlers
    const json = JSON.stringify(tool)
    dispatch('token', ' there', h)
    dispatch('reasoning', 'hm', h)
    dispatch('tool_call', json, h)
    dispatch('tool_result', json, h)
    dispatch('tool_output', json, h)
    dispatch('permission', json, h)
    dispatch('error', 'boom', h)
    dispatch('compacting', '', h)
    dispatch('compacted', '{"role":"summary","content":"g","createdAt":1}', h)
    dispatch('usage', '{"contextTokens":42}', h)
    dispatch('done', '', h)
    dispatch('unknown', '', h)
    // A leading space is real token content.
    expect(h.onToken).toHaveBeenCalledWith(' there')
    expect(h.onReasoning).toHaveBeenCalledWith('hm')
    for (const f of [h.onToolCall, h.onToolResult, h.onToolOutput, h.onPermission])
      expect(f).toHaveBeenCalledWith(tool)
    expect(h.onError).toHaveBeenCalledWith('boom')
    expect(h.onCompacting).toHaveBeenCalled()
    expect(h.onCompacted).toHaveBeenCalledWith({ role: 'summary', content: 'g', createdAt: 1 })
    expect(h.onUsage).toHaveBeenCalledWith(42)
    expect(h.onDone).toHaveBeenCalledTimes(1)
  })

  it('ignores events whose optional handler is missing', () => {
    const h = { onToken: vi.fn(), onDone: vi.fn() }
    for (const e of ['reasoning', 'tool_call', 'permission', 'error', 'compacting', 'usage'])
      dispatch(e, '{"contextTokens":1}', h)
    expect(h.onToken).not.toHaveBeenCalled()
    expect(h.onDone).not.toHaveBeenCalled()
  })
})
