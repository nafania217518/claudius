// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { render, fireEvent, act, cleanup, within, screen } from '@testing-library/react'
import type { StreamHandlers } from './api/chat'
import {
  createWorkspace,
  deleteWorkspace,
  listMessages,
  listWorkspaces,
  compactWorkspace,
  setContextLimit,
  setPermissionMode,
  type ChatMessage,
} from './api/workspaces'
import { listProviders } from './api/providers'
import App from './App'
import { FakeWS, allSent, installFakeWs, lastWs, onChat } from './test/fakeWs'

const chatFrames = () => allSent().filter(m => m.type === 'chat')

// M2: App mounts by listing workspaces and loading the active one's transcript, so
// the workspaces api must be faked too — otherwise listWorkspaces() hits jsdom's
// fetch and throws on the relative URL, leaving no active id and send() no-ops.
vi.mock('./api/workspaces', () => ({
  listWorkspaces: vi.fn(),
  listMessages: vi.fn(),
  createWorkspace: vi.fn(),
  renameWorkspace: vi.fn(),
  deleteWorkspace: vi.fn(),
  compactWorkspace: vi.fn(),
  setContextLimit: vi.fn(),
  setPermissionMode: vi.fn(),
}))
// M10: providers feed the model picker; the default is no providers.
vi.mock('./api/providers', () => ({
  listProviders: vi.fn(async () => []),
  listModels: vi.fn(async () => []),
}))
const prov = (id: string, name: string, defaultModel: string) => ({
  id,
  name,
  type: 'OLLAMA' as const,
  baseUrl: '',
  apiKey: '',
  defaultModel,
  models: [],
  createdAt: 0,
})

const emptyWorkspace = { id: 's1', name: 's1', dir: 's1', createdAt: 0 }
const msg = (role: 'user' | 'assistant', content: string, createdAt = 0): ChatMessage => ({
  id: `${role}:${createdAt}`,
  role,
  content,
  createdAt,
})

// Drains the mount chain: listWorkspaces() → setActiveId → the transcript effect →
// listMessages() → setMessages. A single microtask hop isn't enough, so wait a
// macrotask to settle both hops before asserting.
const flush = () =>
  act(async () => {
    await new Promise(r => setTimeout(r, 0))
  })

// The row's Delete/Rename open an in-app Dialog whose confirm button shares its
// label with the row's button, so scope the query to the `.dialog` overlay.
const dialog = () => within(document.querySelector('.dialog')!)

