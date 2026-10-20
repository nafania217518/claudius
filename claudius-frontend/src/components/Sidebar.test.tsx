// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent, act, cleanup, within, screen } from '@testing-library/react'
import Sidebar from './Sidebar'
import type { Workspaces } from '../hooks/useWorkspaces'
import type { Workspace } from '../api/workspaces'

// Sidebar takes create/rename/remove as props (they come from useWorkspaces and
// already refresh internally), so the unit test just wires up mock fns and checks
// which one the dialog's confirm calls, and with what argument.
const ws = (id: string, name: string): Workspace => ({ id, name, dir: name, createdAt: 0 })

// Each CRUD action opens an in-app Dialog. Its confirm button shares its label
// with the row's Rename/Delete button, so queries inside the card are scoped
// with `within` on the `.dialog` overlay to disambiguate the two.
const dialog = () => within(document.querySelector('.dialog')!)

// The mutation props are typed as the real hook's, but here they're stubs.
let create: Workspaces['create']
let rename: Workspaces['rename']
let remove: Workspaces['remove']

const renderSidebar = (props: { workspaces: Workspace[]; activeId?: string }) =>
  render(
    <Sidebar
      workspaces={props.workspaces}
      activeId={props.activeId}
      onSelect={() => {}}
      create={create}
      rename={rename}
      remove={remove}
    />,
  )

