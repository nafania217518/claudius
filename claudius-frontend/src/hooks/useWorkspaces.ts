import { useCallback, useEffect, useState } from 'react'
import {
  createWorkspace,
  deleteWorkspace,
  listWorkspaces,
  renameWorkspace,
  setContextLimit,
  setPermissionMode,
  type PermissionMode,
  type Workspace,
} from '../api/workspaces'

// M2: all workspace orchestration lives here so App just composes this with
// useChat. `refresh`/`create`/`rename`/`remove` catch into `error` (never
// reject) so a failed call surfaces in the UI instead of an unhandled
// rejection, and the initial list load runs on mount.
// The active workspace id survives a reload in localStorage.
const KEY = 'claudius.activeWorkspace'
const stored = () => {
  try {
    return localStorage.getItem(KEY) ?? undefined
  } catch {
    return undefined
  }
}

export function useWorkspaces() {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  // Seeded from storage; refresh keeps it only when it still exists.
  const [activeId, setActiveId] = useState<string | undefined>(stored)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      const ws = await listWorkspaces()
      setWorkspaces(ws)
      setActiveId(cur => (cur && ws.some(w => w.id === cur) ? cur : ws[0]?.id))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  // Refresh after a mutation. When the active workspace is deleted, the first
  // remaining one is selected. The empty state shows only when none is left.
  const mutate = useCallback(
    async (fn: () => Promise<unknown>) => {
      try {
        await fn()
        await refresh()
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e))
      }
    },
    [refresh],
  )

  // Create selects the new workspace so a send after create lands in it, not the
  // previously-active thread (refresh then keeps the just-created id).
  const create = useCallback(
    (name: string) =>
      mutate(async () => {
        const created = await createWorkspace(name)
        setActiveId(created.id)
      }),
    [mutate],
  )
  const rename = useCallback(
    (id: string, name: string) => mutate(() => renameWorkspace(id, name)),
    [mutate],
  )
  const remove = useCallback(
    (id: string) =>
      mutate(async () => {
        await deleteWorkspace(id)
        setActiveId(cur => (cur === id ? undefined : cur))
      }),
    [mutate],
  )

  // M10: mirror the backend's "last provider/model" locally after a send (no refetch).
  const remember = useCallback((id: string, providerId: string, model: string) => {
    setWorkspaces(list =>
      list.map(w => (w.id === id ? { ...w, lastProviderId: providerId, lastModel: model } : w)),
    )
  }, [])

  // M11: mirror the backend's context size locally (after a turn or a compact).
  const setTokens = useCallback((id: string, contextTokens: number) => {
    setWorkspaces(list => list.map(w => (w.id === id ? { ...w, contextTokens } : w)))
  }, [])

  // M11: save the context limit; mirror the returned workspace locally.
  const setLimit = useCallback(async (id: string, limit: number | null) => {
    try {
      const w = await setContextLimit(id, limit)
      setWorkspaces(list =>
        list.map(x => (x.id === id ? { ...x, contextLimit: w.contextLimit } : x)),
      )
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  // M13: save the permission mode; mirror the returned workspace locally.
  const setMode = useCallback(async (id: string, mode: PermissionMode) => {
    try {
      const w = await setPermissionMode(id, mode)
      setWorkspaces(list =>
        list.map(x => (x.id === id ? { ...x, permissionMode: w.permissionMode } : x)),
      )
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  useEffect(() => {
    if (!activeId) return
    try {
      localStorage.setItem(KEY, activeId)
    } catch {
      // Storage is off (private mode, blocked): the choice is not remembered.
    }
  }, [activeId])

  return {
    workspaces,
    activeId,
    select: setActiveId,
    error,
    refresh,
    create,
    rename,
    remove,
    remember,
    setTokens,
    setLimit,
    setMode,
  }
}

export type Workspaces = ReturnType<typeof useWorkspaces>
