import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, createMemoryRouter, RouterProvider } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Group } from '@/types'
import { useAppUiStore } from '@/store/appUiStore'

const mountedPanels = vi.hoisted(() => [] as string[])
vi.mock('@/components/chat/ChatPanel', () => ({
  ChatPanel: ({ projectId, groupId }: { projectId: string; groupId: string }) => {
    mountedPanels.push(`${projectId}/${groupId}`)
    return <section aria-label="当前会话" data-project={projectId} data-group={groupId}>{projectId}/{groupId}</section>
  },
}))
import ChatWorkspacePage from './ChatWorkspacePage'

const teams = ['team-a', 'team-b', 'team-empty'].map((id) => ({ id, name: id, role: 'TEAM_OWNER' }))
const projects = {
  'team-a': ['project-a1', 'project-a2'],
  'team-b': ['project-b'],
  'team-empty': [],
}
const groups: Group[] = [
  { id: 'group-a1', projectId: 'project-a1', type: 'PROJECT_MAIN', title: '主群 A1', status: 'ACTIVE' },
  { id: 'group-a2', projectId: 'project-a2', type: 'PROJECT_MAIN', title: '主群 A2', status: 'ACTIVE' },
  { id: 'group-b', projectId: 'project-b', type: 'PROJECT_MAIN', title: '主群 A1', status: 'ACTIVE' },
  { id: 'requirement', projectId: 'project-a1', type: 'REQUIREMENT', title: '需求群', status: 'ACTIVE' },
  { id: 'hidden', projectId: 'hidden-project', type: 'PROJECT_MAIN', title: '不可见群', status: 'ACTIVE' },
]
const teamsPath = '/api/teams'
const projectsPath = '/api/teams/:teamId/projects'
const groupsPath = '/api/chat/main-groups'
const observed: URL[] = []
const clients: QueryClient[] = []
const routers: ReturnType<typeof createMemoryRouter>[] = []
const server = setupServer(
  http.get(teamsPath, () => HttpResponse.json({ data: teams })),
  http.get(projectsPath, ({ params }) => HttpResponse.json({ data: (projects[params.teamId as keyof typeof projects] ?? []).map((id) => ({ id, teamId: params.teamId, name: id })) })),
  http.get(groupsPath, () => HttpResponse.json({ data: groups })),
)
const link = (teamId = 'team-a', projectId = 'project-a2', groupId = 'group-a2') =>
  `/app/chat?${new URLSearchParams({ teamId, projectId, groupId })}`
const unavailable = () => HttpResponse.json({ error: { code: 'SERVICE_UNAVAILABLE', message: '临时失败' } }, { status: 503 })
function gate() {
  let release = () => {}
  const promise = new Promise<void>((resolve) => { release = resolve })
  return { promise, release }
}

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
  server.events.on('request:start', ({ request }) => observed.push(new URL(request.url)))
})
beforeEach(() => {
  observed.length = 0
  mountedPanels.length = 0
  useAppUiStore.setState({ currentTeamId: null, currentProjectId: null })
  onlineManager.setOnline(true)
})
afterEach(() => {
  cleanup()
  routers.splice(0).forEach((router) => router.dispose())
  clients.splice(0).forEach((client) => client.clear())
  onlineManager.setOnline(true)
  server.resetHandlers()
  vi.restoreAllMocks()
})
afterAll(() => server.close())

