import { useEffect, useId, useState } from 'react'
import { listModels, type Provider } from '../api/providers'

export type Selection = { providerId: string; model: string }

// M10: per-message provider + model. The model is free text; the datalist only
// suggests the provider's models, so a failed model list never blocks a send.
export default function ModelPicker({
  providers,
  value,
  onChange,
  disabled,
}: {
  providers: Provider[]
  value: Selection
  onChange: (s: Selection) => void
  disabled?: boolean
}) {
  const [models, setModels] = useState<string[]>([])
  const listId = useId()
  // M10: the provider's stored models win; listModels is the fallback.
  const stored = providers.find(p => p.id === value.providerId)?.models.map(m => m.id) ?? []
  const storedKey = stored.join('\n')

  useEffect(() => {
    let live = true
    setModels(storedKey ? storedKey.split('\n') : [])
    if (value.providerId && !storedKey)
      listModels(value.providerId)
        .then(m => {
          if (live) setModels(m)
        })
        .catch(() => {})
    return () => {
      live = false
    }
  }, [value.providerId, storedKey])

  const field = 'py-1.5 px-2 border border-border rounded-lg bg-bg text-text text-[0.8rem]'
  return (
    <div className="model-picker flex flex-col gap-1">
      <select
        aria-label="Provider"
        className={field}
        disabled={disabled}
        value={value.providerId}
        onChange={e => {
          const p = providers.find(x => x.id === e.target.value)
          onChange({ providerId: e.target.value, model: p?.defaultModel ?? '' })
        }}
      >
        {providers.map(p => (
          <option key={p.id} value={p.id}>
            {p.name}
          </option>
        ))}
      </select>
      <input
        aria-label="Model"
        className={`${field} w-36`}
        list={listId}
        disabled={disabled}
        value={value.model}
        onChange={e => {
          onChange({ providerId: value.providerId, model: e.target.value })
        }}
      />
      <datalist id={listId}>
        {models.map(m => (
          <option key={m} value={m} />
        ))}
      </datalist>
    </div>
  )
}
