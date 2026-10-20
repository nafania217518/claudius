import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  listWorkspaces,
  createWorkspace,
  renameWorkspace,
  deleteWorkspace,
  listMessages,
  compactWorkspace,
  setContextLimit,
  setPermissionMode,
} from './workspaces'

// A fetch stub returning a canned JSON body; records the call for assertions.
function mockJson(body: unknown, status = 200) {
  const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status }))
  global.fetch = fetchMock
  return fetchMock
}

const ws = { id: 'a1', name: 'proj', dir: 'proj', createdAt: 0 }

describe('workspaces api', () => {
  afterEach(() => delete (global as any).fetch)

  it('listWorkspaces() GETs the base and parses the array', async () => {
    const fetchMock = mockJson([ws, { ...ws, id: 'a2', name: 'beta' }])
    const out = await listWorkspaces()
    expect(fetchMock).toHaveBeenCalledWith('/api/workspaces')
    expect(out).toHaveLength(2)
    expect(out[1]?.name).toBe('beta')
  })

  it('createWorkspace() POSTs the name and parses the new workspace', async () => {
    const fetchMock = mockJson(ws)
    const out = await createWorkspace('proj')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workspaces',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'proj' }) }),
    )
    expect(out.id).toBe('a1')
  })

  it('renameWorkspace() PUTs the new name to /{id}', async () => {
    const fetchMock = mockJson({ ...ws, name: 'new' })
    const out = await renameWorkspace('a1', 'new')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workspaces/a1',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ name: 'new' }) }),
    )
    expect(out.name).toBe('new')
  })

  it('deleteWorkspace() DELETEs /{id}', async () => {
    const fetchMock = mockJson(null)
    await expect(deleteWorkspace('a1')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workspaces/a1',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('listMessages() GETs /{id}/messages and parses the transcript', async () => {
    const msgs = [
      { role: 'user', content: 'hi', createdAt: 1 },
      { role: 'assistant', content: 'yo', createdAt: 2 },
    ]
    const fetchMock = mockJson(msgs)
    const out = await listMessages('a1')
    expect(fetchMock).toHaveBeenCalledWith('/api/workspaces/a1/messages')
    // Each turn gets a stable `${id}:${i}` key, so the id is synthesised here.
    expect(out).toEqual(msgs.map((m, i) => ({ ...m, id: `a1:${i}` })))
  })

  it('listMessages passes tools arrays through', async () => {
    const call = { id: 'c1', name: 'CurrentTime', arguments: '', result: '12:34' }
    mockJson([
      { role: 'user', content: 'time?', createdAt: 1, tools: [] },
      { role: 'assistant', content: 'It is 12:34', createdAt: 2, tools: [call] },
    ])
    const out = await listMessages('a1')
    expect(out[0]?.tools).toEqual([])
    expect(out[1]?.tools).toEqual([call])
  })

  it('compactWorkspace posts to the compact endpoint and returns the summary and context size', async () => {
    const body = { summary: { role: 'summary', content: 'S' }, contextTokens: 7 }
    const fetchMock = mockJson(body)
    expect(await compactWorkspace('a1')).toEqual(body)
    expect(fetchMock).toHaveBeenCalledWith('/api/workspaces/a1/compact', { method: 'POST' })
    global.fetch = vi.fn(async () => new Response('nothing to compact', { status: 400 }))
    await expect(compactWorkspace('a1')).rejects.toThrow('compact failed: 400: nothing to compact')
  })

  it('throws with the status when a call responds non-ok', async () => {
    global.fetch = vi.fn(async () => new Response('nope', { status: 404 }))
    await expect(listWorkspaces()).rejects.toThrow(/list workspaces failed: 404/)
    global.fetch = vi.fn(async () => new Response('nope', { status: 409 }))
    await expect(createWorkspace('x')).rejects.toThrow(/create workspace failed: 409/)
    global.fetch = vi.fn(async () => new Response('nope', { status: 409 }))
    await expect(renameWorkspace('a1', 'x')).rejects.toThrow(/rename workspace failed: 409/)
    global.fetch = vi.fn(async () => new Response('nope', { status: 404 }))
    await expect(deleteWorkspace('a1')).rejects.toThrow(/delete workspace failed: 404/)
    global.fetch = vi.fn(async () => new Response('nope', { status: 404 }))
    await expect(listMessages('a1')).rejects.toThrow(/list messages failed: 404/)
  })

  it('a failed workspace call carries the backend message', async () => {
    global.fetch = vi.fn(async () => new Response('name taken', { status: 409 }))
    await expect(createWorkspace('x')).rejects.toThrow('create workspace failed: 409: name taken')
    global.fetch = vi.fn(async () => new Response('gone', { status: 404 }))
    await expect(deleteWorkspace('a1')).rejects.toThrow('delete workspace failed: 404: gone')
  })

  it('setContextLimit puts the limit and returns the workspace', async () => {
    const ws = { id: 'a1', name: 'a', dir: 'a', createdAt: 0, contextLimit: 70000 }
    const fetchMock = mockJson(ws)
    expect(await setContextLimit('a1', 70000)).toEqual(ws)
    expect(fetchMock).toHaveBeenCalledWith('/api/workspaces/a1/context-limit', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ contextLimit: 70000 }),
    })
    global.fetch = vi.fn(
      async () => new Response('context limit must be at least 65536 tokens', { status: 400 }),
    )
    await expect(setContextLimit('a1', 1)).rejects.toThrow(
      'set context limit failed: 400: context limit must be at least 65536 tokens',
    )
  })

  it('setPermissionMode PUTs the mode and returns the workspace', async () => {
    const ws = { id: 'a1', name: 'a', dir: 'a', createdAt: 0, permissionMode: 'BYPASS' }
    const fetchMock = mockJson(ws)
    expect(await setPermissionMode('a1', 'BYPASS')).toEqual(ws)
    expect(fetchMock).toHaveBeenCalledWith('/api/workspaces/a1/permission-mode', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ permissionMode: 'BYPASS' }),
    })
    global.fetch = vi.fn(async () => new Response('bad mode', { status: 400 }))
    await expect(setPermissionMode('a1', 'ASK')).rejects.toThrow(
      'set permission mode failed: 400: bad mode',
    )
  })
})