describe('App', () => {
  // No setup file in this repo, so RTL auto-cleanup is off; tear down between
  // tests or the shared document accumulates duplicate 'idle' spans.
  // M12: chat runs over a fake WebSocket; each test plays the server.
  beforeEach(() => installFakeWs())
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('the status shows reconnecting while the socket is down', async () => {
    installFakeWs()
    FakeWS.autoOpen = false
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    act(() => lastWs().open())
    expect(screen.queryByText('reconnecting…')).toBeNull()
    act(() => lastWs().close())
    expect(screen.getByText('reconnecting…')).toBeTruthy()
  })

  it('shows the empty state with no active workspace initially', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([])
    const { getByText } = render(<App />)
    await flush()
    expect(getByText('claudius')).toBeTruthy()
    expect(getByText('Create a new or select an existing workspace to continue.')).toBeTruthy()
  })

  it('sends a message and renders the streamed assistant reply', async () => {
    // Deltas arrive before done; onDone flushes the coalesced buffer
    // synchronously, so the merged content is settled once send's promise runs.
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat((_m, handlers) => {
      handlers.onToken('Hi ')
      handlers.onToken('there')
      handlers.onDone()
      return Promise.resolve()
    })

    const { getByText } = render(<App />)
    await flush()
    const input = document.querySelector('textarea')!
    act(() => {
      fireEvent.change(input, { target: { value: 'hello' } })
      fireEvent.keyDown(input, { key: 'Enter' })
    })
    await flush()

    expect(getByText('Hi there')).toBeTruthy()
    expect(getByText('idle')).toBeTruthy()
  })

  it('shows the streaming status while a reply is in flight', async () => {
    // onToken without onDone leaves streaming true, so the status stays 'streaming…'.
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat((_m, handlers) => {
      handlers.onToken('partial')
      return new Promise<never>(() => {})
    })

    const { getByText } = render(<App />)
    await flush()
    const input = document.querySelector('textarea')!
    act(() => {
      fireEvent.change(input, { target: { value: 'hi' } })
      fireEvent.keyDown(input, { key: 'Enter' })
    })
    await flush()

    expect(getByText('streaming…')).toBeTruthy()
  })

  it('shows Stop while streaming', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat(() => {})
    render(<App />)
    await flush()
    expect(screen.queryByRole('button', { name: 'Stop' })).toBeNull()
    const input = document.querySelector('textarea')!
    act(() => {
      fireEvent.change(input, { target: { value: 'hi' } })
      fireEvent.keyDown(input, { key: 'Enter' })
    })
    await flush()
    expect(screen.getByRole('button', { name: 'Stop' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Send/ })).toBeNull()
  })

  it('loads a workspace transcript when switching workspaces', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([
      { id: 'a', name: 'Alpha', dir: 'a', createdAt: 0 },
      { id: 'b', name: 'Beta', dir: 'b', createdAt: 0 },
    ])
    vi.mocked(listMessages).mockImplementation(async id =>
      id === 'b' ? [msg('user', 'from beta', 1)] : [],
    )

    const { getByText } = render(<App />)
    await flush()
    fireEvent.click(getByText('Beta'))
    await flush()

    expect(getByText('from beta')).toBeTruthy()
  })

  // Deleting the selected workspace refreshes the list to empty, so refresh's
  // "keep the current selection if it still exists, else first" must fall back
  // to no active workspace (the ? on ws[0]?.id over an empty list).
  it('falls back to no active workspace after the selected one is deleted', async () => {
    vi.mocked(listWorkspaces)
      .mockResolvedValueOnce([{ id: 'a', name: 'Alpha', dir: 'a', createdAt: 0 }])
      .mockResolvedValueOnce([])
    vi.mocked(listMessages).mockResolvedValue([])
    vi.mocked(deleteWorkspace).mockResolvedValue(undefined)

    const { getByText } = render(<App />)
    await flush()
    // The row's Delete opens the confirm dialog; confirm inside it to remove.
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    fireEvent.click(dialog().getByRole('button', { name: 'Delete' }))
    await flush()

    expect(vi.mocked(deleteWorkspace)).toHaveBeenCalledWith('a')
    expect(getByText('Create a new or select an existing workspace to continue.')).toBeTruthy()
  })

  // Creating a workspace selects it: the new thread becomes the active one so a
  // send after create lands in it, not the previously-active workspace. The
  // refreshed list still contains 'a', but create pre-selects 'c', so c's
  // transcript shows — proving the new selection won over the old.
  it('selects the newly created workspace on create', async () => {
    const a = { id: 'a', name: 'Alpha', dir: 'a', createdAt: 0 }
    const c = { id: 'c', name: 'beta', dir: 'c', createdAt: 0 }
    vi.mocked(listWorkspaces).mockResolvedValueOnce([a]).mockResolvedValueOnce([c, a])
    vi.mocked(listMessages).mockImplementation(async id =>
      id === 'a' ? [msg('user', 'from alpha', 1)] : [msg('user', 'from beta', 1)],
    )
    vi.mocked(createWorkspace).mockResolvedValue(c)

    const { getByText } = render(<App />)
    await flush()
    // Fill the create dialog's field and confirm.
    fireEvent.click(getByText('+ New'))
    fireEvent.change(dialog().getByRole('textbox'), { target: { value: 'beta' } })
    fireEvent.click(dialog().getByRole('button', { name: 'Create' }))
    await flush()

    expect(getByText('from beta')).toBeTruthy()
  })

  it('surfaces a failed workspace load in the error region', async () => {
    vi.mocked(listWorkspaces).mockRejectedValue(new Error('offline'))
    const { getByText } = render(<App />)
    await flush()
    expect(getByText('offline')).toBeTruthy()
  })

  // M7: a permission event puts Approve and Reject on the tool card.
  const askPermission = async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat((_m, handlers) => {
      const call = { id: 'c1', name: 'Bash', arguments: '{"cmd":"ls"}', result: '' }
      handlers.onToolCall(call)
      handlers.onPermission(call)
      return new Promise<void>(() => {})
    })
    render(<App />)
    await flush()
    const input = document.querySelector('textarea')!
    act(() => {
      fireEvent.change(input, { target: { value: 'hello' } })
      fireEvent.keyDown(input, { key: 'Enter' })
    })
    await flush()
    // The tool card lands with the next animation frame.
    await act(async () => {
      await new Promise(r => requestAnimationFrame(r))
    })
  }

  it('the approval buttons live on the tool card', async () => {
    await askPermission()
    expect(document.querySelector('.dialog')).toBeNull()
    const approve = screen.getByRole('button', { name: 'Approve' })
    expect(approve.closest('.tool__approval')).not.toBeNull()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeTruthy()
  })

  it('clicking a tool chip opens the Tools tab with that call in the inspector', async () => {
    await askPermission()
    fireEvent.click(document.querySelector('button.chip')!)
    expect(screen.getByRole('tab', { name: 'Tools' }).getAttribute('aria-selected')).toBe('true')
    expect(document.querySelector('.inspector')!.textContent).toContain('Arguments')
  })

  it('a new run clears the inspector selection so the latest call shows', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([
      { ...msg('assistant', 'ok'), tools: [{ name: 'glob', arguments: '{}', result: 'old' }] },
    ])
    onChat((_m, handlers) => {
      handlers.onToolCall({ id: 'c2', name: 'bash', arguments: '{"command":"ls"}', result: '' })
      handlers.onToolOutput({ id: 'c2', name: 'bash', arguments: '', result: 'live!' })
      return new Promise<void>(() => {})
    })
    render(<App />)
    await flush()
    fireEvent.click(document.querySelector('button.chip')!)
    expect(document.querySelector('.inspector')!.textContent).toContain('old')
    const input = document.querySelector('textarea')!
    act(() => {
      fireEvent.change(input, { target: { value: 'hello' } })
      fireEvent.keyDown(input, { key: 'Enter' })
    })
    await flush()
    await act(async () => {
      await new Promise(r => requestAnimationFrame(r))
    })
    expect(document.querySelector('.inspector')!.textContent).toContain('live!')
    expect(document.querySelector('.inspector')!.textContent).not.toContain('old')
  })

  it('clicking an agent chip opens the Agents tab', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([
      {
        ...msg('assistant', 'ok'),
        tools: [{ name: 'agent', arguments: '{"description":"scan","prompt":"P1"}', result: 'R1' }],
      },
    ])
    render(<App />)
    await flush()
    fireEvent.click(screen.getByText('agent · scan · done'))
    expect(screen.getByRole('tab', { name: 'Agents' }).getAttribute('aria-selected')).toBe('true')
    expect(document.querySelector('.inspector')!.textContent).toContain('P1')
  })

  it('Approve answers true', async () => {
    await askPermission()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    })
    expect(allSent()).toContainEqual({
      type: 'answer',
      runId: expect.any(String),
      id: 'c1',
      approved: true,
    })
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull()
  })

  it('Reject answers false', async () => {
    await askPermission()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    })
    expect(allSent()).toContainEqual({
      type: 'answer',
      runId: expect.any(String),
      id: 'c1',
      approved: false,
    })
  })

  it("the selection starts from the workspace's last provider and model", async () => {
    vi.mocked(listProviders).mockResolvedValue([
      prov('p1', 'local', 'qwen3'),
      prov('p2', 'cloud', 'gpt-x'),
    ])
    vi.mocked(listWorkspaces).mockResolvedValue([
      { ...emptyWorkspace, lastProviderId: 'p2', lastModel: 'gpt-old' },
      { ...emptyWorkspace, id: 's2', name: 's2' },
    ])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('p2')
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('gpt-old')
    // A workspace with no last choice falls back to the first provider + its default.
    fireEvent.click(screen.getByText('s2'))
    await flush()
    expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('p1')
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('qwen3')
  })

  it('sending a message sends the selected provider and model', async () => {
    vi.mocked(listProviders).mockResolvedValue([
      prov('p1', 'local', 'qwen3'),
      prov('p2', 'cloud', 'gpt-x'),
    ])
    vi.mocked(listWorkspaces).mockResolvedValue([
      emptyWorkspace,
      { ...emptyWorkspace, id: 's2', name: 's2' },
    ])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat((_m, handlers) => {
      handlers.onDone()
      return Promise.resolve()
    })
    render(<App />)
    await flush()
    fireEvent.change(screen.getByLabelText('Provider'), { target: { value: 'p2' } })
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'gpt-y' } })
    const input = document.querySelector('textarea')!
    act(() => {
      fireEvent.change(input, { target: { value: 'hello' } })
      fireEvent.keyDown(input, { key: 'Enter' })
    })
    await flush()
    expect(chatFrames()[0]).toMatchObject({ providerId: 'p2', model: 'gpt-y' })
    // The workspace remembers the choice locally: switching away and back keeps it.
    fireEvent.click(screen.getByText('s2'))
    await flush()
    fireEvent.click(screen.getByText('s1'))
    await flush()
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('gpt-y')
  })

  it('the gear button on the right of the header opens the providers panel', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    const gear = within(document.querySelector('.app__header')!).getByRole('button', {
      name: 'Settings',
    })
    expect(gear.parentElement?.className).toContain('ml-auto')
    expect(document.querySelector('.sidebar__settings')).toBeNull()
    fireEvent.click(gear)
    await flush()
    expect(screen.getByRole('dialog', { name: 'Providers' })).toBeTruthy()
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('a workspace whose last provider was deleted starts from the first provider and its default model', async () => {
    vi.mocked(listProviders).mockResolvedValue([prov('p1', 'local', 'qwen3')])
    vi.mocked(listWorkspaces).mockResolvedValue([
      { ...emptyWorkspace, lastProviderId: 'gone', lastModel: 'old' },
    ])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('p1')
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('qwen3')
  })

  it('a streaming assistant turn shows the selected model', async () => {
    vi.mocked(listProviders).mockResolvedValue([prov('p1', 'local', 'qwen3')])
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat((_m, handlers) => {
      handlers.onToken('Hi')
      return new Promise(() => {})
    })
    render(<App />)
    await flush()
    const input = document.querySelector('textarea')!
    act(() => {
      fireEvent.change(input, { target: { value: 'hello' } })
      fireEvent.keyDown(input, { key: 'Enter' })
    })
    await flush()
    expect(screen.getByText('local/qwen3')).toBeTruthy()
  })

  // M11: context tracking + compaction.
  const typeAndSend = (text: string) => {
    const input = document.querySelector('textarea')!
    act(() => {
      fireEvent.change(input, { target: { value: text } })
      fireEvent.keyDown(input, { key: 'Enter' })
    })
  }
  const askCompact = async () => {
    vi.mocked(listProviders).mockResolvedValue([
      {
        ...prov('p1', 'local', 'big'),
        models: [
          { id: 'big', contextWindow: 200000, maxOutputTokens: null },
          { id: 'small', contextWindow: 1000, maxOutputTokens: null },
        ],
      },
    ])
    vi.mocked(listWorkspaces).mockResolvedValue([{ ...emptyWorkspace, contextTokens: 5000 }])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat((_m, handlers) => {
      handlers.onDone()
      return Promise.resolve()
    })
    render(<App />)
    await flush()
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'small' } })
    typeAndSend('hello')
    await flush()
  }

  it('switching to a model with a smaller window asks to compact first', async () => {
    await askCompact()
    expect(
      dialog().getByText("The context is bigger than the model's window. Compact it first?"),
    ).toBeTruthy()
    expect(chatFrames()).toHaveLength(0)
  })

  it('answering yes sends the message with compact', async () => {
    await askCompact()
    fireEvent.click(dialog().getByRole('button', { name: 'Yes' }))
    await flush()
    const call = chatFrames()[0]!
    expect(call.message).toBe('hello')
    expect(call.compact).toBe(true)
    expect(document.querySelector('.dialog')).toBeNull()
  })

  it('answering yes clears the composer', async () => {
    await askCompact()
    expect(document.querySelector('textarea')!.value).toBe('hello')
    fireEvent.click(dialog().getByRole('button', { name: 'Yes' }))
    await flush()
    expect(document.querySelector('textarea')!.value).toBe('')
  })

  it('answering no sends nothing and keeps the text', async () => {
    await askCompact()
    fireEvent.click(dialog().getByRole('button', { name: 'No' }))
    await flush()
    expect(chatFrames()).toHaveLength(0)
    expect(document.querySelector('.dialog')).toBeNull()
    expect(document.querySelector('textarea')!.value).toBe('hello')
  })

  it('the compact button compacts the workspace and shows the summary', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([{ ...emptyWorkspace, contextTokens: 500 }])
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(listMessages).mockResolvedValue([msg('user', 'old', 1)])
    vi.mocked(compactWorkspace).mockResolvedValue({
      summary: { role: 'summary', content: 'the gist', createdAt: 2 },
      contextTokens: 50,
    })
    render(<App />)
    await flush()
    expect(screen.getByText('Context: 500 tokens')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Compact' }))
    await flush()
    expect(compactWorkspace).toHaveBeenCalledWith('s1')
    expect(screen.getByText('Context compacted')).toBeTruthy()
    expect(screen.getByText('the gist')).toBeTruthy()
    expect(screen.getByText('Context: 50 tokens')).toBeTruthy()
    // Errors go to the error display.
    vi.mocked(compactWorkspace).mockRejectedValue(new Error('compact failed: 409: busy'))
    fireEvent.click(screen.getByRole('button', { name: 'Compact' }))
    await flush()
    expect(screen.getByText('compact failed: 409: busy')).toBeTruthy()
  })

  it('a compacted event inserts the summary between the user message and the reply', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([msg('user', 'old', 1)])
    const summary = { role: 'summary' as const, content: 'the gist', createdAt: 2 }
    onChat((m, handlers) => {
      if (m.message === 'mid')
        handlers.onToolCall?.({ id: 't1', name: 'read', arguments: '{}', result: '' })
      handlers.onCompacted?.(summary)
      handlers.onToken('Hi')
      handlers.onDone()
      return Promise.resolve()
    })
    render(<App />)
    await flush()
    const roles = () => [...document.querySelectorAll('.msg')].map(e => e.className.split(' ')[1])
    typeAndSend('hello')
    await flush()
    expect(roles()).toEqual(['msg--user', 'msg--user', 'msg--summary', 'msg--assistant'])
    typeAndSend('mid')
    await flush()
    expect(roles().slice(4)).toEqual(['msg--user', 'msg--summary', 'msg--assistant'])
    expect(document.querySelectorAll('.msg--assistant')[1]!.textContent).toContain('read')
  })

  const compactSetup = async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([{ ...emptyWorkspace, contextTokens: 500 }])
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(listMessages).mockResolvedValue([msg('user', 'old', 1)])
    let settle: { ok: (v: unknown) => void; fail: (e: Error) => void } | null = null
    vi.mocked(compactWorkspace).mockImplementation(
      () => new Promise((ok, fail) => (settle = { ok, fail })) as never,
    )
    render(<App />)
    await flush()
    return () => settle!
  }
  const compactBtn = () => screen.getByRole('button', { name: 'Compact' }) as HTMLButtonElement
  const sendBtn = () => document.querySelector<HTMLButtonElement>('.composer button')!
  const chip = () => document.querySelector('.msg--compacting')
  const result = {
    summary: { role: 'summary' as const, content: 'the gist', createdAt: 2 },
    contextTokens: 50,
  }

  it('the compact button is disabled until the compaction finishes', async () => {
    const settle = await compactSetup()
    fireEvent.change(document.querySelector('textarea')!, { target: { value: 'hi' } })
    expect(sendBtn().disabled).toBe(false)
    fireEvent.click(compactBtn())
    await flush()
    expect(compactBtn().disabled).toBe(true)
    expect(sendBtn().disabled).toBe(true)
    await act(async () => settle().ok(result))
    await flush()
    expect(compactBtn().disabled).toBe(false)
    expect(sendBtn().disabled).toBe(false)
    fireEvent.click(compactBtn())
    await act(async () => settle().fail(new Error('compact failed: 409: busy')))
    await flush()
    expect(compactBtn().disabled).toBe(false)
  })

  it('while compacting the button reads Compacting and is disabled', async () => {
    const settle = await compactSetup()
    fireEvent.click(compactBtn())
    await flush()
    expect(sendBtn().textContent).toBe('Compacting…')
    expect(sendBtn().disabled).toBe(true)
    await act(async () => settle().ok(result))
    await flush()
    expect(sendBtn().textContent).toBe('Send')
  })

  it('a compacting chip shows at once and becomes the summary', async () => {
    const settle = await compactSetup()
    fireEvent.click(compactBtn())
    expect(chip()?.textContent).toBe('Compacting context…')
    await act(async () => settle().ok(result))
    await flush()
    expect(chip()).toBeNull()
    expect(screen.getByText('the gist')).toBeTruthy()
    fireEvent.click(compactBtn())
    expect(chip()).toBeTruthy()
    await act(async () => settle().fail(new Error('compact failed: 409: busy')))
    await flush()
    expect(chip()).toBeNull()
    expect(screen.getByText('compact failed: 409: busy')).toBeTruthy()
  })

  it('a compacting event shows the chip during a turn', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    let h: Required<StreamHandlers> | null = null
    const end = () => h!.onDone()
    onChat((_m, handlers) => {
      h = handlers
    })
    render(<App />)
    await flush()
    const roles = () => [...document.querySelectorAll('.msg')].map(e => e.className.split(' ')[1])
    typeAndSend('hello')
    await flush()
    act(() => h!.onCompacting?.())
    expect(roles()).toEqual(['msg--user', 'msg--summary', 'msg--assistant'])
    expect(chip()?.textContent).toBe('Compacting context…')
    act(() => h!.onCompacted?.({ role: 'summary', content: 'the gist', createdAt: 2 }))
    expect(chip()).toBeNull()
    expect(roles()).toEqual(['msg--user', 'msg--summary', 'msg--assistant'])
    expect(screen.getByText('the gist')).toBeTruthy()
    // A leftover chip goes away on error or at the end of the stream.
    act(() => h!.onCompacting?.())
    expect(chip()).toBeTruthy()
    act(() => h!.onError?.('boom'))
    expect(chip()).toBeNull()
    act(() => h!.onCompacting?.())
    await act(async () => end())
    await flush()
    expect(chip()).toBeNull()
    expect(roles()).toEqual(['msg--user', 'msg--summary', 'msg--assistant'])
  })

  // M11: the workspace context limit.
  const limitSetup = async (contextLimit: number | null = null, contextTokens = 0) => {
    vi.mocked(listProviders).mockResolvedValue([
      {
        ...prov('p1', 'local', 'big'),
        models: [{ id: 'big', contextWindow: 262144, maxOutputTokens: null }],
      },
    ])
    vi.mocked(listWorkspaces).mockResolvedValue([
      { ...emptyWorkspace, contextTokens, contextLimit },
    ])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat((_m, handlers) => {
      handlers.onDone()
      return Promise.resolve()
    })
    render(<App />)
    await flush()
  }
  const limitInput = () => screen.getByLabelText('Context limit') as HTMLInputElement

  it('the context limit can be set and cleared from the usage bar', async () => {
    await limitSetup()
    expect(limitInput().placeholder).toBe('model default')
    expect(screen.getByText('Context: 0 / 262k tokens (0%)')).toBeTruthy()
    vi.mocked(setContextLimit).mockResolvedValue({ ...emptyWorkspace, contextLimit: 128000 })
    fireEvent.change(limitInput(), { target: { value: '128000' } })
    fireEvent.keyDown(limitInput(), { key: 'Enter' })
    await flush()
    expect(setContextLimit).toHaveBeenCalledWith('s1', 128000)
    expect(screen.getByText('Context: 0 / 128k tokens (0%)')).toBeTruthy()
    vi.mocked(setContextLimit).mockResolvedValue({ ...emptyWorkspace, contextLimit: null })
    fireEvent.change(limitInput(), { target: { value: '' } })
    fireEvent.blur(limitInput())
    await flush()
    expect(setContextLimit).toHaveBeenLastCalledWith('s1', null)
    expect(screen.getByText('Context: 0 / 262k tokens (0%)')).toBeTruthy()
  })

  it('a context limit below 64k is refused with a message', async () => {
    await limitSetup()
    fireEvent.change(limitInput(), { target: { value: '1000' } })
    fireEvent.keyDown(limitInput(), { key: 'Enter' })
    await flush()
    expect(setContextLimit).not.toHaveBeenCalled()
    expect(screen.getByText('Context limit must be at least 65536 tokens.')).toBeTruthy()
    vi.mocked(setContextLimit).mockRejectedValue(
      new Error('set context limit failed: 400: context limit must be at least 65536 tokens'),
    )
    fireEvent.change(limitInput(), { target: { value: '70000' } })
    fireEvent.blur(limitInput())
    await flush()
    expect(
      screen.getByText(
        'set context limit failed: 400: context limit must be at least 65536 tokens',
      ),
    ).toBeTruthy()
  })

  it('the compact-first check uses the effective window', async () => {
    await limitSetup(70000, 100000)
    typeAndSend('hello')
    await flush()
    expect(document.querySelector('.dialog')).not.toBeNull()
    expect(chatFrames()).toHaveLength(0)
  })

  it('a usage event updates the context size', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([{ ...emptyWorkspace, contextTokens: 10 }])
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(listMessages).mockResolvedValue([])
    onChat((_m, handlers) => {
      handlers.onUsage?.(4321)
      handlers.onDone()
      return Promise.resolve()
    })
    render(<App />)
    await flush()
    typeAndSend('hello')
    await flush()
    expect(screen.getByText('Context: 4.3k tokens')).toBeTruthy()
  })

  it('the sidebar marks a workspace with a run in progress', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([
      { id: 'a', name: 'Alpha', dir: 'a', createdAt: 0 },
      { id: 'b', name: 'Beta', dir: 'b', createdAt: 0 },
    ])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    act(() => lastWs().receive({ type: 'runs', workspaceIds: ['b'] }))
    const dot = screen.getByLabelText('running')
    expect(dot.closest('li')!.textContent).toContain('Beta')
  })

  it('the transcript stays where the user scrolled during a stream', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    let h: Required<StreamHandlers> | undefined
    onChat((_m, handlers) => {
      h = handlers
    })
    render(<App />)
    await flush()
    const body = document.querySelector<HTMLElement>('.app__body')!
    const size = (scrollHeight: number, clientHeight: number) => {
      Object.defineProperty(body, 'scrollHeight', { configurable: true, value: scrollHeight })
      Object.defineProperty(body, 'clientHeight', { configurable: true, value: clientHeight })
    }
    // At the bottom: new content scrolls into view.
    size(1000, 200)
    body.scrollTop = 800
    typeAndSend('hello')
    await flush()
    size(1200, 200)
    act(() => {
      h!.onToken('a')
      h!.onDone()
    })
    await flush()
    expect(body.scrollTop).toBe(1200)
    // Scrolled up: new content leaves the view alone.
    body.scrollTop = 100
    fireEvent.scroll(body)
    typeAndSend('again')
    await flush()
    size(1500, 200)
    act(() => {
      h!.onToken('b')
      h!.onDone()
    })
    await flush()
    expect(body.scrollTop).toBe(100)
  })

  it('the status reads compacting while a compaction runs', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    vi.mocked(listWorkspaces).mockResolvedValue([{ ...emptyWorkspace, contextTokens: 500 }])
    vi.mocked(compactWorkspace).mockReturnValue(new Promise(() => {}))
    render(<App />)
    await flush()
    fireEvent.click(screen.getByRole('button', { name: 'Compact' }))
    await flush()
    expect(screen.getByText('compacting…')).toBeTruthy()
  })

  it('the layout stacks the sidebar on top on a narrow screen', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    const cls = (sel: string) => document.querySelector(sel)!.className.split(' ')
    expect(cls('.app')).toEqual(expect.arrayContaining(['flex-col', 'sm:flex-row']))
    expect(cls('.sidebar')).toEqual(
      expect.arrayContaining(['w-full', 'sm:w-(--w)', 'border-b', 'sm:border-b-0', 'sm:border-r']),
    )
    expect(cls('.sidebar__list')).toEqual(
      expect.arrayContaining(['flex-row', 'flex-wrap', 'sm:flex-col']),
    )
    expect(cls('.app__main')).toContain('min-w-0')
    // mx-auto alone shrinks the column to fit wide content; w-full keeps it at the screen width.
    expect(cls('.app__main')).toContain('w-full')
  })

  it('hiding the inspector removes its tabs and is remembered', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    expect(screen.getAllByRole('tab')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: 'Hide inspector' }))
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    expect(JSON.parse(localStorage.getItem('claudius.layout')!).right.open).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Show inspector' }))
    expect(screen.getAllByRole('tab')).toHaveLength(2)
  })

  it('a splitter sits between each panel and the chat', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    const labels = screen.getAllByRole('separator').map(e => e.getAttribute('aria-label'))
    expect(labels).toEqual(['Resize workspaces', 'Resize inspector'])
  })

  it('the chat body uses the small text size', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    expect(document.querySelector('.app__body')!.className).toContain('text-sm')
  })

  it('the permissions select shows the workspace mode and saves a change', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([
      { ...emptyWorkspace, permissionMode: 'ACCEPT_EDITS' },
    ])
    vi.mocked(listMessages).mockResolvedValue([])
    vi.mocked(setPermissionMode).mockResolvedValue({ ...emptyWorkspace, permissionMode: 'BYPASS' })
    render(<App />)
    await flush()
    const select = screen.getByLabelText('Permissions') as HTMLSelectElement
    expect(select.value).toBe('ACCEPT_EDITS')
    fireEvent.change(select, { target: { value: 'BYPASS' } })
    await flush()
    expect(setPermissionMode).toHaveBeenCalledWith('s1', 'BYPASS')
    expect((screen.getByLabelText('Permissions') as HTMLSelectElement).value).toBe('BYPASS')
  })

  it('bypass mode is marked in red', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([{ ...emptyWorkspace, permissionMode: 'BYPASS' }])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    expect(screen.getByLabelText('Permissions').className).toContain('text-red-600')
    cleanup()
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    render(<App />)
    await flush()
    expect(screen.getByLabelText('Permissions').className).not.toContain('text-red-600')
  })

  it('the permissions select is in the header before the gear', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([emptyWorkspace])
    vi.mocked(listMessages).mockResolvedValue([])
    render(<App />)
    await flush()
    const header = within(document.querySelector('.app__header')!)
    const select = header.getByLabelText('Permissions')
    const gear = header.getByRole('button', { name: 'Settings' })
    expect(select.compareDocumentPosition(gear) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