function renderWorkspace(url = link(), options: { browser?: boolean; entries?: string[] } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } } })
  clients.push(client)
  const routes = [{ path: '/app/chat', element: <ChatWorkspacePage /> }, { path: '/outside', element: <p>其他页面</p> }]
  if (options.browser) window.history.replaceState({}, '', url)
  const router = options.browser ? createBrowserRouter(routes) : createMemoryRouter(routes, { initialEntries: options.entries ?? [url] })
  routers.push(router)
  const view = render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return { ...view, router, client }
}
async function expectSession(groupId: string, projectId?: string) {
  await waitFor(() => expect(screen.getByRole('region', { name: '当前会话' })).toHaveAttribute('data-group', groupId))
  if (projectId) expect(screen.getByRole('region', { name: '当前会话' })).toHaveAttribute('data-project', projectId)
}
function expectSelection(router: ReturnType<typeof createMemoryRouter>, teamId: string, projectId: string, groupId: string) {
  const params = new URLSearchParams(router.state.location.search)
  expect([params.get('teamId'), params.get('projectId'), params.get('groupId')]).toEqual([teamId, projectId, groupId])
}

describe('CHAT-007C URL session selection', () => {
  it('opens an exact deep link and gives it precedence over the global team', async () => {
    useAppUiStore.getState().setCurrentTeam('team-b')
    const { router } = renderWorkspace(link())
    await expectSession('group-a2', 'project-a2')
    expectSelection(router, 'team-a', 'project-a2', 'group-a2')
    expect(mountedPanels.every((panel) => panel === 'project-a2/group-a2')).toBe(true)
    expect(useAppUiStore.getState().currentTeamId).toBe('team-a')
  })

  it('preserves the selection across a fresh QueryClient and UI-store reset at the same browser URL', async () => {
    const first = renderWorkspace(link(), { browser: true })
    await expectSession('group-a2')
    const url = window.location.pathname + window.location.search
    first.unmount()
    first.router.dispose()
    act(() => useAppUiStore.setState({ currentTeamId: null, currentProjectId: null }))
    const second = renderWorkspace(url, { browser: true })
    await expectSession('group-a2')
    expectSelection(second.router, 'team-a', 'project-a2', 'group-a2')
    expect(window.location.search).toBe(new URL(url, 'http://localhost').search)
  })

  it('pushes explicit selections and restores both back and forward history with the browser router', async () => {
    const { router } = renderWorkspace(link('team-a', 'project-a1', 'group-a1'), { browser: true })
    await expectSession('group-a1')
    const length = window.history.length
    fireEvent.click(screen.getByRole('button', { name: '打开群聊 主群 A2，项目 project-a2' }))
    await expectSession('group-a2')
    expect(window.history.length).toBe(length + 1)
    await act(async () => { await router.navigate(-1) })
    await expectSession('group-a1')
    expectSelection(router, 'team-a', 'project-a1', 'group-a1')
    await act(async () => { await router.navigate(1) })
    await expectSession('group-a2')
    expectSelection(router, 'team-a', 'project-a2', 'group-a2')
  })

  it('does not add a duplicate history entry when selecting the already selected session', async () => {
    const { router } = renderWorkspace(link(), { entries: ['/outside', link()] })
    await expectSession('group-a2')
    fireEvent.click(screen.getByRole('button', { name: '打开群聊 主群 A2，项目 project-a2' }))
    await act(async () => { await router.navigate(-1) })
    expect(await screen.findByText('其他页面')).toBeInTheDocument()
  })

  it('canonicalizes a missing selection with replace and preserves unrelated query parameters', async () => {
    const { router } = renderWorkspace('/app/chat?source=通知&filter=a%2Bb', { entries: ['/outside', '/app/chat?source=通知&filter=a%2Bb'] })
    await expectSession('group-a1')
    await waitFor(() => expectSelection(router, 'team-a', 'project-a1', 'group-a1'))
    expect(new URLSearchParams(router.state.location.search).get('source')).toBe('通知')
    expect(new URLSearchParams(router.state.location.search).get('filter')).toBe('a+b')
    await act(async () => { await router.navigate(-1) })
    expect(await screen.findByText('其他页面')).toBeInTheDocument()
  })

  it('preserves unrelated URL parameters when manually changing sessions', async () => {
    const { router } = renderWorkspace(`${link()}&source=shared&filter=a%2Bb`)
    await expectSession('group-a2')
    fireEvent.click(screen.getByRole('button', { name: '打开群聊 主群 A1，项目 project-a1' }))
    await expectSession('group-a1')
    expect(new URLSearchParams(router.state.location.search).get('source')).toBe('shared')
    expect(new URLSearchParams(router.state.location.search).get('filter')).toBe('a+b')
  })

  it.each([
    ['unknown group', 'project-a2', 'missing'],
    ['wrong project/group pair', 'project-a1', 'group-a2'],
    ['another team', 'project-b', 'group-b'],
    ['invisible project', 'hidden-project', 'hidden'],
    ['requirement group in a main-group workspace', 'project-a1', 'requirement'],
    ['encoded invalid value', 'project-a1', '不存在 / ? &'],
  ])('rejects %s without mounting that conversation', async (_name, projectId, groupId) => {
    const { router } = renderWorkspace(link('team-a', projectId, groupId))
    await expectSession('group-a1', 'project-a1')
    await waitFor(() => expectSelection(router, 'team-a', 'project-a1', 'group-a1'))
    expect(await screen.findByText('链接中的团队或会话不可用，已打开当前团队的可用群聊。')).toBeInTheDocument()
    expect(mountedPanels.every((panel) => panel === 'project-a1/group-a1')).toBe(true)
  })

  it.each(['teamId=team-a&projectId=project-a2', 'teamId=team-a&groupId=group-a2', 'teamId=&projectId=project-a2&groupId=group-a2'])('normalizes incomplete URL parameters: %s', async (search) => {
    const { router } = renderWorkspace(`/app/chat?${search}`)
    await expectSession('group-a1')
    await waitFor(() => expectSelection(router, 'team-a', 'project-a1', 'group-a1'))
  })

  it('never requests projects of an inaccessible URL team', async () => {
    useAppUiStore.getState().setCurrentTeam('team-b')
    const { router } = renderWorkspace(link('not-a-member', 'project-a2', 'group-a2'))
    await expectSession('group-b', 'project-b')
    await waitFor(() => expectSelection(router, 'team-b', 'project-b', 'group-b'))
    expect(observed.some((url) => url.pathname.includes('/teams/not-a-member/'))).toBe(false)
    expect(mountedPanels.every((panel) => panel === 'project-b/group-b')).toBe(true)
  })

  it('uses IDs rather than identical group titles when changing teams and navigating back', async () => {
    const { router } = renderWorkspace(link('team-a', 'project-a1', 'group-a1'))
    await expectSession('group-a1')
    await act(async () => { await router.navigate(link('team-b', 'project-b', 'group-b')) })
    await expectSession('group-b', 'project-b')
    await act(async () => { await router.navigate(-1) })
    await expectSession('group-a1', 'project-a1')
  })

  it('clears the old session when navigating to an empty team and restores it with back', async () => {
    const { router } = renderWorkspace(link())
    await expectSession('group-a2')
    await act(async () => { await router.navigate(link('team-empty', 'project-a2', 'group-a2')) })
    await waitFor(() => expect(screen.queryByRole('region', { name: '当前会话' })).not.toBeInTheDocument())
    await waitFor(() => expect(router.state.location.search).toBe('?teamId=team-empty'))
    expect(screen.getAllByText('暂无项目群聊').length).toBeGreaterThan(0)
    await act(async () => { await router.navigate(-1) })
    await expectSession('group-a2')
  })

  it('removes invalid selection parameters for an account with no teams', async () => {
    server.use(http.get(teamsPath, () => HttpResponse.json({ data: [] })))
    const { router } = renderWorkspace(link())
    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(screen.queryByRole('region', { name: '当前会话' })).not.toBeInTheDocument()
    expect(observed.some((url) => url.pathname.includes('/projects'))).toBe(false)
  })

  it.each([teamsPath, projectsPath, groupsPath])('retains a valid URL while %s is loading, then opens its exact target', async (path) => {
    const pending = gate()
    server.use(http.get(path, async () => { await pending.promise; return path === teamsPath ? HttpResponse.json({ data: teams }) : path === groupsPath
      ? HttpResponse.json({ data: groups }) : HttpResponse.json({ data: projects['team-a'].map((id) => ({ id, teamId: 'team-a', name: id })) }) }))
    const { router } = renderWorkspace(link())
    try {
      await waitFor(() => expect(observed.some((url) => path === projectsPath ? url.pathname.endsWith('/projects') : url.pathname === path)).toBe(true))
      expect(router.state.location.search).toBe(new URL(link(), 'http://localhost').search)
      expect(mountedPanels).toEqual([])
      pending.release()
      await expectSession('group-a2')
    } finally { pending.release() }
  })

  it.each([teamsPath, projectsPath, groupsPath])('keeps the deep link on first-load failure of %s and retries without an empty team request', async (path) => {
    let failing = true
    server.use(http.get(path, ({ params }) => failing ? unavailable() : path === teamsPath ? HttpResponse.json({ data: teams }) : path === groupsPath
      ? HttpResponse.json({ data: groups }) : HttpResponse.json({ data: (projects[params.teamId as keyof typeof projects] ?? []).map((id) => ({ id, name: id })) })))
    const { router } = renderWorkspace(link())
    await screen.findByText('会话列表加载失败，链接已保留')
    expect(router.state.location.search).toBe(new URL(link(), 'http://localhost').search)
    expect(mountedPanels).toEqual([])
    failing = false
    fireEvent.click(screen.getByRole('button', { name: '重试加载会话' }))
    await expectSession('group-a2')
    expectSelection(router, 'team-a', 'project-a2', 'group-a2')
    expect(observed.some((url) => url.pathname.includes('/teams//'))).toBe(false)
  })

  it.each([false, true])('preserves an offline deep link and restores it after online (browser router: %s)', async (browser) => {
    onlineManager.setOnline(false)
    const { router } = renderWorkspace(link(), { browser })
    expect(router.state.location.search).toBe(new URL(link(), 'http://localhost').search)
    expect(mountedPanels).toEqual([])
    act(() => onlineManager.setOnline(true))
    await expectSession('group-a2')
    expectSelection(router, 'team-a', 'project-a2', 'group-a2')
  })

  it('ignores a late old-team response after the URL switches to another team', async () => {
    const pending = gate()
    server.use(http.get('/api/teams/team-a/projects', async () => { await pending.promise; return HttpResponse.json({ data: projects['team-a'].map((id) => ({ id, name: id })) }) }))
    const { router } = renderWorkspace(link())
    try {
      await waitFor(() => expect(observed.some((url) => url.pathname === '/api/teams/team-a/projects')).toBe(true))
      await act(async () => { await router.navigate(link('team-b', 'project-b', 'group-b')) })
      await expectSession('group-b')
      pending.release()
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 30)) })
      await expectSession('group-b')
      expectSelection(router, 'team-b', 'project-b', 'group-b')
      expect(mountedPanels.some((panel) => panel.startsWith('project-a'))).toBe(false)
    } finally { pending.release() }
  })

  it('retains the selected group when searching or when the server reorders the aggregate', async () => {
    const { router, client } = renderWorkspace(link())
    await expectSession('group-a2')
    fireEvent.change(screen.getByPlaceholderText('搜索项目群聊'), { target: { value: '无匹配项' } })
    await expectSession('group-a2')
    server.use(http.get(groupsPath, () => HttpResponse.json({ data: [...groups].reverse() })))
    await act(async () => { await client.invalidateQueries({ queryKey: ['chat', 'main-groups'] }) })
    await expectSession('group-a2')
    expectSelection(router, 'team-a', 'project-a2', 'group-a2')
  })

  it('keeps a cached selection and URL when a background aggregate refresh fails', async () => {
    const { router, client } = renderWorkspace(link())
    await expectSession('group-a2')
    server.use(http.get(groupsPath, unavailable))
    await act(async () => { await client.invalidateQueries({ queryKey: ['chat', 'main-groups'] }) })
    await expectSession('group-a2')
    expectSelection(router, 'team-a', 'project-a2', 'group-a2')
    expect(screen.queryByText('会话列表加载失败，链接已保留')).not.toBeInTheDocument()
  })

  it('falls back explicitly if a selected group is removed by a later successful refresh', async () => {
    const { router, client } = renderWorkspace(link())
    await expectSession('group-a2')
    server.use(http.get(groupsPath, () => HttpResponse.json({ data: groups.filter((group) => group.id !== 'group-a2') })))
    await act(async () => { await client.invalidateQueries({ queryKey: ['chat', 'main-groups'] }) })
    await expectSession('group-a1')
    await waitFor(() => expectSelection(router, 'team-a', 'project-a1', 'group-a1'))
    expect(await screen.findByText('链接中的团队或会话不可用，已打开当前团队的可用群聊。')).toBeInTheDocument()
  })

  it.each(['Enter', ' '])('writes the selected IDs to the URL when the session is activated with %s', async (key) => {
    const { router } = renderWorkspace(link('team-a', 'project-a1', 'group-a1'))
    await expectSession('group-a1')
    const user = userEvent.setup()
    screen.getByRole('button', { name: '打开群聊 主群 A2，项目 project-a2' }).focus()
    await user.keyboard(key === 'Enter' ? '{Enter}' : ' ')
    await expectSession('group-a2')
    expectSelection(router, 'team-a', 'project-a2', 'group-a2')
  })
})