describe('Sidebar', () => {
  beforeEach(() => {
    create = vi.fn()
    rename = vi.fn()
    remove = vi.fn()
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('lists workspaces and marks the active one', () => {
    const { getByText } = renderSidebar({
      workspaces: [ws('a', 'Alpha'), ws('b', 'Beta')],
      activeId: 'a',
    })
    expect(getByText('Alpha')).toBeTruthy()
    expect(getByText('Beta')).toBeTruthy()
    expect(document.querySelector('.sidebar__item.is-active')?.textContent).toContain('Alpha')
  })

  it('rename and delete show only on the active workspace', () => {
    const { getAllByRole } = renderSidebar({
      workspaces: [ws('a', 'Alpha'), ws('b', 'Beta')],
      activeId: 'b',
    })
    expect(getAllByRole('button', { name: 'Rename' })).toHaveLength(1)
    expect(getAllByRole('button', { name: 'Delete' })).toHaveLength(1)
    expect(document.querySelector('.is-active .sidebar__actions')).toBeTruthy()
  })

  it('a long name stays on one line and fades out at the edge', () => {
    const { getByText } = renderSidebar({ workspaces: [ws('a', 'A'.repeat(60))], activeId: 'a' })
    const cls = getByText('A'.repeat(60)).className
    expect(cls).toContain('whitespace-nowrap')
    expect(cls).toContain('overflow-hidden')
    expect(cls).toContain('[mask-image:')
  })

  it('selects the clicked workspace', () => {
    const onSelect = vi.fn()
    const { getByText } = render(
      <Sidebar
        workspaces={[ws('a', 'Alpha'), ws('b', 'Beta')]}
        activeId="a"
        onSelect={onSelect}
        create={create}
        rename={rename}
        remove={remove}
      />,
    )
    fireEvent.click(getByText('Beta'))
    expect(onSelect).toHaveBeenCalledWith('b')
  })

  it('creates a workspace from a trimmed dialog input', async () => {
    const { getByText } = renderSidebar({ workspaces: [], activeId: undefined })
    fireEvent.click(getByText('+ New'))
    fireEvent.change(dialog().getByRole('textbox'), { target: { value: '  New   ' } })
    fireEvent.click(dialog().getByRole('button', { name: 'Create' }))
    await act(async () => {
      await Promise.resolve()
    })
    expect(create).toHaveBeenCalledWith('New')
  })

  it('skips create when the dialog input is blank', () => {
    const { getByText } = renderSidebar({ workspaces: [], activeId: undefined })
    fireEvent.click(getByText('+ New'))
    fireEvent.click(dialog().getByRole('button', { name: 'Create' }))
    expect(create).not.toHaveBeenCalled()
  })

  it('renames to a different name', async () => {
    const { getByRole } = renderSidebar({ workspaces: [ws('b', 'Beta')], activeId: 'b' })
    fireEvent.click(getByRole('button', { name: 'Rename' }))
    fireEvent.change(dialog().getByRole('textbox'), { target: { value: 'Beta2' } })
    fireEvent.click(dialog().getByRole('button', { name: 'Rename' }))
    await act(async () => {
      await Promise.resolve()
    })
    expect(rename).toHaveBeenCalledWith('b', 'Beta2')
  })

  it('skips rename when the name is unchanged or blank', () => {
    const { getByRole } = renderSidebar({ workspaces: [ws('b', 'Beta')], activeId: 'b' })
    // unchanged: the field is seeded with the current name, so confirming a no-op
    // closes the dialog without a rename.
    fireEvent.click(getByRole('button', { name: 'Rename' }))
    fireEvent.click(dialog().getByRole('button', { name: 'Rename' }))
    expect(rename).not.toHaveBeenCalled()
    // blank: clearing the field to whitespace also skips the rename.
    fireEvent.click(getByRole('button', { name: 'Rename' }))
    fireEvent.change(dialog().getByRole('textbox'), { target: { value: '   ' } })
    fireEvent.click(dialog().getByRole('button', { name: 'Rename' }))
    expect(rename).not.toHaveBeenCalled()
  })

  it('deletes after confirming in the dialog', async () => {
    const { getByRole } = renderSidebar({ workspaces: [ws('d', 'Delta')], activeId: 'd' })
    fireEvent.click(getByRole('button', { name: 'Delete' }))
    fireEvent.click(dialog().getByRole('button', { name: 'Delete' }))
    await act(async () => {
      await Promise.resolve()
    })
    expect(remove).toHaveBeenCalledWith('d')
  })

  it('skips delete when the dialog is cancelled', () => {
    const { getByRole } = renderSidebar({ workspaces: [ws('d', 'Delta')], activeId: 'd' })
    fireEvent.click(getByRole('button', { name: 'Delete' }))
    fireEvent.click(dialog().getByRole('button', { name: 'Cancel' }))
    expect(remove).not.toHaveBeenCalled()
  })

  it('the delete dialog explains what is deleted', () => {
    const { getByRole } = renderSidebar({ workspaces: [ws('d', 'Delta')], activeId: 'd' })
    fireEvent.click(getByRole('button', { name: 'Delete' }))
    expect(dialog().getByText('Delete "Delta"?')).toBeTruthy()
    expect(
      dialog().getByText('This deletes the workspace directory and its chat history.'),
    ).toBeTruthy()
  })

  it('the sidebar marks a workspace with a run in progress', () => {
    render(
      <Sidebar
        workspaces={[ws('a', 'Alpha'), ws('b', 'Beta')]}
        running={['b']}
        onSelect={() => {}}
        create={create}
        rename={rename}
        remove={remove}
      />,
    )
    const dots = document.querySelectorAll('[aria-label="running"]')
    expect(dots).toHaveLength(1)
    expect(dots[0]!.getAttribute('title')).toBe('running')
    expect(dots[0]!.closest('li')!.textContent).toContain('Beta')
  })

  it('a closed panel shows only an expand button that calls onToggle', () => {
    const onToggle = vi.fn()
    render(
      <Sidebar
        workspaces={[ws('a', 'Alpha')]}
        onSelect={() => {}}
        create={create}
        rename={rename}
        remove={remove}
        open={false}
        onToggle={onToggle}
      />,
    )
    expect(document.querySelector('.sidebar__list')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Show workspaces' }))
    expect(onToggle).toHaveBeenCalled()
  })

  it('the hide button calls onToggle and the width prop sets the panel width', () => {
    const onToggle = vi.fn()
    render(
      <Sidebar
        workspaces={[]}
        onSelect={() => {}}
        create={create}
        rename={rename}
        remove={remove}
        width={250}
        onToggle={onToggle}
      />,
    )
    const aside = document.querySelector('aside')!
    expect(aside.style.getPropertyValue('--w')).toBe('250px')
    expect(aside.className).toContain('sm:w-(--w)')
    const hide = screen.getByRole('button', { name: 'Hide workspaces' })
    // The hide button is the first thing in the head, left of the title.
    expect(document.querySelector('.sidebar__head')!.firstElementChild).toBe(hide)
    fireEvent.click(hide)
    expect(onToggle).toHaveBeenCalled()
  })
})
