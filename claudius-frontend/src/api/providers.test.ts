import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  listModels,
  fetchModels,
} from './providers'

// M10: provider CRUD + model list over /api/providers. Plain fetch, like workspaces.ts.
function mockFetch(body: unknown, status = 200) {
  const text = typeof body === 'string' ? body : JSON.stringify(body)
  const fetchMock = vi.fn(async () => new Response(status === 204 ? null : text, { status }))
  global.fetch = fetchMock
  return fetchMock
}

const p = {
  id: 'p1',
  name: 'local',
  type: 'OLLAMA',
  baseUrl: '',
  apiKey: '',
  defaultModel: 'qwen3',
  models: [],
  createdAt: 0,
}

describe('providers api', () => {
  afterEach(() => delete (global as any).fetch)

  it('listProviders calls the provider endpoint', async () => {
    const fetchMock = mockFetch([p])
    const out = await listProviders()
    expect(fetchMock).toHaveBeenCalledWith('/api/providers')
    expect(out[0]?.name).toBe('local')
  })

  it('createProvider calls the provider endpoint', async () => {
    const fetchMock = mockFetch(p)
    const out = await createProvider({ name: 'local', type: 'OLLAMA' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/providers',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ name: 'local', type: 'OLLAMA' }),
      }),
    )
    expect(out.id).toBe('p1')
  })

  it('updateProvider calls the provider endpoint', async () => {
    const fetchMock = mockFetch(p)
    await updateProvider('p1', { name: 'x', type: 'OPENAI' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/providers/p1',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ name: 'x', type: 'OPENAI' }),
      }),
    )
  })

  it('deleteProvider calls the provider endpoint', async () => {
    const fetchMock = mockFetch(null, 204)
    await deleteProvider('p1')
    expect(fetchMock).toHaveBeenCalledWith('/api/providers/p1', { method: 'DELETE' })
  })

  it('provider errors throw with the status and the backend message', async () => {
    mockFetch('name is required', 400)
    await expect(createProvider({ name: '', type: 'OLLAMA' })).rejects.toThrow(
      'create provider failed: 400: name is required',
    )
    mockFetch('nope', 404)
    await expect(deleteProvider('x')).rejects.toThrow('delete provider failed: 404: nope')
  })

  it("listModels returns the provider's model ids and throws on 502 with the message", async () => {
    const fetchMock = mockFetch(['a', 'b'])
    expect(await listModels('p1')).toEqual(['a', 'b'])
    expect(fetchMock).toHaveBeenCalledWith('/api/providers/p1/models')
    mockFetch('provider unreachable', 502)
    await expect(listModels('p1')).rejects.toThrow('provider unreachable')
  })

  it('fetchModels posts the form values and returns the models with limits', async () => {
    const m = [{ id: 'qwen3', contextWindow: 32768, maxOutputTokens: null }]
    const fetchMock = mockFetch(m)
    const q = { type: 'OLLAMA' as const, baseUrl: 'http://h', apiKey: 'k' }
    expect(await fetchModels(q)).toEqual(m)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/providers/models',
      expect.objectContaining({ method: 'POST', body: JSON.stringify(q) }),
    )
    mockFetch('unreachable', 502)
    await expect(fetchModels(q)).rejects.toThrow('fetch models failed: 502: unreachable')
  })
})
