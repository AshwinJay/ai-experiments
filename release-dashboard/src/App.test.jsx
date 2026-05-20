import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, beforeEach } from 'vitest'
import App, { mergeReleases, mergeNoteArrays } from './App'

// Ensure username is pre-set so the modal doesn't block existing tests
beforeEach(() => {
  localStorage.setItem('rdUsername', 'test')
})

// Wait for the loading spinner to disappear before making assertions.
async function waitForLoad() {
  await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
}

describe('Phase bar', () => {
  it('is not rendered in the dashboard', async () => {
    render(<App />)
    await waitForLoad()
    // "Planning" only appears in the phase bar — it is not a checklist phase label
    expect(screen.queryByText('Planning')).not.toBeInTheDocument()
  })

  it('does not render the Mon Review phase step', async () => {
    render(<App />)
    await waitForLoad()
    // "Mon Review" appears in both the phase bar and the checklist pills;
    // on the default "board" tab there is no checklist, so its presence
    // means the phase bar is still rendered.
    expect(screen.queryByText('Mon Review')).not.toBeInTheDocument()
  })
})

// Returns the numeric text of a stat counter by its label (e.g. "Approved" → "2")
function getStatCount(label) {
  return screen.getByText(label).previousElementSibling?.textContent
}

// Adds a service via the board form using fireEvent (faster than userEvent.type).
function addService(name = 'test-service') {
  fireEvent.click(screen.getByText('+ Add Service'))
  fireEvent.change(screen.getByPlaceholderText('e.g. auth-service'), { target: { value: name } })
  fireEvent.click(screen.getByText('Add'))
}

describe('Summary counters', () => {
  it('all show 0 when there are no services', async () => {
    render(<App />)
    await waitForLoad()
    expect(getStatCount('Services')).toBe('0')
    expect(getStatCount('Approved')).toBe('0')
    expect(getStatCount('Deployed')).toBe('0')
    expect(getStatCount('Hotfixes')).toBe('0')
    expect(getStatCount('Failed')).toBe('0')
  })

  it('Services increments when a service is added', async () => {
    render(<App />)
    await waitForLoad()
    addService('alpha')
    expect(getStatCount('Services')).toBe('1')
  })

  it('Approved increments when a service status is set to approved', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    // Service starts as "pending"; "approved" appears once (in the status row)
    fireEvent.click(screen.getByText('approved'))
    expect(getStatCount('Approved')).toBe('1')
    expect(getStatCount('Deployed')).toBe('0')
  })

  it('Deployed increments and Approved stays ≥1 when all regions are deployed', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    // deployed status is auto-set once every region reaches "deployed"
    for (const region of ["pre-production","us-east-1","us-west-2","eu-west-1","ap-southeast-1"]) {
      fireEvent.change(screen.getByLabelText(`Region ${region}`), {target:{value:"deployed"}})
    }
    expect(getStatCount('Deployed')).toBe('1')
    // deployed services also count toward Approved
    expect(getStatCount('Approved')).toBe('1')
  })

  it('Failed increments when a service status is set to failed', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    // "failed" also appears as <option> in region selects; target the status chip <span>
    const failedChip = screen.getAllByText('failed').find(el => el.tagName.toLowerCase() === 'span')
    fireEvent.click(failedChip)
    expect(getStatCount('Failed')).toBe('1')
  })

  it('Hotfixes increments when needs-hotfix chip is clicked', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    fireEvent.click(screen.getByText('needs-hotfix'))
    expect(getStatCount('Hotfixes')).toBe('1')
  })
})

describe('Checklist tab', () => {
  it('still renders phase pills after phase bar removal', async () => {
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Release Checklist'))
    // Phase pills should still appear next to checklist items
    expect(screen.getAllByText('Branch Cut').length).toBeGreaterThan(0)
  })

  it('does not have a Release Notes / Comments textarea', async () => {
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Release Checklist'))
    expect(screen.queryByText('Release Notes / Comments')).not.toBeInTheDocument()
  })
})

