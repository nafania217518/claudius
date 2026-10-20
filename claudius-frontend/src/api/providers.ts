export type ProviderType = 'OLLAMA' | 'OPENAI' | 'ANTHROPIC'
export type Provider = {
  id: string
  name: string
  type: ProviderType
  baseUrl: string
  apiKey: string
  defaultModel: string
  models: ProviderModel[]
  createdAt: number
}
export type ProviderInput = {
  name: string
  type: ProviderType
  baseUrl?: string
  apiKey?: string | null
  defaultModel?: string
  // Omitted = keep the stored rows; a list replaces them all.
  models?: ProviderModel[]
}

// M10: one model row with its limits; null = not reported / not set.
export type ProviderModel = {
  id: string
  contextWindow: number | null
  maxOutputTokens: number | null
}

export type FetchModelsInput = {
  type: ProviderType
  baseUrl?: string
  apiKey?: string
  providerId?: string
}
const BASE = '/api/providers'

// Errors carry the backend body so a 400 validation message reaches the UI.
async function check(res: Response, what: string): Promise<Response> {
  if (!res.ok) throw new Error(`${what} failed: ${res.status}: ${await res.text()}`)
  return res
}

const send = (method: string, url: string, p: ProviderInput) =>
  fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(p) })

export async function listProviders(): Promise<Provider[]> {
  return (await check(await fetch(BASE), 'list providers')).json()
}

export async function createProvider(p: ProviderInput): Promise<Provider> {
  return (await check(await send('POST', BASE, p), 'create provider')).json()
}

export async function updateProvider(id: string, p: ProviderInput): Promise<Provider> {
  return (await check(await send('PUT', `${BASE}/${id}`, p), 'update provider')).json()
}

export async function deleteProvider(id: string): Promise<void> {
  await check(await fetch(`${BASE}/${id}`, { method: 'DELETE' }), 'delete provider')
}

export async function listModels(id: string): Promise<string[]> {
  return (await check(await fetch(`${BASE}/${id}/models`), 'list models')).json()
}

// Lists the models a provider reports for the form's current values (before save).
export async function fetchModels(q: FetchModelsInput): Promise<ProviderModel[]> {
  const res = await fetch(`${BASE}/models`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(q),
  })
  return (await check(res, 'fetch models')).json()
}