describe('CHAT-010A session navigation', () => {
  it('distinguishes same-name groups by project, searches project names and uses native keyboard activation', async () => {
    server.use(
      http.get(groupsPath, () => HttpResponse.json({ data: groups.map((group) => ({ ...group, title: '同名主群' })) })),
      http.get(projectsPath, () => HttpResponse.json({ data: [
        { id: 'project-a1', teamId: 'team-a', name: '研发项目' },
        { id: 'project-a2', teamId: 'team-a', name: '交付项目' },
      ] })),
    )
    const { router } = renderWorkspace(link('team-a', 'project-a1', 'group-a1'))
    await expectSession('group-a1')
    const first = screen.getByRole('button', { name: '打开群聊 同名主群，项目 研发项目' })
    const second = screen.getByRole('button', { name: '打开群聊 同名主群，项目 交付项目' })
    expect(first.tagName).toBe('BUTTON')
    expect(first).toHaveAttribute('aria-current', 'page')
    expect(second).not.toHaveAttribute('aria-current')
    expect(screen.getByText('项目：研发项目')).toBeInTheDocument()
    const user = userEvent.setup()
    first.focus()
    await user.tab()
    expect(second).toHaveFocus()
    await user.keyboard('{Enter}')
    await expectSession('group-a2')
    expect(second).toHaveFocus()
    expect(second).toHaveAttribute('aria-current', 'page')
    expectSelection(router, 'team-a', 'project-a2', 'group-a2')
    fireEvent.change(screen.getByPlaceholderText('搜索项目群聊'), { target: { value: '研发' } })
    expect(first).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /交付项目/ })).not.toBeInTheDocument()
    await expectSession('group-a2')
  })

  it('keeps server unread and mention badges visible for the selected conversation until acknowledgement', async () => {
    server.use(http.get(groupsPath, () => HttpResponse.json({ data: groups.map((group) => ({ ...group, unreadCount: 7, mentionedUnread: 3 })) })))
    renderWorkspace(link())
    await expectSession('group-a2')
    const selected = screen.getByRole('button', { name: '打开群聊 主群 A2，项目 project-a2' })
    expect(selected).toHaveTextContent('有人@你')
    expect(selected.querySelector('[title="7"]')).not.toBeNull()
  })
})