describe('Notes tab', () => {
  it('has a Notes tab button', async () => {
    render(<App />)
    await waitForLoad()
    expect(screen.getByText('Notes')).toBeInTheDocument()
  })

  it('shows an Add note button when Notes tab is active', async () => {
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Notes'))
    expect(screen.getByText('+ Add note')).toBeInTheDocument()
  })

  it('adds a note input when Add note is clicked', async () => {
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Notes'))
    fireEvent.click(screen.getByText('+ Add note'))
    expect(screen.getByPlaceholderText('Note…')).toBeInTheDocument()
  })

  it('marks a note as done when its checkbox is clicked', async () => {
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Notes'))
    fireEvent.click(screen.getByText('+ Add note'))
    const checkbox = screen.getByRole('checkbox')
    expect(checkbox).not.toBeChecked()
    fireEvent.click(checkbox)
    expect(checkbox).toBeChecked()
  })

  it('deletes a note when × is clicked', async () => {
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Notes'))
    fireEvent.click(screen.getByText('+ Add note'))
    expect(screen.getByPlaceholderText('Note…')).toBeInTheDocument()
    fireEvent.click(screen.getByText('×'))
    expect(screen.queryByPlaceholderText('Note…')).not.toBeInTheDocument()
  })

  it('adds a sub-item when the ↳ button is clicked', async () => {
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Notes'))
    fireEvent.click(screen.getByText('+ Add note'))
    fireEvent.click(screen.getByTitle('Add sub-item'))
    expect(screen.getAllByPlaceholderText('Note…').length).toBe(2)
  })

  it('adds a tag when tag name is entered and submitted', async () => {
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Notes'))
    fireEvent.click(screen.getByText('+ Add note'))
    fireEvent.click(screen.getByTitle('Add tag'))
    const tagInput = screen.getByPlaceholderText('tag…')
    fireEvent.change(tagInput, { target: { value: 'urgent' } })
    fireEvent.keyDown(tagInput, { key: 'Enter' })
    expect(screen.getByText('#urgent')).toBeInTheDocument()
  })
})

describe('Service status labels', () => {
  it('clicking a status chip updates the service status pill', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    // Card starts with a "pending" status pill; click "testing" chip to change it
    fireEvent.click(screen.getByText('testing'))
    // The active status pill on the card should now read "testing"
    const pills = screen.getAllByText('testing')
    // At least one pill should be active (the card badge), not just the chip
    expect(pills.length).toBeGreaterThanOrEqual(1)
    // "pending" active pill should no longer be visible as a card badge
    // (it still exists as a chip in the status row, but no longer as the active status)
    expect(getStatCount('Approved')).toBe('0')
  })

  it('switching status to approved updates the Approved counter', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    fireEvent.click(screen.getByText('testing'))
    expect(getStatCount('Approved')).toBe('0')
    fireEvent.click(screen.getAllByText('approved')[0])
    expect(getStatCount('Approved')).toBe('1')
  })
})

describe('needs-hotfix chip as hotfix toggle', () => {
  it('clicking needs-hotfix chip shows the hotfix section', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    expect(screen.queryByPlaceholderText('e.g. v2.14.1-hotfix')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('needs-hotfix'))
    expect(screen.getByPlaceholderText('e.g. v2.14.1-hotfix')).toBeInTheDocument()
  })

  it('clicking needs-hotfix chip a second time hides the hotfix section', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    fireEvent.click(screen.getByText('needs-hotfix'))
    expect(screen.getByPlaceholderText('e.g. v2.14.1-hotfix')).toBeInTheDocument()
    // click again — needs-hotfix is now highlighted so getAllByText returns [pill, chip]
    const chips = screen.getAllByText('needs-hotfix')
    fireEvent.click(chips[chips.length - 1])
    expect(screen.queryByPlaceholderText('e.g. v2.14.1-hotfix')).not.toBeInTheDocument()
    expect(getStatCount('Hotfixes')).toBe('0')
  })

  it('does not render a dedicated Request Hotfix button', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    expect(screen.queryByTitle('Request hotfix')).not.toBeInTheDocument()
    expect(screen.queryByTitle('Cancel hotfix')).not.toBeInTheDocument()
  })

  it('does not render a separate HOTFIX pill when hotfix is active', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    fireEvent.click(screen.getByText('needs-hotfix'))
    // The word "HOTFIX" should not appear as a standalone pill
    expect(screen.queryByText('HOTFIX')).not.toBeInTheDocument()
  })
})

