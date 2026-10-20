// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, act, cleanup, screen, within } from '@testing-library/react'
import {
  createProvider,
  deleteProvider,
  listProviders,
  updateProvider,
  fetchModels,
  type Provider,
} from '../api/providers'
import ProvidersPanel from './ProvidersPanel'

vi.mock('../api/providers', () => ({
  listProviders: vi.fn(),
  createProvider: vi.fn(),
  updateProvider: vi.fn(),
  deleteProvider: vi.fn(),
  fetchModels: vi.fn(),
}))

const p: Provider = {
  id: 'p1',
  name: 'cloud',
  type: 'OPENAI',
  baseUrl: 'https://api.example',
  apiKey: '…a1b2',
  defaultModel: 'gpt-x',
  models: [
    { id: 'gpt-x', contextWindow: 128000, maxOutputTokens: 4096 },
    { id: 'gpt-y', contextWindow: null, maxOutputTokens: null },
  ],
  createdAt: 0,
}
const flush = () =>
  act(async () => {
    await new Promise(r => setTimeout(r, 0))
  })
const type = (label: string, value: string) => {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}
const input = (label: string) => screen.getByLabelText(label) as HTMLInputElement
const openAdd = () => fireEvent.click(screen.getByRole('button', { name: 'Add provider' }))
const form = () => document.querySelector('.provider-form')
const row = () => within(document.querySelector('.providers__row')!)

