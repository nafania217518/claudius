// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, act, cleanup, screen } from '@testing-library/react'
import { listModels, type Provider } from '../api/providers'
import ModelPicker from './ModelPicker'

vi.mock('../api/providers', () => ({ listModels: vi.fn() }))

const prov = (id: string, name: string, defaultModel: string): Provider => ({
  id,
  name,
  type: 'OLLAMA',
  baseUrl: '',
  apiKey: '',
  defaultModel,
  models: [],
  createdAt: 0,
})
const providers = [prov('p1', 'local', 'qwen3'), prov('p2', 'cloud', 'gpt-x')]
const flush = () =>
  act(async () => {
    await new Promise(r => setTimeout(r, 0))
  })
const options = () =>
  [...document.querySelectorAll('datalist option')].map(o => (o as HTMLOptionElement).value)

describe('ModelPicker', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('the picker shows the providers and the current model', async () => {
    vi.mocked(listModels).mockResolvedValue([])
    render(
      <ModelPicker
        providers={providers}
        value={{ providerId: 'p2', model: 'm9' }}
        onChange={() => {}}
      />,
    )
    await flush()
    const select = screen.getByLabelText('Provider') as HTMLSelectElement
    expect([...select.options].map(o => o.text)).toEqual(['local', 'cloud'])
    expect(select.value).toBe('p2')
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('m9')
  })

  it('changing the provider switches to its default model and loads its model list', async () => {
    vi.mocked(listModels).mockResolvedValue(['gpt-x', 'gpt-y'])
    const onChange = vi.fn()
    const { rerender } = render(
      <ModelPicker
        providers={providers}
        value={{ providerId: 'p1', model: 'qwen3' }}
        onChange={onChange}
      />,
    )
    await flush()
    fireEvent.change(screen.getByLabelText('Provider'), { target: { value: 'p2' } })
    expect(onChange).toHaveBeenCalledWith({ providerId: 'p2', model: 'gpt-x' })
    rerender(
      <ModelPicker
        providers={providers}
        value={{ providerId: 'p2', model: 'gpt-x' }}
        onChange={onChange}
      />,
    )
    await flush()
    expect(listModels).toHaveBeenLastCalledWith('p2')
    expect(options()).toEqual(['gpt-x', 'gpt-y'])
  })

  it('a failing model list still allows a typed model', async () => {
    vi.mocked(listModels).mockRejectedValue(new Error('provider unreachable'))
    const onChange = vi.fn()
    render(
      <ModelPicker
        providers={providers}
        value={{ providerId: 'p1', model: '' }}
        onChange={onChange}
      />,
    )
    await flush()
    expect(options()).toEqual([])
    expect(screen.queryByText(/unreachable/)).toBeNull()
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'custom' } })
    expect(onChange).toHaveBeenCalledWith({ providerId: 'p1', model: 'custom' })
  })

  it("the picker suggests the provider's stored models without a fetch", async () => {
    const stored = {
      ...prov('p3', 'stored', 'a'),
      models: [
        { id: 'a', contextWindow: null, maxOutputTokens: null },
        { id: 'b', contextWindow: 8192, maxOutputTokens: 1024 },
      ],
    }
    render(
      <ModelPicker
        providers={[stored]}
        value={{ providerId: 'p3', model: 'a' }}
        onChange={() => {}}
      />,
    )
    await flush()
    expect(listModels).not.toHaveBeenCalled()
    expect(options()).toEqual(['a', 'b'])
  })

  it('an unknown provider shows no suggestions and no fetch', async () => {
    render(
      <ModelPicker
        providers={providers}
        value={{ providerId: '', model: '' }}
        onChange={() => {}}
      />,
    )
    await flush()
    expect(listModels).not.toHaveBeenCalled()
    expect(options()).toEqual([])
  })
})
