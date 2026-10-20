import { useCallback, useEffect, useState } from 'react'
import {
  createProvider,
  deleteProvider,
  listProviders,
  updateProvider,
  fetchModels,
  type ProviderModel,
  type FetchModelsInput,
  type Provider,
  type ProviderInput,
  type ProviderType,
} from '../api/providers'

// M10: provider settings modal. Same overlay/card tokens and Escape/backdrop
// close as Dialog.tsx. The card shows the list; the Add/Edit form opens in its
// own overlay on top, with its errors inside it.
const TYPES: ProviderType[] = ['OLLAMA', 'OPENAI', 'ANTHROPIC']
const DEFAULT_URL: Record<ProviderType, string> = {
  OLLAMA: 'http://localhost:11434',
  OPENAI: 'https://api.openai.com',
  ANTHROPIC: 'https://api.anthropic.com',
}
const EMPTY = {
  name: '',
  type: 'OLLAMA' as ProviderType,
  baseUrl: '',
  apiKey: '',
  defaultModel: '',
}
const field = 'w-full px-2 py-1.5 border border-border rounded-lg bg-bg text-text text-[0.8rem]'
const btn =
  'rounded-lg border border-border bg-transparent px-2 py-1 text-[0.75rem] text-text cursor-pointer'

export default function ProvidersPanel({
  onClose,
  onSaved,
}: {
  onClose: () => void
  onSaved?: () => void
}) {
  const [providers, setProviders] = useState<Provider[]>([])
  const [form, setForm] = useState(EMPTY)
  const [editId, setEditId] = useState<string | null>(null)
  const [clearKey, setClearKey] = useState(false)
  const [confirmId, setConfirmId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [models, setModels] = useState<ProviderModel[]>([])
  const [newModel, setNewModel] = useState('')
  const [open, setOpen] = useState(false)

  const run = useCallback(async (fn: () => Promise<unknown>) => {
    try {
      await fn()
      setProviders(await listProviders())
      setError(null)
      return true
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      return false
    }
  }, [])

  useEffect(() => {
    void run(async () => {})
  }, [run])

  const close = useCallback(() => {
    setOpen(false)
    setForm(EMPTY)
    setModels([])
    setEditId(null)
    setClearKey(false)
    setError(null)
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (open) close()
      else onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
    }
  }, [onClose, open, close])

  const save = async () => {
    // Empty optional fields are omitted. On edit, an empty key keeps the stored one.
    const body: ProviderInput = { name: form.name.trim(), type: form.type }
    if (form.baseUrl.trim()) body.baseUrl = form.baseUrl.trim()
    if (clearKey) body.apiKey = ''
    else if (form.apiKey) body.apiKey = form.apiKey
    if (form.defaultModel.trim()) body.defaultModel = form.defaultModel.trim()
    body.models = models
    const ok = await run(() => (editId ? updateProvider(editId, body) : createProvider(body)))
    if (ok) {
      close()
      onSaved?.()
    }
  }

  // A typed key wins; on edit with an empty key the backend uses the stored one.
  const loadModels = async () => {
    const q: FetchModelsInput = { type: form.type }
    if (form.baseUrl.trim()) q.baseUrl = form.baseUrl.trim()
    if (form.apiKey) q.apiKey = form.apiKey
    else if (editId) q.providerId = editId
    try {
      const fetched = await fetchModels(q)
      // A re-fetch only adds new ids; existing rows keep their edited limits.
      setModels(cur => [...cur, ...fetched.filter(m => !cur.some(c => c.id === m.id))])
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const addModel = () => {
    const id = newModel.trim()
    if (id && !models.some(m => m.id === id))
      setModels(cur => [...cur, { id, contextWindow: null, maxOutputTokens: null }])
    setNewModel('')
  }

  const setLimit = (id: string, k: 'contextWindow' | 'maxOutputTokens', v: string) => {
    setModels(cur => cur.map(m => (m.id === id ? { ...m, [k]: v === '' ? null : Number(v) } : m)))
  }

  const remove = async (id: string) => {
    setConfirmId(null)
    if (await run(() => deleteProvider(id))) onSaved?.()
  }

  const set = (k: keyof typeof EMPTY) => (e: { target: { value: string } }) => {
    setForm(f => ({ ...f, [k]: e.target.value }))
  }

  return (
    <div
      className="providers fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Providers"
      onClick={onClose}
    >
      <div
        className="w-full max-w-[560px] max-h-full overflow-auto rounded-lg border border-border bg-bg p-4 shadow-lg text-text"
        onClick={e => {
          e.stopPropagation()
        }}
      >
        <h3 className="mb-3 text-[0.95rem] font-semibold">Providers</h3>
        {!open && error && (
          <div role="alert" className="mb-2 text-[0.8rem] text-red-600">
            {error}
          </div>
        )}
        <ul className="list-none m-0 p-0 flex flex-col gap-1.5 mb-4">
          {providers.map(p => (
            <li
              key={p.id}
              className="providers__row flex items-center justify-between gap-2 border border-border rounded-lg px-2 py-1.5"
            >
              <div className="text-[0.8rem] min-w-0">
                <div className="font-semibold">{p.name}</div>
                <div className="text-muted break-all">
                  {p.type} · {p.baseUrl || 'default URL'} · key {p.apiKey || 'none'} · model{' '}
                  {p.defaultModel || 'none'} · {p.models.length} models
                </div>
              </div>
              <div className="flex gap-1 shrink-0">
                {confirmId === p.id ? (
                  <>
                    <button type="button" className={btn} onClick={() => void remove(p.id)}>
                      Confirm delete
                    </button>
                    <button type="button" className={btn} onClick={() => setConfirmId(null)}>
                      Cancel
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      type="button"
                      className={btn}
                      onClick={() => {
                        setEditId(p.id)
                        setClearKey(false)
                        setForm({
                          name: p.name,
                          type: p.type,
                          baseUrl: p.baseUrl,
                          apiKey: '',
                          defaultModel: p.defaultModel,
                        })
                        setModels(p.models)
                        setError(null)
                        setOpen(true)
                      }}
                    >
                      Edit
                    </button>
                    <button type="button" className={btn} onClick={() => setConfirmId(p.id)}>
                      Delete
                    </button>
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>
        <button
          type="button"
          className={btn}
          onClick={() => {
            setError(null)
            setOpen(true)
          }}
        >
          Add provider
        </button>
        <button type="button" className={`${btn} ml-2`} onClick={onClose}>
          Close
        </button>
      </div>
      {open && (
        <div
          className="provider-form fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          role="dialog"
          aria-modal="true"
          aria-label={editId ? 'Edit provider' : 'Add provider'}
          onClick={e => {
            e.stopPropagation()
            close()
          }}
        >
          <div
            className="w-full max-w-[560px] max-h-full overflow-auto rounded-lg border border-border bg-bg p-4 shadow-lg text-text"
            onClick={e => {
              e.stopPropagation()
            }}
          >
            <form
              className="grid grid-cols-2 gap-2 text-[0.8rem]"
              onSubmit={e => {
                e.preventDefault()
                void save()
              }}
            >
              <h4 className="col-span-2 font-semibold">
                {editId ? 'Edit provider' : 'Add provider'}
              </h4>
              {error && (
                <div role="alert" className="col-span-2 text-red-600">
                  {error}
                </div>
              )}
              <label>
                Name
                <input
                  aria-label="Name"
                  className={field}
                  value={form.name}
                  onChange={set('name')}
                />
              </label>
              <label>
                Type
                <select
                  aria-label="Type"
                  className={field}
                  value={form.type}
                  onChange={set('type')}
                >
                  {TYPES.map(t => (
                    <option key={t}>{t}</option>
                  ))}
                </select>
              </label>
              <label className="col-span-2">
                Base URL
                <input
                  aria-label="Base URL"
                  className={field}
                  placeholder={DEFAULT_URL[form.type]}
                  value={form.baseUrl}
                  onChange={set('baseUrl')}
                />
              </label>
              <label>
                API key
                <input
                  aria-label="API key"
                  type="password"
                  className={field}
                  placeholder={editId ? 'empty = keep stored key' : ''}
                  disabled={clearKey}
                  value={form.apiKey}
                  onChange={set('apiKey')}
                />
              </label>
              <label>
                Default model
                <input
                  aria-label="Default model"
                  className={field}
                  value={form.defaultModel}
                  onChange={set('defaultModel')}
                />
              </label>
              {editId && (
                <label className="flex items-center gap-1.5 self-end">
                  <input
                    type="checkbox"
                    aria-label="Clear key"
                    checked={clearKey}
                    onChange={e => setClearKey(e.target.checked)}
                  />
                  Clear key
                </label>
              )}
              <div className="col-span-2 flex flex-col gap-1.5">
                <div className="flex items-center justify-between">
                  <span className="font-semibold">Models</span>
                  <button type="button" className={btn} onClick={() => void loadModels()}>
                    Fetch models
                  </button>
                </div>
                {models.length > 0 && (
                  <table className="w-full text-left">
                    <thead className="text-muted">
                      <tr>
                        <th>Model</th>
                        <th>Context window</th>
                        <th>Max output tokens</th>
                        <th>Default</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {models.map(m => (
                        <tr key={m.id}>
                          <td className="break-all">{m.id}</td>
                          {(['contextWindow', 'maxOutputTokens'] as const).map(k => (
                            <td key={k}>
                              <input
                                type="number"
                                min={1}
                                aria-label={`${k === 'contextWindow' ? 'Context window' : 'Max output tokens'} for ${m.id}`}
                                className={field}
                                value={m[k] ?? ''}
                                onChange={e => setLimit(m.id, k, e.target.value)}
                              />
                            </td>
                          ))}
                          <td>
                            <input
                              type="radio"
                              name="defaultModel"
                              aria-label={`Default ${m.id}`}
                              checked={form.defaultModel === m.id}
                              onChange={() => setForm(f => ({ ...f, defaultModel: m.id }))}
                            />
                          </td>
                          <td>
                            <button
                              type="button"
                              aria-label={`Remove ${m.id}`}
                              className={btn}
                              onClick={() => setModels(cur => cur.filter(x => x.id !== m.id))}
                            >
                              Remove
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <div className="flex gap-2">
                  <input
                    aria-label="New model id"
                    placeholder="model id"
                    className={field}
                    value={newModel}
                    onChange={e => setNewModel(e.target.value)}
                  />
                  <button type="button" className={`${btn} shrink-0`} onClick={addModel}>
                    Add model
                  </button>
                </div>
              </div>
              <div className="col-span-2 mt-2 flex justify-end gap-2">
                <button type="button" className={btn} onClick={close}>
                  Cancel
                </button>
                <button
                  type="submit"
                  className="rounded-lg border-0 px-3 py-1 text-[0.75rem] font-semibold bg-text text-bg cursor-pointer"
                >
                  Save
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