describe('ProvidersPanel', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('the settings panel lists providers with masked keys', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    expect(row().getByText('cloud')).toBeTruthy()
    expect(row().getByText(/OPENAI/)).toBeTruthy()
    expect(row().getByText(/https:\/\/api\.example/)).toBeTruthy()
    expect(row().getByText(/…a1b2/)).toBeTruthy()
    expect(row().getByText(/gpt-x/)).toBeTruthy()
    expect(row().getByText(/2 models/)).toBeTruthy()
  })

  it('adding a provider posts the form and reloads the list', async () => {
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(createProvider).mockResolvedValue(p)
    const onSaved = vi.fn()
    render(<ProvidersPanel onClose={() => {}} onSaved={onSaved} />)
    await flush()
    openAdd()
    type('Name', 'cloud')
    type('Type', 'OPENAI')
    type('Base URL', 'https://api.example')
    type('API key', 'sk-1')
    type('Default model', 'gpt-x')
    vi.mocked(listProviders).mockResolvedValue([p])
    fireEvent.click(screen.getByText('Save'))
    await flush()
    expect(createProvider).toHaveBeenCalledWith({
      name: 'cloud',
      type: 'OPENAI',
      baseUrl: 'https://api.example',
      apiKey: 'sk-1',
      defaultModel: 'gpt-x',
      models: [],
    })
    expect(listProviders).toHaveBeenCalledTimes(2)
    expect(row().getByText('cloud')).toBeTruthy()
    expect(onSaved).toHaveBeenCalled()
  })

  it('editing a provider without typing a key keeps the stored key', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    vi.mocked(updateProvider).mockResolvedValue(p)
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    fireEvent.click(row().getByText('Edit'))
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('cloud')
    type('Default model', 'gpt-y')
    fireEvent.click(screen.getByText('Save'))
    await flush()
    const body = vi.mocked(updateProvider).mock.calls[0]![1]
    expect(vi.mocked(updateProvider).mock.calls[0]![0]).toBe('p1')
    expect(body.defaultModel).toBe('gpt-y')
    expect('apiKey' in body).toBe(false)
  })

  it('clear key sends an empty key on edit', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    vi.mocked(updateProvider).mockResolvedValue(p)
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    fireEvent.click(row().getByText('Edit'))
    fireEvent.click(screen.getByLabelText('Clear key'))
    fireEvent.click(screen.getByText('Save'))
    await flush()
    expect(vi.mocked(updateProvider).mock.calls[0]![1].apiKey).toBe('')
  })

  it('deleting a provider asks for confirmation and reloads the list', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    vi.mocked(deleteProvider).mockResolvedValue()
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    fireEvent.click(row().getByText('Delete'))
    expect(deleteProvider).not.toHaveBeenCalled()
    vi.mocked(listProviders).mockResolvedValue([])
    fireEvent.click(screen.getByText('Confirm delete'))
    await flush()
    expect(deleteProvider).toHaveBeenCalledWith('p1')
    expect(listProviders).toHaveBeenCalledTimes(2)
    expect(document.querySelector('.providers__row')).toBeNull()
  })

  it('a backend validation error shows in the panel', async () => {
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(createProvider).mockRejectedValue(
      new Error('create provider failed: 400: name is required'),
    )
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    openAdd()
    fireEvent.click(screen.getByText('Save'))
    await flush()
    expect(screen.getByRole('alert').textContent).toContain('name is required')
  })

  it('Escape and the backdrop close the panel', async () => {
    vi.mocked(listProviders).mockResolvedValue([])
    const onClose = vi.fn()
    render(<ProvidersPanel onClose={onClose} />)
    await flush()
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.click(document.querySelector('.providers')!)
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('fetching models fills the models table with the reported limits', async () => {
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(fetchModels).mockResolvedValue([
      { id: 'qwen3', contextWindow: 32768, maxOutputTokens: 8192 },
      { id: 'llama', contextWindow: null, maxOutputTokens: null },
    ])
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    openAdd()
    type('Type', 'OLLAMA')
    type('Base URL', 'http://h')
    type('API key', 'k')
    fireEvent.click(screen.getByText('Fetch models'))
    await flush()
    expect(fetchModels).toHaveBeenCalledWith({ type: 'OLLAMA', baseUrl: 'http://h', apiKey: 'k' })
    expect(input('Context window for qwen3').value).toBe('32768')
    expect(input('Max output tokens for qwen3').value).toBe('8192')
    expect(input('Context window for llama').value).toBe('')
  })

  it('editing a provider fetches with its id instead of a key', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    vi.mocked(fetchModels).mockResolvedValue([])
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    fireEvent.click(row().getByText('Edit'))
    // Editing starts with the stored models.
    expect(input('Context window for gpt-x').value).toBe('128000')
    fireEvent.click(screen.getByText('Fetch models'))
    await flush()
    expect(fetchModels).toHaveBeenCalledWith({
      type: 'OPENAI',
      baseUrl: 'https://api.example',
      providerId: 'p1',
    })
  })

  it('limits can be edited manually and are saved per model', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    vi.mocked(updateProvider).mockResolvedValue(p)
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    fireEvent.click(row().getByText('Edit'))
    type('Context window for gpt-y', '16000')
    type('Max output tokens for gpt-x', '')
    fireEvent.click(screen.getByText('Save'))
    await flush()
    expect(vi.mocked(updateProvider).mock.calls[0]![1].models).toEqual([
      { id: 'gpt-x', contextWindow: 128000, maxOutputTokens: null },
      { id: 'gpt-y', contextWindow: 16000, maxOutputTokens: null },
    ])
  })

  it('a re-fetch keeps the edited limits of existing models', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    vi.mocked(fetchModels).mockResolvedValue([
      { id: 'gpt-x', contextWindow: null, maxOutputTokens: null },
      { id: 'gpt-z', contextWindow: null, maxOutputTokens: null },
    ])
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    fireEvent.click(row().getByText('Edit'))
    type('Context window for gpt-y', '999')
    fireEvent.click(screen.getByText('Fetch models'))
    await flush()
    expect(input('Context window for gpt-x').value).toBe('128000')
    expect(input('Context window for gpt-y').value).toBe('999')
    expect(input('Context window for gpt-z').value).toBe('')
  })

  it('a model can be added manually and removed', async () => {
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(createProvider).mockResolvedValue(p)
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    openAdd()
    type('Name', 'n')
    type('New model id', 'm1')
    fireEvent.click(screen.getByText('Add model'))
    type('New model id', 'm2')
    fireEvent.click(screen.getByText('Add model'))
    fireEvent.click(screen.getByLabelText('Remove m1'))
    fireEvent.click(screen.getByText('Save'))
    await flush()
    expect(vi.mocked(createProvider).mock.calls[0]![0].models).toEqual([
      { id: 'm2', contextWindow: null, maxOutputTokens: null },
    ])
  })

  it('a failed fetch shows the error in the panel', async () => {
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(fetchModels).mockRejectedValue(new Error('fetch models failed: 502: unreachable'))
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    openAdd()
    fireEvent.click(screen.getByText('Fetch models'))
    await flush()
    expect(screen.getByRole('alert').textContent).toContain('unreachable')
  })

  it('the default radio sets the default model', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    vi.mocked(updateProvider).mockResolvedValue(p)
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    fireEvent.click(row().getByText('Edit'))
    expect(input('Default gpt-x').checked).toBe(true)
    fireEvent.click(screen.getByLabelText('Default gpt-y'))
    expect((screen.getByLabelText('Default model') as HTMLInputElement).value).toBe('gpt-y')
    fireEvent.click(screen.getByText('Save'))
    await flush()
    expect(vi.mocked(updateProvider).mock.calls[0]![1].defaultModel).toBe('gpt-y')
  })

  it('the add provider form opens in its own dialog', async () => {
    vi.mocked(listProviders).mockResolvedValue([])
    vi.mocked(createProvider).mockRejectedValueOnce(new Error('400: name is required'))
    vi.mocked(createProvider).mockResolvedValue(p)
    const onClose = vi.fn()
    render(<ProvidersPanel onClose={onClose} />)
    await flush()
    expect(screen.queryByLabelText('Name')).toBeNull()
    openAdd()
    expect(form()?.getAttribute('role')).toBe('dialog')
    expect(within(form() as HTMLElement).getByLabelText('Name')).toBeTruthy()
    // Escape and the backdrop close only the form.
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(form()).toBeNull()
    openAdd()
    fireEvent.click(form()!)
    expect(form()).toBeNull()
    expect(onClose).not.toHaveBeenCalled()
    // A validation error shows inside the form dialog.
    openAdd()
    fireEvent.click(screen.getByText('Save'))
    await flush()
    expect(within(form() as HTMLElement).getByRole('alert').textContent).toContain('required')
    type('Name', 'cloud')
    vi.mocked(listProviders).mockResolvedValue([p])
    fireEvent.click(screen.getByText('Save'))
    await flush()
    expect(form()).toBeNull()
    expect(row().getByText('cloud')).toBeTruthy()
  })

  it('editing a provider opens the same dialog filled in', async () => {
    vi.mocked(listProviders).mockResolvedValue([p])
    render(<ProvidersPanel onClose={() => {}} />)
    await flush()
    expect(form()).toBeNull()
    fireEvent.click(row().getByText('Edit'))
    expect(form()?.getAttribute('aria-label')).toBe('Edit provider')
    expect(input('Name').value).toBe('cloud')
    expect(input('Base URL').value).toBe('https://api.example')
    expect(input('Context window for gpt-x').value).toBe('128000')
  })
})