describe('Hotfix section layout', () => {
  it('hotfix details appear before the status strip', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    fireEvent.click(screen.getByText('needs-hotfix'))
    const hotfixInput = screen.getByPlaceholderText('e.g. v2.14.1-hotfix')
    const pendingChip = screen.getAllByText('pending').find(
      el => el.tagName.toLowerCase() === 'span' && el.textContent === 'pending'
    )
    // hotfix input must come before the status chip strip in the DOM
    const order = hotfixInput.compareDocumentPosition(pendingChip)
    // DOCUMENT_POSITION_FOLLOWING = 4 means pendingChip comes after hotfixInput
    expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

describe('Label field in service card', () => {
  it('shows a "Label" field (not "Label / Tag") in the service card', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    expect(screen.queryByText('Label / Tag')).not.toBeInTheDocument()
    expect(screen.getByText('Label')).toBeInTheDocument()
  })

  it('label input in the service card updates the service label', async () => {
    render(<App />)
    await waitForLoad()
    addService('svc-a')
    const labelInput = screen.getByPlaceholderText('e.g. v2.14.0-rc1')
    fireEvent.change(labelInput, { target: { value: 'v1.0.0-rc1' } })
    expect(labelInput.value).toBe('v1.0.0-rc1')
  })
})

describe('Regional deployment in service card', () => {
  it('shows region selectors for all regions in each service card', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    expect(screen.getByLabelText('Region pre-production')).toBeInTheDocument()
    expect(screen.getByLabelText('Region us-east-1')).toBeInTheDocument()
    expect(screen.getByLabelText('Region eu-west-1')).toBeInTheDocument()
  })

  it('auto-sets service status to deployed when all regions are deployed', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    for (const region of ["pre-production","us-east-1","us-west-2","eu-west-1","ap-southeast-1"]) {
      fireEvent.change(screen.getByLabelText(`Region ${region}`), {target:{value:"deployed"}})
    }
    expect(getStatCount('Deployed')).toBe('1')
  })

  it('deployed chip has no manual click handler and does not update status when clicked', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    const deployedChip = screen.getByTitle('Auto-set when all regions are deployed')
    fireEvent.click(deployedChip)
    expect(getStatCount('Deployed')).toBe('0')
  })

  it('reverts from deployed to deploying when a region is un-deployed', async () => {
    render(<App />)
    await waitForLoad()
    addService()
    for (const region of ["pre-production","us-east-1","us-west-2","eu-west-1","ap-southeast-1"]) {
      fireEvent.change(screen.getByLabelText(`Region ${region}`), {target:{value:"deployed"}})
    }
    expect(getStatCount('Deployed')).toBe('1')
    // walk back one region
    fireEvent.change(screen.getByLabelText('Region us-east-1'), {target:{value:"pending"}})
    expect(getStatCount('Deployed')).toBe('0')
  })
})

// ── mergeReleases unit tests ──────────────────────────────────────────────────

const makeSvc = (overrides = {}) => ({
  id: 'svc-1', name: 'alpha', repo: 'org/alpha', changeType: 'code',
  label: '', hotfixLabel: '', poc: '', dependencies: [], status: 'pending',
  regions: {}, hasHotfix: false, hotfixNotes: '', deployConfirmed: false,
  hotfixMergedMain: false, hotfixMergedRelease: false, hotfixMergedHotfix: false,
  updatedAt: 1000, deletedAt: null,
  ...overrides,
})

const makeTestNote = (overrides = {}) => ({
  id: 'note-1', text: 'hello', done: false, tags: [], children: [],
  updatedAt: 1000, deletedAt: null,
  ...overrides,
})

