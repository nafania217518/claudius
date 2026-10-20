// M2: workspace management over /api/workspaces. One workspace = one working dir +
// one chat thread (the workspace IS the session). Plain fetch, matching api/chat.ts.
//
// `deleteWorkspace` (not `remove`) reads as the delete op; the other verbs are
// prefixed with `Workspace`/`Messages` so a call site says what it acts on.
// M11: 'summary' is a compaction summary; older rows before it stay visible.
export type Role = 'user' | 'assistant' | 'summary'

// One transcript turn. `id` is a stable key (see listMessages / useChat) so a
// re-render can't reuse the wrong row; `createdAt` survives into the UI.
// `thinking` is the model's reasoning trace, shown collapsed above the answer
// and display-only — the backend never feeds it back into model context.
// `tools` is this turn's tool-call trace (M4): one card per invocation, its
// result filled in when the matching tool_result lands.
export type ToolInvocation = {
  id?: string
  name: string
  arguments: string
  result: string
  // M8: live command output, streamed by tool_output; display-only, never persisted.
  output?: string
  // M11: true when this tool round was folded into a mid-turn summary.
  compacted?: boolean
  // Thinking and answer lengths when the call was made; old rows have none.
  thinkingAt?: number
  answerAt?: number
}

export type ChatMessage = {
  id: string
  role: Role
  content: string
  thinking?: string
  tools?: ToolInvocation[]
  // M10: "<provider name>/<model>" for assistant turns; "" for older rows.
  model?: string
  // M12: display-only turn error, e.g. "interrupted by a server restart".
  error?: string
  createdAt: number
  // Summary rows: the estimated context size before and after the compaction.
  tokensBefore?: number | null
  tokensAfter?: number | null
  // A "Compacting context…" placeholder until the summary lands (client only).
  pending?: boolean
}

// M13: what the model may do without asking.
export type PermissionMode = 'ASK' | 'ACCEPT_EDITS' | 'BYPASS'

export type Workspace = {
  id: string
  name: string
  dir: string
  createdAt: number
  // M10: the provider + model this workspace last used (null = never chosen).
  lastProviderId?: string | null
  lastModel?: string | null
  // M11: the current context size in tokens.
  contextTokens?: number
  // M11: a per-workspace context window override (null = the model's window).
  contextLimit?: number | null
  // M13: the permission mode (missing = ASK).
  permissionMode?: PermissionMode
}

const BASE = '/api/workspaces'

// Throw `<what> failed: <status>: <body>` on a non-ok response.
async function check(res: Response, what: string): Promise<void> {
  if (!res.ok) throw new Error(`${what} failed: ${res.status}: ${await res.text()}`)
}

export async function listWorkspaces(): Promise<Workspace[]> {
  const res = await fetch(BASE)
  await check(res, 'list workspaces')
  const data: unknown = await res.json()
  if (!Array.isArray(data)) throw new Error('list workspaces: expected an array')
  return data as Workspace[]
}

export async function createWorkspace(name: string): Promise<Workspace> {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  await check(res, 'create workspace')
  return res.json()
}

export async function renameWorkspace(id: string, name: string): Promise<Workspace> {
  const res = await fetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  await check(res, 'rename workspace')
  return res.json()
}

export async function deleteWorkspace(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${id}`, { method: 'DELETE' })
  await check(res, 'delete workspace')
}

// A workspace's whole transcript, in order — the "load on switch" path. Each turn
// gets a stable `${id}:${i}` key so MessageList can key on it.
export async function listMessages(id: string): Promise<ChatMessage[]> {
  const res = await fetch(`${BASE}/${id}/messages`)
  await check(res, 'list messages')
  const data: unknown = await res.json()
  if (!Array.isArray(data)) throw new Error('list messages: expected an array')
  // `tools` arrives as a ToolInvocation[] (empty when none) and passes through.
  return (data as Omit<ChatMessage, 'id'>[]).map((m, i) => ({ ...m, id: `${id}:${i}` }))
}

// M11: compact the workspace context now.
export async function compactWorkspace(
  id: string,
): Promise<{ summary: Omit<ChatMessage, 'id'>; contextTokens: number }> {
  const res = await fetch(`${BASE}/${id}/compact`, { method: 'POST' })
  await check(res, 'compact')
  return res.json()
}

// M11: set (or clear with null) the workspace's context limit.
export async function setContextLimit(id: string, contextLimit: number | null): Promise<Workspace> {
  const res = await fetch(`${BASE}/${id}/context-limit`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contextLimit }),
  })
  await check(res, 'set context limit')
  return res.json()
}

// M13: set the workspace's permission mode.
export async function setPermissionMode(
  id: string,
  permissionMode: PermissionMode,
): Promise<Workspace> {
  const res = await fetch(`${BASE}/${id}/permission-mode`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ permissionMode }),
  })
  await check(res, 'set permission mode')
  return res.json()
}
