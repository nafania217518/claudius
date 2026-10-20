// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, act, cleanup } from '@testing-library/react'
import { useWorkspaces } from './useWorkspaces'
import { listWorkspaces, setPermissionMode } from '../api/workspaces'

vi.mock('../api/workspaces')

const w = (id: string) => ({ id, name: id, dir: id, createdAt: 0 })
const KEY = 'claudius.activeWorkspace'
const settle = () =>
  act(async () => {
    await new Promise(r => setTimeout(r, 0))
  })

describe('useWorkspaces', () => {
  afterEach(() => {
    cleanup()
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('the active workspace is remembered across reloads', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([w('a'), w('b')])
    const first = renderHook(() => useWorkspaces())
    await settle()
    expect(first.result.current.activeId).toBe('a')
    act(() => first.result.current.select('b'))
    expect(localStorage.getItem(KEY)).toBe('b')
    first.unmount()

    const second = renderHook(() => useWorkspaces())
    await settle()
    expect(second.result.current.activeId).toBe('b')
  })

  it('a remembered workspace that no longer exists falls back to the first one', async () => {
    localStorage.setItem(KEY, 'gone')
    vi.mocked(listWorkspaces).mockResolvedValue([w('a'), w('b')])
    const { result } = renderHook(() => useWorkspaces())
    await settle()
    expect(result.current.activeId).toBe('a')
  })

  it('a storage failure does not break the selection', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.mocked(listWorkspaces).mockResolvedValue([w('a'), w('b')])
    const { result } = renderHook(() => useWorkspaces())
    await settle()
    expect(result.current.activeId).toBe('a')
  })

  it('setMode saves the permission mode and mirrors it on the workspace', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([w('a')])
    vi.mocked(setPermissionMode).mockResolvedValue({ ...w('a'), permissionMode: 'BYPASS' })
    const { result } = renderHook(() => useWorkspaces())
    await settle()
    await act(() => result.current.setMode('a', 'BYPASS'))
    expect(setPermissionMode).toHaveBeenCalledWith('a', 'BYPASS')
    expect(result.current.workspaces[0]?.permissionMode).toBe('BYPASS')
    expect(result.current.error).toBeNull()
  })

  it('setMode surfaces a failure in error', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue([w('a')])
    vi.mocked(setPermissionMode).mockRejectedValue(new Error('set permission mode failed: 500'))
    const { result } = renderHook(() => useWorkspaces())
    await settle()
    await act(() => result.current.setMode('a', 'BYPASS'))
    expect(result.current.error).toBe('set permission mode failed: 500')
  })
})