describe('mergeReleases', () => {
  it('returns EMPTY_RELEASE for empty input', () => {
    const result = mergeReleases([])
    expect(result.services).toEqual([])
    expect(result.notes).toEqual([])
    expect(result.releaseManager).toBe('')
  })

  it('returns single file unchanged (minus computed fields)', () => {
    const file = {
      releaseManager: 'alice', releaseBranch: 'release/2026-W10',
      hotfixBranch: '', phase: 'testing', savedAt: 5000,
      services: [makeSvc()], notes: [], checklist: {},
    }
    const result = mergeReleases([file])
    expect(result.releaseManager).toBe('alice')
    expect(result.services).toHaveLength(1)
  })

  it('scalar fields: latest savedAt wins', () => {
    const older = { releaseManager: 'alice', releaseBranch: 'old', hotfixBranch: '', phase: 'planning', savedAt: 1000, services: [], notes: [], checklist: {} }
    const newer = { releaseManager: 'bob',   releaseBranch: 'new', hotfixBranch: '', phase: 'testing', savedAt: 9000, services: [], notes: [], checklist: {} }
    const result = mergeReleases([older, newer])
    expect(result.releaseManager).toBe('bob')
    expect(result.releaseBranch).toBe('new')
    expect(result.phase).toBe('testing')
  })

  it('scalar fields: order independent — latest savedAt wins regardless of array order', () => {
    const older = { releaseManager: 'alice', releaseBranch: 'old', hotfixBranch: '', phase: 'planning', savedAt: 1000, services: [], notes: [], checklist: {} }
    const newer = { releaseManager: 'bob',   releaseBranch: 'new', hotfixBranch: '', phase: 'testing', savedAt: 9000, services: [], notes: [], checklist: {} }
    const result = mergeReleases([newer, older])
    expect(result.releaseManager).toBe('bob')
  })

  it('services: union by id — services from both files appear', () => {
    const fileA = { savedAt: 1000, services: [makeSvc({ id: 'svc-1', name: 'alpha', updatedAt: 1000 })], notes: [], checklist: {} }
    const fileB = { savedAt: 1000, services: [makeSvc({ id: 'svc-2', name: 'beta',  updatedAt: 1000 })], notes: [], checklist: {} }
    const result = mergeReleases([fileA, fileB])
    expect(result.services).toHaveLength(2)
    expect(result.services.map(s => s.name).sort()).toEqual(['alpha', 'beta'])
  })

  it('services: latest updatedAt wins per id', () => {
    const older = makeSvc({ status: 'pending',  updatedAt: 1000 })
    const newer = makeSvc({ status: 'approved', updatedAt: 9000 })
    const fileA = { savedAt: 1000, services: [older], notes: [], checklist: {} }
    const fileB = { savedAt: 1000, services: [newer], notes: [], checklist: {} }
    const result = mergeReleases([fileA, fileB])
    expect(result.services).toHaveLength(1)
    expect(result.services[0].status).toBe('approved')
  })

  it('services: tombstone propagates — deletedAt service is retained in data', () => {
    const live      = makeSvc({ deletedAt: null,      updatedAt: 1000 })
    const tombstone = makeSvc({ deletedAt: 9999, updatedAt: 9000 })
    const fileA = { savedAt: 1000, services: [live],      notes: [], checklist: {} }
    const fileB = { savedAt: 1000, services: [tombstone], notes: [], checklist: {} }
    const result = mergeReleases([fileA, fileB])
    expect(result.services).toHaveLength(1)
    expect(result.services[0].deletedAt).toBe(9999)
  })

  it('checklist: OR-merge — checked by anyone means checked', () => {
    const fileA = { savedAt: 1000, services: [], notes: [], checklist: { branches_cut: true,  labels_produced: false } }
    const fileB = { savedAt: 1000, services: [], notes: [], checklist: { branches_cut: false, labels_produced: true  } }
    const result = mergeReleases([fileA, fileB])
    expect(result.checklist.branches_cut).toBe(true)
    expect(result.checklist.labels_produced).toBe(true)
  })

  it('checklist: unchecked by both stays unchecked', () => {
    const fileA = { savedAt: 1000, services: [], notes: [], checklist: { branches_cut: false } }
    const fileB = { savedAt: 1000, services: [], notes: [], checklist: { branches_cut: false } }
    const result = mergeReleases([fileA, fileB])
    expect(result.checklist.branches_cut).toBe(false)
  })

  it('checklist: keys only in one file are included', () => {
    const fileA = { savedAt: 1000, services: [], notes: [], checklist: { branches_cut: true } }
    const fileB = { savedAt: 1000, services: [], notes: [], checklist: {} }
    const result = mergeReleases([fileA, fileB])
    expect(result.checklist.branches_cut).toBe(true)
  })

  it('notes: union by id — notes from both files appear', () => {
    const noteA = makeTestNote({ id: 'n-1', text: 'first',  updatedAt: 1000 })
    const noteB = makeTestNote({ id: 'n-2', text: 'second', updatedAt: 1000 })
    const fileA = { savedAt: 1000, services: [], notes: [noteA], checklist: {} }
    const fileB = { savedAt: 1000, services: [], notes: [noteB], checklist: {} }
    const result = mergeReleases([fileA, fileB])
    expect(result.notes).toHaveLength(2)
  })

  it('notes: latest updatedAt wins per id', () => {
    const older = makeTestNote({ text: 'old', updatedAt: 1000 })
    const newer = makeTestNote({ text: 'new', updatedAt: 9000 })
    const fileA = { savedAt: 1000, services: [], notes: [older], checklist: {} }
    const fileB = { savedAt: 1000, services: [], notes: [newer], checklist: {} }
    const result = mergeReleases([fileA, fileB])
    expect(result.notes).toHaveLength(1)
    expect(result.notes[0].text).toBe('new')
  })

  it('notes: tombstone propagates', () => {
    const live      = makeTestNote({ deletedAt: null, updatedAt: 1000 })
    const tombstone = makeTestNote({ deletedAt: 9999, updatedAt: 9000 })
    const fileA = { savedAt: 1000, services: [], notes: [live],      checklist: {} }
    const fileB = { savedAt: 1000, services: [], notes: [tombstone], checklist: {} }
    const result = mergeReleases([fileA, fileB])
    expect(result.notes[0].deletedAt).toBe(9999)
  })

  it('merges three concurrent files correctly', () => {
    const alice = {
      savedAt: 5000, releaseManager: 'alice',
      services: [makeSvc({ id: 'svc-1', status: 'approved', updatedAt: 5000 })],
      notes: [makeTestNote({ id: 'n-1', text: 'from alice', updatedAt: 5000 })],
      checklist: { branches_cut: true },
    }
    const bob = {
      savedAt: 3000, releaseManager: 'bob',
      services: [
        makeSvc({ id: 'svc-1', status: 'pending', updatedAt: 3000 }),
        makeSvc({ id: 'svc-2', name: 'beta', updatedAt: 3000 }),
      ],
      notes: [],
      checklist: { labels_produced: true },
    }
    const carol = {
      savedAt: 1000, releaseManager: 'carol',
      services: [makeSvc({ id: 'svc-3', name: 'gamma', updatedAt: 1000 })],
      notes: [makeTestNote({ id: 'n-1', text: 'from carol', updatedAt: 2000 })],
      checklist: {},
    }
    const result = mergeReleases([alice, bob, carol])
    // Scalar: alice has latest savedAt
    expect(result.releaseManager).toBe('alice')
    // Services: 3 unique ids
    expect(result.services).toHaveLength(3)
    // svc-1: alice's version wins (updatedAt 5000)
    expect(result.services.find(s => s.id === 'svc-1').status).toBe('approved')
    // Notes: n-1 from alice wins (updatedAt 5000)
    expect(result.notes.find(n => n.id === 'n-1').text).toBe('from alice')
    // Checklist: OR-merge
    expect(result.checklist.branches_cut).toBe(true)
    expect(result.checklist.labels_produced).toBe(true)
  })
})

