import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { groupApi } from '@/api'
import { AuthProvider } from '@/context/AuthContext'
import ProjectDetailLayout from './ProjectDetailLayout'
import RequirementChatPage from './RequirementChatPage'

vi.mock('@/pages/ProjectDetail/requirements', () => ({ PROJECT_REQUIREMENTS: [] }))
vi.mock('@/components/chat/ChatPanel', () => ({
  ChatPanel: ({ groupId }: { groupId: string }) => <div>当前群聊 {groupId}</div>,
}))

afterEach(() => vi.restoreAllMocks())

Object.defineProperty(globalThis, 'ResizeObserver', {
  writable: true,
  value: class ResizeObserverMock {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  },
})

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname}</output>
}

function renderLayout(initialEntry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route path="/app/projects/:projectId" element={<ProjectDetailLayout />}>
              <Route path="req-chat" element={<RequirementChatPage />} />
              <Route path="req-chat/:groupId" element={<RequirementChatPage />} />
              <Route path="*" element={null} />
            </Route>
          </Routes>
          <LocationProbe />
        </MemoryRouter>
      </QueryClientProvider>
    </AuthProvider>,
  )
}

describe('ProjectDetailLayout Diff Center navigation', () => {
  it('shows the delivery center entry and navigates with the project path', () => {
    renderLayout('/app/projects/project-1/overview')

    fireEvent.click(screen.getByText('交付中心'))

    expect(screen.getByTestId('location')).toHaveTextContent('/app/projects/project-1/diffs')
  })

  it('keeps delivery center selected on the detail route', () => {
    renderLayout('/app/projects/project-1/diffs/diff-1')

    expect(screen.getByRole('link', { name: /交付中心/ })).toHaveClass('is-active')
  })
})

describe('ProjectDetailLayout project chat entry', () => {
  const chatPath = '/app/projects/demo-project/req-chat'

  it('replaces loading with an empty state and creation action after the group list resolves empty', async () => {
    let finishLoading = () => {}
    vi.spyOn(groupApi, 'listByProject').mockImplementation(() => new Promise((resolve) => {
      finishLoading = () => resolve([])
    }))
    renderLayout(chatPath)

    expect(screen.getByText('正在进入项目群聊…')).toBeInTheDocument()
    finishLoading()

    const state = within(await screen.findByRole('region', { name: '项目群聊状态' }))
    expect(await state.findByText('暂无可用群聊')).toBeInTheDocument()
    expect(screen.queryByText('正在进入项目群聊…')).not.toBeInTheDocument()
    expect(screen.queryByText(/当前群聊 /)).not.toBeInTheDocument()
    fireEvent.click(state.getByRole('button', { name: '新建需求群' }))
    expect(await screen.findByRole('dialog', { name: '新建需求群' })).toBeInTheDocument()
  })

  it('shows a retry on group list failure and shows empty only after a successful retry', async () => {
    const listGroups = vi.spyOn(groupApi, 'listByProject')
      .mockRejectedValueOnce(new Error('群列表暂时不可用'))
      .mockResolvedValueOnce([])
    renderLayout(chatPath)

    expect(await screen.findByText('群聊加载失败，请重试')).toBeInTheDocument()
    expect(screen.queryByText('暂无可用群聊')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试加载群聊' }))
    expect(await screen.findByText('暂无可用群聊')).toBeInTheDocument()
    expect(listGroups).toHaveBeenCalledTimes(2)
  })

  it('opens the project main group when there are no requirement groups', async () => {
    vi.spyOn(groupApi, 'listByProject').mockResolvedValue([
      { id: 'main-group', projectId: 'demo-project', type: 'PROJECT_MAIN', title: '项目总群', status: 'ACTIVE' },
    ])
    renderLayout(chatPath)

    expect(await screen.findByText('当前群聊 main-group')).toBeInTheDocument()
    expect(screen.queryByText('暂无可用群聊')).not.toBeInTheDocument()
  })

  it('opens the first requirement group once when there is no project main group', async () => {
    vi.spyOn(groupApi, 'listByProject').mockResolvedValue([
      { id: 'requirement-only', projectId: 'demo-project', type: 'REQUIREMENT', title: '登录需求', status: 'ACTIVE' },
    ])
    renderLayout(chatPath)

    expect(await screen.findByText('当前群聊 requirement-only')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /登录需求/ })).toHaveLength(1)
  })

  it('shows the network pause until the first group query can complete', async () => {
    const listGroups = vi.spyOn(groupApi, 'listByProject').mockResolvedValue([])
    onlineManager.setOnline(false)
    try {
      renderLayout(chatPath)
      expect(screen.getByText('等待网络恢复后加载群聊…')).toBeInTheDocument()
      expect(screen.queryByText('暂无可用群聊')).not.toBeInTheDocument()
      expect(listGroups).not.toHaveBeenCalled()
      await act(async () => onlineManager.setOnline(true))
      expect(await screen.findByText('暂无可用群聊')).toBeInTheDocument()
    } finally {
      onlineManager.setOnline(true)
    }
  })

  it('opens the explicit group when the project list is empty', async () => {
    vi.spyOn(groupApi, 'listByProject').mockResolvedValue([])
    renderLayout(chatPath + '/linked-group')

    expect(await screen.findByText('当前群聊 linked-group')).toBeInTheDocument()
    expect(screen.queryByText('暂无可用群聊')).not.toBeInTheDocument()
  })
})