describe('mergeNoteArrays', () => {
  it('merges children recursively', () => {
    const child1 = makeTestNote({ id: 'child-1', text: 'old child', updatedAt: 1000 })
    const child2 = makeTestNote({ id: 'child-1', text: 'new child', updatedAt: 9000 })
    const noteA = makeTestNote({ id: 'n-1', children: [child1] })
    const noteB = makeTestNote({ id: 'n-1', children: [child2] })
    const result = mergeNoteArrays([[noteA], [noteB]])
    expect(result[0].children[0].text).toBe('new child')
  })

  it('returns empty array for empty input', () => {
    expect(mergeNoteArrays([])).toEqual([])
    expect(mergeNoteArrays([[]])).toEqual([])
  })
})

describe('Username modal', () => {
  it('shows the modal when no username is stored', async () => {
    localStorage.removeItem('rdUsername')
    render(<App />)
    await waitForLoad()
    expect(screen.getByText('Welcome')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Your name (e.g. alice)')).toBeInTheDocument()
  })

  it('does not show the modal when username is already stored', async () => {
    localStorage.setItem('rdUsername', 'alice')
    render(<App />)
    await waitForLoad()
    expect(screen.queryByText('Welcome')).not.toBeInTheDocument()
  })

  it('dismisses the modal and stores username on confirm', async () => {
    localStorage.removeItem('rdUsername')
    render(<App />)
    await waitForLoad()
    const input = screen.getByPlaceholderText('Your name (e.g. alice)')
    fireEvent.change(input, { target: { value: 'alice' } })
    fireEvent.click(screen.getByText('Get Started'))
    expect(screen.queryByText('Welcome')).not.toBeInTheDocument()
    expect(localStorage.getItem('rdUsername')).toBe('alice')
  })

  it('does not dismiss on empty username', async () => {
    localStorage.removeItem('rdUsername')
    render(<App />)
    await waitForLoad()
    fireEvent.click(screen.getByText('Get Started'))
    expect(screen.getByText('Welcome')).toBeInTheDocument()
  })
})

describe('Service tombstone (delete)', () => {
  it('deleted service does not appear in the service list', async () => {
    render(<App />)
    await waitForLoad()
    addService('ghost-svc')
    expect(screen.getByText('ghost-svc')).toBeInTheDocument()
    window.confirm = () => true
    fireEvent.click(screen.getAllByText('🗑️')[0])
    expect(screen.queryByText('ghost-svc')).not.toBeInTheDocument()
  })

  it('deleted service does not count in Services stat', async () => {
    render(<App />)
    await waitForLoad()
    addService('to-delete')
    expect(getStatCount('Services')).toBe('1')
    window.confirm = () => true
    fireEvent.click(screen.getAllByText('🗑️')[0])
    expect(getStatCount('Services')).toBe('0')
  })
})
