import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { onlineManager, QueryClient, QueryClientProvider, type InfiniteData } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { MemoryRouter } from 'react-router-dom'
import { StrictMode } from 'react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DiffMessageContent, Group, Message, Page, TaskStatusMessageContent, TextMessageContent } from '@/types'

const reconnectListeners = vi.hoisted(() => new Set<() => void>())
const realtimeState = vi.hoisted(() => ({ connectionStatus: 'connected' as 'connected' | 'connecting' | 'disconnected' | 'disabled' }))

vi.mock('@/context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'chat-reader', displayName: 'Reader' } }),
}))
vi.mock('@/hooks/agents', () => ({ useAgents: () => ({ data: { data: [] } }) }))
vi.mock('@/realtime', () => ({
  subscribeRealtimeReconnect: (listener: () => void) => {
    reconnectListeners.add(listener)
    return () => { reconnectListeners.delete(listener) }
  },
}))
vi.mock('@/realtime/useProjectTaskDomainEvents', () => ({ useProjectTaskSyncState: () => ({ pollingInterval: false, connectionStatus: realtimeState.connectionStatus }) }))
vi.mock('@/components/task-domain', () => ({ TaskTriggerModal: () => null }))
vi.mock('@/components/chat/ChatSettingsDrawer', () => ({ ChatSettingsDrawer: () => null }))
vi.mock('@/components/chat/AttachmentPreviewModal', () => ({ AttachmentPreviewModal: () => null }))
vi.mock('@/components/chat/ChatHeader', () => ({
  ChatHeader: ({ group }: { group?: Group }) => <h2>{group?.title ?? '聊天'}</h2>,
}))
vi.mock('@/components/chat/ChatMessageBubble', () => ({
  MessageBubble: ({ message }: { message: Message }) => {
    if (message.type === 'TASK_STATUS') {
      const content = message.content as TaskStatusMessageContent
      return <article aria-label={`任务状态 ${message.id}`}>
        <p>{content.message}</p>
        {content.plan?.steps?.map((step) => <p key={step.stepId}>{step.title}：{step.status}</p>)}
      </article>
    }
    if (message.type === 'DIFF') {
      const content = message.content as DiffMessageContent
      return <article aria-label={`Diff 交付 ${message.id}`}><p>{content.title}</p><p>{content.reviewStatus} / {content.deliveryStatus}</p></article>
    }
    return <span>{(message.content as TextMessageContent).text}</span>
  },
}))

import { ChatPanel } from './ChatPanel'
import { dispatchRealtimeFrame } from '@/realtime/realtimeClient'
import { queryClient as realtimeQueryClient } from '@/query'
import { invalidateProjectChatAfterCursorExpiry } from '@/realtime/queryInvalidation'

const PROJECT_ID = 'chat004-project'
const GROUP_A = 'chat004-a'
const GROUP_B = 'chat004-b'
const HISTORY_CURSOR = 'older-31'
const SYNC_WARNING = '同步未成功，当前显示已有消息'
const INPUT_PLACEHOLDER = '输入消息，@ 可提及成员或 Agent，回车发送…'
const clients: QueryClient[] = []
const observedRequests: URL[] = []

function message(id: string, sequence: number, text: string, groupId = GROUP_A): Message {
  return { id, groupId, sequence, type: 'TEXT', senderType: 'USER', senderId: 'other-user', senderName: 'Teammate', content: { text }, createdAt: new Date(Date.UTC(2026, 9, 19, 8, 0, sequence)).toISOString() }
}

const currentMessages = [message('message-31', 31, '已经加载的消息一'), message('message-32', 32, '已经加载的消息二')]
const otherMessages = [message('message-b', 11, '另一个群的消息', GROUP_B)]

function page(data: Message[], nextCursor: string | null = null): Page<Message> {
  return { data, page: { nextCursor, hasMore: nextCursor !== null } }
}

function unavailable() {
  return HttpResponse.json({ error: { code: 'SERVICE_UNAVAILABLE', message: '测试注入的临时错误' } }, { status: 503 })
}

function createGate() {
  let resolveGate = () => {}
  const promise = new Promise<void>((resolve) => { resolveGate = resolve })
  return { promise, release: () => resolveGate() }
}

const server = setupServer(
  http.get('/api/projects/:projectId/groups', () => HttpResponse.json({ data: [
    { id: GROUP_A, projectId: PROJECT_ID, type: 'REQUIREMENT', title: '群 A', status: 'ACTIVE' },
    { id: GROUP_B, projectId: PROJECT_ID, type: 'REQUIREMENT', title: '群 B', status: 'ACTIVE' },
  ] })),
  http.get('/api/projects/:projectId', () => HttpResponse.json({ data: { id: PROJECT_ID, teamId: 'chat004-team', name: 'Chat regression' } })),
  http.get('/api/projects/:projectId/groups/:groupId/members', () => HttpResponse.json({ data: [] })),
  http.get('/api/projects/:projectId/tasks', () => HttpResponse.json(page([]))),
  http.post('/api/projects/:projectId/groups/:groupId/read', ({ params }) => HttpResponse.json({ data: { groupId: params.groupId, lastReadSequenceNo: 32, unreadCount: 0 } })),
  http.get('/api/projects/:projectId/groups/:groupId/messages', ({ params }) => HttpResponse.json(page(params.groupId === GROUP_A ? currentMessages : otherMessages))),
  http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => HttpResponse.json(page([]))),
  http.get('/api/projects/:projectId/groups/:groupId/messages/:messageId/context', () => unavailableContext()),
)

function unavailableContext() { return HttpResponse.json({ error: { code: 'CONTEXT_NOT_AVAILABLE', message: '旧服务未提供上下文' } }, { status: 404 }) }

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
  server.events.on('request:start', ({ request }) => observedRequests.push(new URL(request.url)))
})
beforeEach(() => {
  realtimeState.connectionStatus = 'connected'
  observedRequests.length = 0
  reconnectListeners.clear()
  localStorage.clear()
})
afterEach(() => {
  cleanup()
  for (const client of clients.splice(0)) client.clear()
  onlineManager.setOnline(true)
  server.resetHandlers()
  vi.restoreAllMocks()
})
afterAll(() => server.close())

function messagesKey(groupId = GROUP_A) {
  return ['groups', PROJECT_ID, groupId, 'messages'] as const
}

function renderChat(groupId = GROUP_A, cached?: Page<Message> | InfiniteData<Page<Message>>, strict = false, state?: { mentionMessageId: string }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity }, mutations: { retry: false } } })
  clients.push(client)
  if (cached) client.setQueryData<InfiniteData<Page<Message>>>(messagesKey(groupId), 'pages' in cached ? cached : { pages: [cached], pageParams: [undefined] })
  const tree = (nextGroupId: string) => (
    <QueryClientProvider client={client}>
      <AntdApp>
        <MemoryRouter initialEntries={[{ pathname: '/', state }]}>
          <ChatPanel projectId={PROJECT_ID} groupId={nextGroupId} />
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>
  )
  const view = (nextGroupId: string) => strict ? <StrictMode>{tree(nextGroupId)}</StrictMode> : tree(nextGroupId)
  const result = render(view(groupId))
  return { ...result, client, switchGroup: (nextGroupId: string) => result.rerender(view(nextGroupId)) }
}

function emitMessageEvent(groupId = GROUP_A) {
  act(() => {
    window.dispatchEvent(new CustomEvent('qgents:message-event', { detail: { projectId: PROJECT_ID, groupId, eventType: 'message.created' } }))
  })
}

function returnToVisibleTab() {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  act(() => { document.dispatchEvent(new Event('visibilitychange')) })
}

function reconnectRealtime() {
  act(() => { for (const listener of reconnectListeners) listener() })
}

function historyRequests() {
  return observedRequests.filter((url) => url.pathname === `/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages`)
}

function incrementalRequests(groupId = GROUP_A) {
  return observedRequests.filter((url) => url.pathname === `/api/projects/${PROJECT_ID}/groups/${groupId}/messages/incremental`)
}

function cardRequests(messageId: string, groupId = GROUP_A) {
  return observedRequests.filter((url) => url.pathname === `/api/projects/${PROJECT_ID}/groups/${groupId}/messages/${messageId}`)
}

async function settleInitialMessages(expectedText = '已经加载的消息一') {
  await screen.findByText(expectedText)
  await waitFor(() => expect(incrementalRequests().length).toBeGreaterThan(0))
  await act(async () => { await new Promise<void>((resolve) => requestAnimationFrame(() => resolve())) })
}

function getViewport() {
  return screen.getByLabelText('对话内容')
}

describe('ChatPanel CHAT-009A read acknowledgement', () => {
  const readRequests = (groupId = GROUP_A) => observedRequests.filter((url) => url.pathname === `/api/projects/${PROJECT_ID}/groups/${groupId}/read`)

  it.each([false, true])('waits for a usable first page, reads once and sends no leave read (StrictMode %s)', async (strict) => {
    const response = createGate()
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', async () => { await response.promise; return HttpResponse.json(page(currentMessages)) }))
    const view = renderChat(GROUP_A, undefined, strict)
    try {
      await screen.findByText('正在加载群聊消息…')
      expect(readRequests()).toHaveLength(0)
      response.release()
      await settleInitialMessages()
      await waitFor(() => expect(readRequests()).toHaveLength(1))
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
      expect(readRequests()).toHaveLength(1)
      view.unmount()
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
      expect(readRequests()).toHaveLength(1)
    } finally { response.release() }
  })

  it('does not mark a failed first load read and acknowledges after the first-load retry succeeds', async () => {
    let failing = true
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', () => failing ? unavailable() : HttpResponse.json(page(currentMessages))))
    renderChat()
    await screen.findByText('消息加载失败')
    expect(readRequests()).toHaveLength(0)
    failing = false
    fireEvent.click(screen.getByRole('button', { name: '重试加载消息' }))
    await settleInitialMessages()
    await waitFor(() => expect(readRequests()).toHaveLength(1))
  })

  it('preserves unread summaries and reading context on read failure, then retries only read', async () => {
    let failing = true
    const serveGroups = () => HttpResponse.json({ data: [{ id: GROUP_A, projectId: PROJECT_ID, type: 'REQUIREMENT', title: '群 A', status: 'ACTIVE', unreadCount: failing ? 7 : 0, mentionedUnread: failing ? 3 : 0 }] })
    server.use(
      http.get('/api/projects/:projectId/groups', serveGroups),
      http.post('/api/projects/:projectId/groups/:groupId/read', () => failing ? unavailable() : HttpResponse.json({ data: { groupId: GROUP_A, lastReadSequenceNo: 32, unreadCount: 0 } })),
    )
    const { client } = renderChat()
    client.setQueryData<Group[]>(['chat', 'main-groups'], [{ id: GROUP_A, projectId: PROJECT_ID, type: 'REQUIREMENT', title: '群 A', status: 'ACTIVE', unreadCount: 7, mentionedUnread: 3 }])
    await settleInitialMessages()
    await screen.findByText('已读未确认，保留未读状态')
    expect(client.getQueryData<Group[]>(['groups', PROJECT_ID])?.[0]).toMatchObject({ unreadCount: 7, mentionedUnread: 3 })
    expect(client.getQueryData<Group[]>(['chat', 'main-groups'])?.[0]).toMatchObject({ unreadCount: 7, mentionedUnread: 3 })
    const viewport = getViewport()
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
    viewport.scrollTop = 120
    fireEvent.scroll(viewport)
    const row = document.getElementById('msg-message-31')
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '已读重试保留草稿' } })
    const before = { messages: historyRequests().length, increment: incrementalRequests().length }
    failing = false
    fireEvent.click(screen.getByRole('button', { name: '重试已读' }))
    await waitFor(() => expect(screen.queryByText('已读未确认，保留未读状态')).not.toBeInTheDocument())
    await waitFor(() => expect(client.getQueryData<Group[]>(['groups', PROJECT_ID])?.[0]).toMatchObject({ unreadCount: 0, mentionedUnread: 0 }))
    expect(readRequests()).toHaveLength(2)
    expect(historyRequests()).toHaveLength(before.messages)
    expect(incrementalRequests()).toHaveLength(before.increment)
    expect(document.getElementById('msg-message-31')).toBe(row)
    expect(viewport.scrollTop).toBe(120)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('已读重试保留草稿')
  })

  it('waits for foreground recovery before read and coalesces concurrent visible signals', async () => {
    renderChat()
    await settleInitialMessages()
    await waitFor(() => expect(readRequests()).toHaveLength(1))
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    const response = createGate()
    let active = false
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', async () => { active = true; await response.promise; return HttpResponse.json(page([])) }))
    try {
      act(() => { for (let index = 0; index < 5; index++) document.dispatchEvent(new Event('visibilitychange')) })
      await waitFor(() => expect(active).toBe(true))
      expect(readRequests()).toHaveLength(1)
      response.release()
      await waitFor(() => expect(readRequests()).toHaveLength(2))
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
      expect(readRequests()).toHaveLength(2)
    } finally { response.release() }
  })

  it('queues one acknowledgement after recovery when the first read is still in flight', async () => {
    const firstRead = createGate()
    let readCalls = 0
    server.use(http.post('/api/projects/:projectId/groups/:groupId/read', async () => {
      if (++readCalls === 1) await firstRead.promise
      return HttpResponse.json({ data: { groupId: GROUP_A, lastReadSequenceNo: readCalls === 1 ? 32 : 33, unreadCount: 0 } })
    }))
    renderChat()
    try {
      await settleInitialMessages()
      await waitFor(() => expect(readCalls).toBe(1))
      const before = incrementalRequests().length
      act(() => { for (let index = 0; index < 4; index++) document.dispatchEvent(new Event('visibilitychange')) })
      await waitFor(() => expect(incrementalRequests().length).toBeGreaterThan(before))
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
      expect(readCalls).toBe(1)
      firstRead.release()
      await waitFor(() => expect(readCalls).toBe(2))
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
      expect(readCalls).toBe(2)
    } finally { firstRead.release() }
  })

  it('does not acknowledge hidden or failed foreground recovery, and acknowledges after recovery succeeds', async () => {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const view = renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(readRequests()).toHaveLength(0)
    let failing = true
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => failing ? unavailable() : HttpResponse.json(page([]))),
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => failing ? unavailable() : HttpResponse.json(page(currentMessages))),
    )
    visibility.mockReturnValue('visible')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    await screen.findByText(SYNC_WARNING)
    expect(readRequests()).toHaveLength(0)
    failing = false
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    await waitFor(() => expect(readRequests()).toHaveLength(1))
    view.unmount()
    expect(readRequests()).toHaveLength(1)
  })

  it('does not acknowledge a coalesced foreground recovery when the accompanying query refresh fails', async () => {
    const { client } = renderChat()
    await settleInitialMessages()
    await waitFor(() => expect(readRequests()).toHaveLength(1))
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    vi.spyOn(realtimeQueryClient, 'invalidateQueries').mockImplementation((filters, options) => client.invalidateQueries(filters, options))
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', unavailable))
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
      dispatchRealtimeFrame({ type: 'message.updated', scope: 'project', projectId: PROJECT_ID,
        payload: { projectId: PROJECT_ID, groupId: GROUP_A, messageId: 'message-31' } })
    })
    await screen.findByText(SYNC_WARNING)
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(readRequests()).toHaveLength(1)
    expect(screen.getByText('已经加载的消息一')).toBeInTheDocument()
  })

  it('does not read an abandoned group whose first page completes after switching', async () => {
    const response = createGate()
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', async ({ params }) => {
      if (params.groupId === GROUP_A) await response.promise
      return HttpResponse.json(page(params.groupId === GROUP_A ? currentMessages : otherMessages))
    }))
    const { switchGroup } = renderChat()
    try {
      await screen.findByText('正在加载群聊消息…')
      switchGroup(GROUP_B)
      await screen.findByText('另一个群的消息')
      await waitFor(() => expect(readRequests(GROUP_B)).toHaveLength(1))
      response.release()
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
      expect(readRequests()).toHaveLength(0)
      expect(readRequests(GROUP_B)).toHaveLength(1)
    } finally { response.release() }
  })
})

describe('ChatPanel CHAT-010B connection feedback', () => {
  it('keeps REST failure and its retry visible even when the real-time connection is connected', async () => {
    const { switchGroup } = renderChat()
    await settleInitialMessages()
    realtimeState.connectionStatus = 'disconnected'
    switchGroup(GROUP_A)
    expect(screen.getByText('实时连接已断开，正在重连')).toBeInTheDocument()
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', unavailable))
    emitMessageEvent()
    await screen.findByText(SYNC_WARNING)
    realtimeState.connectionStatus = 'connected'
    switchGroup(GROUP_A)
    expect(screen.getByText('实时连接已建立')).toBeInTheDocument()
    expect(screen.getByText(SYNC_WARNING)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重试同步/ })).toBeInTheDocument()
    expect(screen.queryByText('消息已同步')).not.toBeInTheDocument()
    expect(screen.getByText('已经加载的消息一')).toBeInTheDocument()
  })

  it.each([
    ['connecting', '正在连接实时更新…'], ['disabled', '实时更新未启用'],
  ] as const)('provides accessible %s feedback without replacing the conversation', async (status, text) => {
    realtimeState.connectionStatus = status
    renderChat()
    await settleInitialMessages()
    expect(screen.getByText(text).closest('[role="status"]')).not.toBeNull()
    expect(screen.getByText('已经加载的消息一')).toBeInTheDocument()
  })
})

describe('ChatPanel CHAT-006 mention queue', () => {
  const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')
  afterEach(() => {
    if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScrollIntoView)
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
  })
  function mentioned(id: string, sequence: number): Message {
    return { ...message(id, sequence, `提及 ${sequence}`), mentions: [{ type: 'USER', id: 'chat-reader' }] }
  }

  it('locates three mentions in order and clears only one at a time without changing draft or mark-read', async () => {
    const scroll = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: function (this: HTMLElement) { scroll(this.id) } })
    let arrivals: Message[] = []
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', ({ request }) => {
      const after = Number(new URL(request.url).searchParams.get('afterSequence'))
      return HttpResponse.json(page(arrivals.filter((item) => (item.sequence ?? 0) > after)))
    }))
    const { client } = renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    const readBefore = observedRequests.filter((url) => url.pathname.endsWith('/read')).length
    const input = screen.getByPlaceholderText(INPUT_PLACEHOLDER)
    fireEvent.change(input, { target: { value: '等待逐条查看的草稿' } })
    arrivals = [mentioned('mention-33', 33), mentioned('mention-34', 34), mentioned('mention-35', 35)]
    emitMessageEvent()
    const first = await screen.findByRole('button', { name: /有人@你.*3 条/ })
    fireEvent.click(first)
    expect(scroll).toHaveBeenLastCalledWith('msg-mention-33')
    screen.getByRole('button', { name: /有人@你.*2 条/ }).focus()
    await userEvent.setup().keyboard('{Enter}')
    expect(scroll).toHaveBeenLastCalledWith('msg-mention-34')
    screen.getByRole('button', { name: /有人@你/ }).focus()
    await userEvent.setup().keyboard(' ')
    expect(scroll).toHaveBeenLastCalledWith('msg-mention-35')
    expect(screen.queryByRole('button', { name: /有人@你/ })).not.toBeInTheDocument()
    // 重复事件与对象替换不能让已处理的提示复活。
    act(() => client.setQueryData<InfiniteData<Page<Message>>>(messagesKey(), (old) => old ? { ...old, pages: old.pages.map((item) => ({ ...item, data: item.data.map((entry) => ({ ...entry })) })) } : old))
    emitMessageEvent()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(screen.queryByRole('button', { name: /有人@你/ })).not.toBeInTheDocument()
    arrivals.push(mentioned('mention-36', 36))
    emitMessageEvent()
    fireEvent.click(await screen.findByRole('button', { name: /有人@你/ }))
    expect(scroll).toHaveBeenLastCalledWith('msg-mention-36')
    expect(input).toHaveValue('等待逐条查看的草稿')
    expect(observedRequests.filter((url) => url.pathname.endsWith('/read'))).toHaveLength(readBefore)
  })

  it('handles a precise notification target without hiding the other loaded mentions', async () => {
    const scroll = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: function (this: HTMLElement) { scroll(this.id) } })
    const latest = [...currentMessages, mentioned('mention-33', 33), mentioned('mention-34', 34), mentioned('mention-35', 35)]
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page(latest))))
    renderChat(GROUP_A, page(latest), false, { mentionMessageId: 'mention-34' })
    await settleInitialMessages()
    expect(scroll).toHaveBeenCalledWith('msg-mention-34')
    fireEvent.click(await screen.findByRole('button', { name: /有人@你.*2 条/ }))
    expect(scroll).toHaveBeenLastCalledWith('msg-mention-33')
    fireEvent.click(screen.getByRole('button', { name: /有人@你.*1 条/ }))
    expect(scroll).toHaveBeenLastCalledWith('msg-mention-35')
    expect(screen.queryByRole('button', { name: /有人@你/ })).not.toBeInTheDocument()
  })

  it('loads an absent notification target through context and preserves pending latest mentions', async () => {
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
    const target = mentioned('old-mention-17', 17)
    const latest = [...currentMessages, mentioned('mention-33', 33), mentioned('mention-34', 34)]
    let failing = true
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page(latest))),
      http.get('/api/projects/:projectId/groups/:groupId/messages/:messageId/context', () => failing ? unavailable() : HttpResponse.json({ data: {
        anchorMessageId: target.id, messages: [target], page: { beforeCursor: null, afterCursor: null, hasMoreBefore: false, hasMoreAfter: false },
      } })),
    )
    const { client } = renderChat(GROUP_A, undefined, false, { mentionMessageId: target.id })
    await screen.findByRole('button', { name: /重试/ })
    const input = screen.getByPlaceholderText(INPUT_PLACEHOLDER)
    fireEvent.change(input, { target: { value: '旧提及定位失败也保留' } })
    failing = false
    fireEvent.click(screen.getByRole('button', { name: /重试/ }))
    await screen.findByText('提及 17')
    fireEvent.click(screen.getByRole('button', { name: '回到最新消息' }))
    expect(await screen.findByRole('button', { name: /有人@你.*2 条/ })).toBeInTheDocument()
    expect(input).toHaveValue('旧提及定位失败也保留')
    expect(client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data).some((item) => item.id === target.id)).toBe(false)
  })

  it('ignores an old group read response when deciding mentions in the next group', async () => {
    const oldRead = createGate()
    let arrival = false
    server.use(
      http.post('/api/projects/:projectId/groups/:groupId/read', async ({ params }) => {
        if (params.groupId === GROUP_A) await oldRead.promise
        return HttpResponse.json({ data: { groupId: params.groupId, lastReadSequenceNo: params.groupId === GROUP_A ? 100 : 11, unreadCount: 0 } })
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', ({ params, request }) => HttpResponse.json(page(
        params.groupId === GROUP_B && arrival && Number(new URL(request.url).searchParams.get('afterSequence')) < 12
          ? [{ ...mentioned('b-mention-12', 12), groupId: GROUP_B }] : [],
      ))),
    )
    const { switchGroup } = renderChat()
    try {
      await settleInitialMessages()
      switchGroup(GROUP_B)
      await screen.findByText('另一个群的消息')
      await waitFor(() => expect(incrementalRequests(GROUP_B).length).toBeGreaterThan(0))
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)); oldRead.release(); await new Promise((resolve) => setTimeout(resolve, 50)) })
      arrival = true
      emitMessageEvent(GROUP_B)
      expect(await screen.findByRole('button', { name: /有人@你.*1 条/ })).toBeInTheDocument()
    } finally { oldRead.release() }
  })
})

describe('ChatPanel CHAT-008 synchronization requests', () => {
  it('coalesces a burst of real message frames into one increment without refetching loaded pages', async () => {
    const { client } = renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    vi.spyOn(realtimeQueryClient, 'invalidateQueries').mockImplementation((filters, options) => client.invalidateQueries(filters, options))
    const before = { incremental: incrementalRequests().length, history: historyRequests().length }
    const gate = createGate()
    let started = 0
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', async () => {
      started++
      await gate.promise
      return HttpResponse.json(page([]))
    }))
    try {
      act(() => {
        for (let index = 0; index < 8; index++) dispatchRealtimeFrame({
          type: 'message.created', scope: 'project', projectId: PROJECT_ID,
          payload: { projectId: PROJECT_ID, groupId: GROUP_A, messageId: `burst-${index}`, sequence: 33 + index },
        })
      })
      await waitFor(() => expect(started).toBeGreaterThan(0))
      gate.release()
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 100)) })
      const counts = { incremental: incrementalRequests().length - before.incremental, history: historyRequests().length - before.history }
      console.info('CHAT008 message burst requests', counts)
      expect(counts).toEqual({ incremental: 1, history: 0 })
    } finally { gate.release() }
  })

  it('coalesces the DOM reconnect, reconnect subscription and visible signals into one recovery', async () => {
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page([taskStatusMessage(false), currentMessages[1]]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(true) })),
    )
    renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    const before = { incremental: incrementalRequests().length, cards: cardRequests('status-card-31').length }
    act(() => {
      window.dispatchEvent(new CustomEvent('qgents:realtime-reconnected'))
      for (const listener of reconnectListeners) listener()
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    const counts = { incremental: incrementalRequests().length - before.incremental, cards: cardRequests('status-card-31').length - before.cards }
    console.info('CHAT008 reconnect requests', counts)
    expect(counts).toEqual({ incremental: 1, cards: 1 })
  })

  it('coordinates Query online recovery with WebSocket recovery instead of refetching stale message pages separately', async () => {
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page([taskStatusMessage(false), currentMessages[1]]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(true) })),
    )
    const { client } = renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    await act(async () => { await client.invalidateQueries({ queryKey: messagesKey(), exact: true, refetchType: 'none' }) })
    const before = { history: historyRequests().length, incremental: incrementalRequests().length }
    act(() => {
      onlineManager.setOnline(false)
      onlineManager.setOnline(true)
      window.dispatchEvent(new CustomEvent('qgents:realtime-reconnected'))
      for (const listener of reconnectListeners) listener()
    })
    expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(historyRequests()).toHaveLength(before.history)
    expect(incrementalRequests()).toHaveLength(before.incremental + 1)
  })

  it('resumes an uncached paused first load when the network returns', async () => {
    onlineManager.setOnline(false)
    renderChat()
    expect(historyRequests()).toHaveLength(0)
    act(() => { onlineManager.setOnline(true) })
    await settleInitialMessages()
    expect(screen.queryByText('还没有消息，来说点什么吧')).not.toBeInTheDocument()
  })

  it('queues updates and another recovery received during an increment without losing messages or overlapping sync requests', async () => {
    let updated = false
    let active = 0
    let maximum = 0
    const gate = createGate()
    let held = false
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page([
        taskStatusMessage(updated), currentMessages[1], ...(updated ? [message('queued-33', 33, '在请求期间到达的消息')] : []),
      ]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(updated) })),
    )
    const { client } = renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    vi.spyOn(realtimeQueryClient, 'invalidateQueries').mockImplementation((filters, options) => client.invalidateQueries(filters, options))
    const originalRow = document.getElementById('msg-status-card-31')
    const viewport = getViewport()
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
    viewport.scrollTop = 120
    fireEvent.scroll(viewport)
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '串行同步期间的草稿' } })
    const before = { incremental: incrementalRequests().length, history: historyRequests().length }
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', async ({ request }) => {
      maximum = Math.max(maximum, ++active)
      const first = !held
      held = true
      if (first) await gate.promise
      active--
      const after = Number(new URL(request.url).searchParams.get('afterSequence'))
      return HttpResponse.json(page(!first && after < 33 ? [message('queued-33', 33, '在请求期间到达的消息')] : []))
    }))
    try {
      emitMessageEvent()
      await waitFor(() => expect(active).toBe(1))
      updated = true
      act(() => {
        for (let index = 0; index < 8; index++) dispatchRealtimeFrame({ type: 'message.updated', scope: 'project', projectId: PROJECT_ID,
          payload: { projectId: PROJECT_ID, groupId: GROUP_A, messageId: 'status-card-31' } })
        window.dispatchEvent(new CustomEvent('qgents:realtime-reconnected'))
        for (const listener of reconnectListeners) listener()
      })
      expect(historyRequests()).toHaveLength(before.history)
      expect(incrementalRequests()).toHaveLength(before.incremental + 1)
      gate.release()
      expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
      // The full-query response must contain the same new row as the incremental REST result.
      await waitFor(() => expect(incrementalRequests()).toHaveLength(before.incremental + 2))
      await waitFor(() => expect(historyRequests()).toHaveLength(before.history + 1))
      expect(await screen.findByText('在请求期间到达的消息')).toBeInTheDocument()
      expect(maximum).toBe(1)
      expect(document.getElementById('msg-status-card-31')).toBe(originalRow)
      expect(getViewport()).toBe(viewport)
      expect(viewport.scrollTop).toBe(120)
      expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('串行同步期间的草稿')
    } finally { gate.release() }
  })

  it('keeps all three delayed task compensations but deduplicates a burst', async () => {
    renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    const before = incrementalRequests().length
    act(() => {
      for (let index = 0; index < 8; index++) window.dispatchEvent(new CustomEvent('qgents:message-event', {
        detail: { projectId: PROJECT_ID, groupId: GROUP_A, eventType: 'task.updated' },
      }))
    })
    await waitFor(() => expect(incrementalRequests()).toHaveLength(before + 4), { timeout: 4000 })
  })

  it('discards delayed task work and queued recovery after switching groups', async () => {
    const { switchGroup } = renderChat()
    await settleInitialMessages()
    act(() => { window.dispatchEvent(new CustomEvent('qgents:message-event', {
      detail: { projectId: PROJECT_ID, groupId: GROUP_A, eventType: 'task.updated' },
    })) })
    switchGroup(GROUP_B)
    expect(await screen.findByText('另一个群的消息')).toBeInTheDocument()
    const oldRequests = incrementalRequests().length
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 600)) })
    expect(incrementalRequests()).toHaveLength(oldRequests)
    expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument()
  })

  it('waits for the current history page before refreshing an updated message without canceling pagination', async () => {
    const gate = createGate()
    let holdHistory = false
    let updated = false
    let historyStarted = false
    let canceled = false
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', async ({ request }) => {
      if (new URL(request.url).searchParams.get('cursor') === HISTORY_CURSOR) {
        if (holdHistory) {
          historyStarted = true
          request.signal.addEventListener('abort', () => { canceled = true })
          await gate.promise
        }
        return HttpResponse.json(page([message('older-29', 29, '保留正在加载的历史')]))
      }
      return HttpResponse.json(page([taskStatusMessage(updated), currentMessages[1]], HISTORY_CURSOR))
    }))
    const { client } = renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    vi.spyOn(realtimeQueryClient, 'invalidateQueries').mockImplementation((filters, options) => client.invalidateQueries(filters, options))
    holdHistory = true
    const originalRow = document.getElementById('msg-status-card-31')
    const viewport = getViewport()
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
    viewport.scrollTop = 20
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '分页中的草稿' } })
    fireEvent.scroll(viewport)
    try {
      await waitFor(() => expect(historyStarted).toBe(true))
      const count = historyRequests().length
      updated = true
      act(() => { dispatchRealtimeFrame({ type: 'message.updated', scope: 'project', projectId: PROJECT_ID,
        payload: { projectId: PROJECT_ID, groupId: GROUP_A, messageId: 'status-card-31' } }) })
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
      expect(historyRequests()).toHaveLength(count)
      expect(canceled).toBe(false)
      gate.release()
      expect(await screen.findByText('保留正在加载的历史')).toBeInTheDocument()
      expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
      expect(canceled).toBe(false)
      expect(document.getElementById('msg-status-card-31')).toBe(originalRow)
      expect(viewport.scrollTop).toBe(20)
      expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('分页中的草稿')
    } finally { gate.release() }
  })

  it('keeps an incremental error after a coalesced full-query success and retries through the incremental path', async () => {
    const { client } = renderChat()
    await settleInitialMessages()
    vi.spyOn(realtimeQueryClient, 'invalidateQueries').mockImplementation((filters, options) => client.invalidateQueries(filters, options))
    let failing = true
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => failing ? unavailable() : HttpResponse.json(page([]))))
    const before = historyRequests().length
    act(() => {
      emitMessageEvent()
      dispatchRealtimeFrame({ type: 'message.updated', scope: 'project', projectId: PROJECT_ID,
        payload: { projectId: PROJECT_ID, groupId: GROUP_A, messageId: 'message-31' } })
    })
    expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
    await waitFor(() => expect(historyRequests()).toHaveLength(before + 1))
    const retry = screen.getByRole('button', { name: /重试同步/ })
    await waitFor(() => expect(retry).not.toHaveClass('ant-btn-loading'))
    expect(screen.getByText(SYNC_WARNING)).toBeInTheDocument()
    failing = false
    fireEvent.click(retry)
    await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
    expect(historyRequests()).toHaveLength(before + 1)
  })
})

describe('ChatPanel CHAT-004 request failures', () => {
  it('offers a first-load retry without showing an empty conversation', async () => {
    let failing = true
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', () => failing ? unavailable() : HttpResponse.json(page(currentMessages))))
    renderChat()

    expect(await screen.findByText('消息加载失败')).toBeInTheDocument()
    expect(screen.queryByText('还没有消息，来说点什么吧')).not.toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '首载失败时写下的草稿' } })
    const requestsBeforeRetry = historyRequests().length
    failing = false
    fireEvent.click(screen.getByRole('button', { name: '重试加载消息' }))

    expect(await screen.findByText('已经加载的消息一')).toBeInTheDocument()
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('首载失败时写下的草稿')
    expect(screen.queryByText('消息加载失败')).not.toBeInTheDocument()
    expect(historyRequests().length).toBeGreaterThan(requestsBeforeRetry)
  })

  it('keeps message nodes, the draft and the reading anchor after a history failure, then retries the same cursor', async () => {
    let failingHistory = true
    let incrementalMessage: Message | null = null
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', ({ request }) => {
        if (new URL(request.url).searchParams.get('cursor') === HISTORY_CURSOR) return failingHistory ? unavailable() : HttpResponse.json(page([message('message-29', 29, '更早的历史消息')]))
        return HttpResponse.json(page(currentMessages, HISTORY_CURSOR))
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => {
        const received = incrementalMessage
        incrementalMessage = null
        return HttpResponse.json(page(received ? [received] : []))
      }),
    )
    renderChat()
    await settleInitialMessages()
    const viewport = getViewport()
    const originalRow = document.getElementById('msg-message-31')
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
    viewport.scrollTop = 20
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '保留我的历史阅读草稿' } })
    fireEvent.scroll(viewport)

    expect(await screen.findByText('更早消息加载失败')).toBeInTheDocument()
    expect(document.getElementById('msg-message-31')).toBe(originalRow)
    expect(originalRow).toBeInTheDocument()
    expect(getViewport()).toBe(viewport)
    expect(viewport.scrollTop).toBe(20)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('保留我的历史阅读草稿')
    expect(screen.queryByText('消息加载失败')).not.toBeInTheDocument()

    // A concurrent successful increment must not acknowledge the failed history request.
    incrementalMessage = message('message-33', 33, '分页失败后到达的新消息')
    emitMessageEvent()
    expect(await screen.findByText('分页失败后到达的新消息')).toBeInTheDocument()
    expect(screen.getByText('更早消息加载失败')).toBeInTheDocument()

    failingHistory = false
    fireEvent.click(screen.getByRole('button', { name: '重试加载历史' }))
    expect(await screen.findByText('更早的历史消息')).toBeInTheDocument()
    expect(document.getElementById('msg-message-31')).toBe(originalRow)
    expect(viewport.scrollTop).toBe(20)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('保留我的历史阅读草稿')
    expect(screen.queryByText('更早消息加载失败')).not.toBeInTheDocument()
    expect(historyRequests().map((url) => url.searchParams.get('cursor')).filter((cursor) => cursor !== null)).toEqual([HISTORY_CURSOR, HISTORY_CURSOR])
  })

  it('retains existing messages after a refetch failure and retries that query even after an incremental success', async () => {
    let failingRefetch = false
    let incrementalMessage: Message | null = null
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => failingRefetch ? unavailable() : HttpResponse.json(page(currentMessages))),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => {
        const received = incrementalMessage
        incrementalMessage = null
        return HttpResponse.json(page(received ? [received] : []))
      }),
    )
    const { client } = renderChat()
    await settleInitialMessages()
    const originalRow = document.getElementById('msg-message-31')
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '后台失败不能清空' } })
    failingRefetch = true
    await act(async () => { await client.invalidateQueries({ queryKey: messagesKey(), exact: true }) })

    expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
    expect(document.getElementById('msg-message-31')).toBe(originalRow)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('后台失败不能清空')
    incrementalMessage = message('message-33', 33, '同步警告期间到达的新消息')
    emitMessageEvent()
    expect(await screen.findByText('同步警告期间到达的新消息')).toBeInTheDocument()
    expect(screen.getByText(SYNC_WARNING)).toBeInTheDocument()

    const retrySync = screen.getByRole('button', { name: /重试同步/ })
    await waitFor(() => expect(retrySync).not.toHaveClass('ant-btn-loading'))
    const beforeRetry = historyRequests().length
    failingRefetch = false
    fireEvent.click(retrySync)
    await waitFor(() => expect(historyRequests()).toHaveLength(beforeRetry + 1))
    await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
    expect(document.getElementById('msg-message-31')).toBe(originalRow)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('后台失败不能清空')
  })

  it('distinguishes a cached empty conversation from an uncached first-load error', async () => {
    let failingRefetch = true
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', () => failingRefetch ? unavailable() : HttpResponse.json(page([]))))
    const { client } = renderChat(GROUP_A, page([]))
    expect(await screen.findByText('还没有消息，来说点什么吧')).toBeInTheDocument()
    await act(async () => { await client.invalidateQueries({ queryKey: messagesKey(), exact: true }) })

    expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
    expect(screen.getByText('还没有消息，来说点什么吧')).toBeInTheDocument()
    expect(screen.queryByText('消息加载失败')).not.toBeInTheDocument()
    failingRefetch = false
    fireEvent.click(screen.getByRole('button', { name: '重试同步' }))
    await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
    expect(screen.getByText('还没有消息，来说点什么吧')).toBeInTheDocument()
  })

  it('reports a direct incremental failure and clears it after a successful empty incremental retry', async () => {
    let failingIncrement = false
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => failingIncrement ? unavailable() : HttpResponse.json(page([]))))
    renderChat()
    await settleInitialMessages()
    const originalRow = document.getElementById('msg-message-31')
    failingIncrement = true
    emitMessageEvent()
    expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
    expect(document.getElementById('msg-message-31')).toBe(originalRow)
    const beforeRetry = incrementalRequests().length
    const historyCount = historyRequests().length

    failingIncrement = false
    fireEvent.click(screen.getByRole('button', { name: '重试同步' }))
    await waitFor(() => expect(incrementalRequests()).toHaveLength(beforeRetry + 1))
    await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
    expect(incrementalRequests().at(-1)?.searchParams.get('afterSequence')).toBe('32')
    expect(historyRequests()).toHaveLength(historyCount)
    expect(document.getElementById('msg-message-31')).toBe(originalRow)
  })

  it('retries reconciliation when both its incremental request and full-query fallback fail', async () => {
    let failingRequests = false
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => failingRequests ? unavailable() : HttpResponse.json(page(currentMessages))),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => failingRequests ? unavailable() : HttpResponse.json(page([]))),
    )
    renderChat()
    await settleInitialMessages()
    const originalRow = document.getElementById('msg-message-31')
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '校准失败仍保留的草稿' } })
    const beforeFallback = historyRequests().length
    failingRequests = true
    returnToVisibleTab()
    expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
    await waitFor(() => expect(historyRequests().length).toBeGreaterThan(beforeFallback))
    const retrySync = screen.getByRole('button', { name: /重试同步/ })
    await waitFor(() => expect(retrySync).not.toHaveClass('ant-btn-loading'))
    expect(document.getElementById('msg-message-31')).toBe(originalRow)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('校准失败仍保留的草稿')
    const beforeRetry = incrementalRequests().length

    failingRequests = false
    fireEvent.click(retrySync)
    await waitFor(() => expect(incrementalRequests().length).toBeGreaterThan(beforeRetry))
    await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
    expect(incrementalRequests().at(-1)?.searchParams.get('afterSequence')).toBe('32')
    expect(document.getElementById('msg-message-31')).toBe(originalRow)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('校准失败仍保留的草稿')
  })

  it('recovers from a reconciliation incremental failure through a successful full-query fallback', async () => {
    let failingIncrement = false
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => failingIncrement ? unavailable() : HttpResponse.json(page([]))))
    const { client } = renderChat()
    await settleInitialMessages()
    const beforeFallback = historyRequests().length
    failingIncrement = true
    returnToVisibleTab()

    await waitFor(() => expect(historyRequests().length).toBeGreaterThan(beforeFallback))
    await waitFor(() => expect(client.getQueryState(messagesKey())?.fetchStatus).toBe('idle'))
    expect(await screen.findByText('显示当前已加载消息')).toBeInTheDocument()
    expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument()
    expect(screen.getByText('已经加载的消息一')).toBeInTheDocument()
  })

  it('keeps an in-flight history page and its reading state when a concurrent reconciliation fallback fails', async () => {
    const historyResponse = createGate()
    const incrementalFailure = createGate()
    const fallbackResponse = createGate()
    const fallbackReturned = createGate()
    let failNextIncrement = false
    let failingFallback = false
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', async ({ request }) => {
        if (new URL(request.url).searchParams.get('cursor') === HISTORY_CURSOR) {
          await historyResponse.promise
          return HttpResponse.json(page([message('message-29', 29, '延迟加载的历史消息')]))
        }
        if (failingFallback) {
          await fallbackResponse.promise
          fallbackReturned.release()
          return unavailable()
        }
        return HttpResponse.json(page(currentMessages, HISTORY_CURSOR))
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => {
        if (failNextIncrement) {
          failNextIncrement = false
          incrementalFailure.release()
          return unavailable()
        }
        return HttpResponse.json(page([]))
      }),
    )
    renderChat()
    await settleInitialMessages()
    const originalRow = document.getElementById('msg-message-31')
    const viewport = getViewport()
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
    viewport.scrollTop = 20
    fireEvent.scroll(viewport)
    await waitFor(() => expect(historyRequests().some((url) => url.searchParams.get('cursor') === HISTORY_CURSOR)).toBe(true))
    const initialFullRequests = historyRequests().filter((url) => !url.searchParams.has('cursor')).length
    failingFallback = true
    failNextIncrement = true
    returnToVisibleTab()
    try {
      await act(async () => { await incrementalFailure.promise })
      act(() => { historyResponse.release() })

      expect(await screen.findByText('延迟加载的历史消息')).toBeInTheDocument()
      await waitFor(() => expect(historyRequests().filter((url) => !url.searchParams.has('cursor')).length).toBeGreaterThan(initialFullRequests))
      await act(async () => {
        fallbackResponse.release()
        await fallbackReturned.promise
      })
      expect(screen.getByText(SYNC_WARNING)).toBeInTheDocument()
      await waitFor(() => expect(screen.getByRole('button', { name: /重试同步/ })).not.toHaveClass('ant-btn-loading'))
      expect(document.getElementById('msg-message-31')).toBe(originalRow)
      expect(screen.queryByText('消息加载失败')).not.toBeInTheDocument()
    } finally {
      historyResponse.release()
      fallbackResponse.release()
    }
  })

  it('keeps a paused reconciliation fallback failed until its actual online HTTP response succeeds', async () => {
    const fallbackStarted = createGate()
    const fallbackResponse = createGate()
    let failNextIncrement = false
    let holdFallback = false
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', async () => {
        if (holdFallback) {
          fallbackStarted.release()
          await fallbackResponse.promise
        }
        return HttpResponse.json(page(currentMessages))
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => {
        if (failNextIncrement) {
          failNextIncrement = false
          return unavailable()
        }
        return HttpResponse.json(page([]))
      }),
    )
    const { client } = renderChat()
    await settleInitialMessages()
    const initialFullRequests = historyRequests().length
    const originalRow = document.getElementById('msg-message-31')
    holdFallback = true
    failNextIncrement = true
    act(() => { onlineManager.setOnline(false) })
    returnToVisibleTab()
    try {
      await waitFor(() => expect(client.getQueryCache().findAll({ queryKey: messagesKey() }).some((query) => query.state.fetchStatus === 'paused')).toBe(true))
      expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
      expect(historyRequests()).toHaveLength(initialFullRequests)
      act(() => { onlineManager.setOnline(true) })
      await act(async () => { await fallbackStarted.promise })
      expect(client.getQueryCache().findAll({ queryKey: messagesKey() }).some((query) => query.state.fetchStatus === 'fetching')).toBe(true)
      expect(screen.getByText(SYNC_WARNING)).toBeInTheDocument()
      expect(document.getElementById('msg-message-31')).toBe(originalRow)

      act(() => { fallbackResponse.release() })
      await waitFor(() => expect(client.getQueryCache().findAll({ queryKey: messagesKey() }).every((query) => query.state.fetchStatus === 'idle')).toBe(true))
      await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
      expect(document.getElementById('msg-message-31')).toBe(originalRow)
    } finally {
      fallbackResponse.release()
    }
  })

  it.each(['failure', 'success'] as const)('ignores an old group incremental %s after switching groups', async (outcome) => {
    let holdOldRequest = false
    let releaseOld: (() => void) | undefined
    let oldResponseSent: (() => void) | undefined
    const oldRequestGate = new Promise<void>((resolve) => { releaseOld = resolve })
    const oldResponseDone = new Promise<void>((resolve) => { oldResponseSent = resolve })
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', async ({ params }) => {
      if (params.groupId === GROUP_A && holdOldRequest) {
        await oldRequestGate
        oldResponseSent?.()
        return outcome === 'failure' ? unavailable() : HttpResponse.json(page([]))
      }
      if (params.groupId === GROUP_B && outcome === 'success') return unavailable()
      return HttpResponse.json(page([]))
    }))
    const { switchGroup } = renderChat()
    await settleInitialMessages()
    holdOldRequest = true
    const beforeRequest = incrementalRequests().length
    emitMessageEvent()
    await waitFor(() => expect(incrementalRequests().length).toBeGreaterThan(beforeRequest))
    switchGroup(GROUP_B)
    expect(await screen.findByText('另一个群的消息')).toBeInTheDocument()
    if (outcome === 'success') expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()

    await act(async () => {
      releaseOld?.()
      await oldResponseDone
    })
    // Wait through response parsing and React's queued notifications, not a time-based sleep.
    await act(async () => { await new Promise<void>((resolve) => requestAnimationFrame(() => resolve())) })
    expect(screen.getByText('另一个群的消息')).toBeInTheDocument()
    if (outcome === 'failure') expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument()
    else expect(screen.getByText(SYNC_WARNING)).toBeInTheDocument()
  })
})

function taskStatusMessage(updated: boolean, id = 'status-card-31', sequence = 31, groupId = GROUP_A): Message {
  const content: TaskStatusMessageContent = {
    taskId: `task-${id}`,
    status: updated ? 'SUCCEEDED' : 'RUNNING',
    message: updated ? `${id} 的正文已校准` : `${id} 的旧正文`,
    plan: { steps: [{ stepId: `step-${id}`, sequence: 1, title: `${id} 的执行步骤`, status: updated ? 'SUCCEEDED' : 'RUNNING' }] },
  }
  return { ...message(id, sequence, '', groupId), type: 'TASK_STATUS', senderType: 'AGENT', content }
}

describe('ChatPanel CHAT-003B history integration', () => {
  const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')
  beforeEach(() => Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() }))
  afterEach(() => { if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScrollIntoView); else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView') })

  it('keeps the draft through context loading and new real-time messages, then returns to the separate latest cache', async () => {
    const response = createGate()
    const read = createGate()
    const target = { ...message('context-target-17', 17, '历史目标 17'), mentions: [{ type: 'USER' as const, id: 'chat-reader' }] }
    let added = false
    let failIncrement = false
    const incoming = message('latest-103', 103, '定位期间收到的最新消息')
    server.use(
      http.post('/api/projects/:projectId/groups/:groupId/read', async () => { await read.promise; return HttpResponse.json({ data: { groupId: GROUP_A, lastReadSequenceNo: 102, unreadCount: 0 } }) }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', ({ request }) => failIncrement ? unavailable() : HttpResponse.json(page(added && Number(new URL(request.url).searchParams.get('afterSequence')) < 103 ? [incoming] : []))),
      http.get('/api/projects/:projectId/groups/:groupId/messages/:messageId/context', async () => {
        await response.promise
        return HttpResponse.json({ data: { anchorMessageId: target.id, messages: [message('context-16', 16, '历史前文 16'), target, message('context-18', 18, '历史后文 18')],
          page: { beforeCursor: null, afterCursor: null, hasMoreBefore: false, hasMoreAfter: false } } })
      }),
    )
    const latest = currentMessages.map((item, index) => ({ ...item, sequence: 101 + index }))
    const { client } = renderChat(GROUP_A, page(latest), false, { mentionMessageId: target.id })
    try {
      expect(await screen.findByText('正在定位消息并加载上下文…')).toBeInTheDocument()
      fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '定位加载过程中写下的草稿' } })
      await act(async () => response.release())
      await screen.findByText('历史目标 17')
      expect(screen.getByText('历史前文 16')).toBeInTheDocument()
      expect(screen.getByText('历史后文 18')).toBeInTheDocument()
      expect(screen.queryByText('已经加载的消息一')).not.toBeInTheDocument()
      const original = document.getElementById('msg-context-target-17')
      const el = getViewport()
      Object.defineProperties(el, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
      el.scrollTop = 200
      fireEvent.scroll(el)
      added = true
      emitMessageEvent()
      await waitFor(() => expect(client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages[0].data.some((item) => item.id === incoming.id)).toBe(true))
      expect(screen.getByText('有新消息，当前阅读位置保持')).toBeInTheDocument()
      expect(screen.queryByText('定位期间收到的最新消息')).not.toBeInTheDocument()
      expect(document.getElementById('msg-context-target-17')).toBe(original)
      expect(el.scrollTop).toBe(200)
      expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('定位加载过程中写下的草稿')
      failIncrement = true
      emitMessageEvent()
      expect(await screen.findByText('最新消息同步未成功，历史窗口继续可读')).toBeInTheDocument()
      expect(document.getElementById('msg-context-target-17')).toBe(original)
      failIncrement = false
      fireEvent.click(screen.getByRole('button', { name: /重试同步/ }))
      await waitFor(() => expect(screen.queryByText('最新消息同步未成功，历史窗口继续可读')).not.toBeInTheDocument())
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
      const contextKey = [...messagesKey(), 'context', target.id]
      await act(async () => { await client.invalidateQueries({ queryKey: contextKey, exact: true, refetchType: 'none' }) })
      const contextCount = observedRequests.filter((url) => url.pathname.endsWith('/context')).length
      act(() => {
        onlineManager.setOnline(false)
        onlineManager.setOnline(true)
        for (const listener of reconnectListeners) listener()
      })
      await waitFor(() => expect(observedRequests.filter((url) => url.pathname.endsWith('/context'))).toHaveLength(contextCount + 1))
      expect(document.getElementById('msg-context-target-17')).toBe(original)
      expect(el.scrollTop).toBe(200)
      fireEvent.click(screen.getByRole('button', { name: '回到最新消息' }))
      expect(await screen.findByText('定位期间收到的最新消息')).toBeInTheDocument()
      expect(screen.getByText('已经加载的消息一')).toBeInTheDocument()
      expect(screen.queryByText('历史目标 17')).not.toBeInTheDocument()
      expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('定位加载过程中写下的草稿')
      expect(client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages[0].data.some((item) => item.id === target.id)).toBe(false)
    } finally { response.release(); read.release() }
  })
})

describe('ChatPanel CHAT-003A message identity', () => {
  const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')
  let readResponse = createGate()
  beforeEach(() => {
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
    readResponse = createGate()
    // 已读成功会触发另一轮列表重取；本组单独验证通知/分页/发送的合并结果。
    server.use(http.post('/api/projects/:projectId/groups/:groupId/read', async () => {
      await readResponse.promise
      return HttpResponse.json({ data: { groupId: GROUP_A, lastReadSequenceNo: 32, unreadCount: 0 } })
    }))
  })
  afterEach(() => {
    readResponse.release()
    if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScrollIntoView)
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
  })

  function expectUniqueRows() {
    const ids = [...getViewport().querySelectorAll('[id^="msg-"]')].map((row) => row.id)
    expect(new Set(ids).size).toBe(ids.length)
    return ids
  }

  it.each(['notification-first', 'history-first', 'history-during-lookup'] as const)('shows a notified old message once when %s', async (order) => {
    const target = { ...message('mention-17', 17, '通知指向的旧消息'), mentions: [{ type: 'USER' as const, id: 'chat-reader' }] }
    const lookup = createGate()
    const lookupReturned = createGate()
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', ({ request }) => HttpResponse.json(
        new URL(request.url).searchParams.get('cursor') === HISTORY_CURSOR ? page([target]) : page(currentMessages, HISTORY_CURSOR),
      )),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/${target.id}`, async () => {
        if (order === 'history-during-lookup') await lookup.promise
        lookupReturned.release()
        return HttpResponse.json({ data: target })
      }),
    )
    const cached = order === 'history-first'
      ? { pages: [page(currentMessages, HISTORY_CURSOR), page([target])], pageParams: [undefined, HISTORY_CURSOR] }
      : page(currentMessages, HISTORY_CURSOR)
    const { client } = renderChat(GROUP_A, cached, false, { mentionMessageId: target.id })
    try {
      if (order === 'history-first') await settleInitialMessages()
      if (order === 'notification-first') await screen.findByText('通知指向的旧消息')
      if (order === 'history-during-lookup') await waitFor(() => expect(cardRequests(target.id)).toHaveLength(1))
      fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '定位翻页仍保留的草稿' } })
      if (order === 'history-during-lookup') {
        // A previously-started main-window history request can complete while context is loading.
        act(() => client.setQueryData<InfiniteData<Page<Message>>>(messagesKey(), (current) => current && ({
          ...current, pages: [...current.pages, page([target])], pageParams: [...current.pageParams, HISTORY_CURSOR],
        })))
        await act(async () => { lookup.release(); await lookupReturned.promise })
        await screen.findByText('通知指向的旧消息')
      }
      if (order !== 'history-first') {
        expect(screen.queryByText('已经加载的消息一')).not.toBeInTheDocument()
        expect(screen.getByText(/当前服务尚未支持上下文查询/)).toBeInTheDocument()
        expect(screen.getAllByText('通知指向的旧消息')).toHaveLength(1)
        expectUniqueRows()
        fireEvent.click(screen.getByRole('button', { name: '回到最新消息' }))
        await screen.findByText('已经加载的消息一')
      }
      const originalRow = document.getElementById('msg-message-31')
      if (order === 'notification-first') {
        const viewport = getViewport()
        Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
        viewport.scrollTop = 0
        fireEvent.scroll(viewport)
        await waitFor(() => expect(client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages).toHaveLength(2))
      }
      if (order === 'history-during-lookup') {
        const cachedRows = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data) ?? []
        expect(cachedRows.filter((item) => item.id === target.id)).toHaveLength(1)
      }
      expect(screen.getAllByText('通知指向的旧消息')).toHaveLength(1)
      expectUniqueRows()
      expect(document.getElementById('msg-message-31')).toBe(originalRow)
      expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('定位翻页仍保留的草稿')
      expect(client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pageParams).toEqual([undefined, HISTORY_CURSOR])
      if (order === 'history-first') expect(cardRequests(target.id)).toHaveLength(0)
    } finally { lookup.release() }
  })

  it('keeps different IDs with identical text, sorts them, and preserves the first cached copy across overlapping pages', async () => {
    renderChat(GROUP_A, {
      pages: [page([currentMessages[1], currentMessages[0], currentMessages[0]], HISTORY_CURSOR), page([
        message('message-31', 31, '历史重叠里的旧副本'),
        message('same-text-30', 30, '相同正文'), message('same-text-20', 20, '相同正文'),
      ])],
      pageParams: [undefined, HISTORY_CURSOR],
    })
    await settleInitialMessages()
    expect(screen.getAllByText('已经加载的消息一')).toHaveLength(1)
    expect(screen.queryByText('历史重叠里的旧副本')).not.toBeInTheDocument()
    expect(screen.getAllByText('相同正文')).toHaveLength(2)
    expect(expectUniqueRows()).toEqual(['msg-same-text-20', 'msg-same-text-30', 'msg-message-31', 'msg-message-32'])
  })

  it('updates a duplicated cached card in place after recovery instead of freezing the copy selected for display', async () => {
    server.use(http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(true) })))
    renderChat(GROUP_A, {
      pages: [page([taskStatusMessage(false), currentMessages[1]], HISTORY_CURSOR), page([taskStatusMessage(false)])],
      pageParams: [undefined, HISTORY_CURSOR],
    })
    await settleInitialMessages('status-card-31 的旧正文')
    const row = document.getElementById('msg-status-card-31')
    reconnectRealtime()
    expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
    expect(screen.getAllByText('status-card-31 的执行步骤：SUCCEEDED')).toHaveLength(1)
    expect(document.getElementById('msg-status-card-31')).toBe(row)
    expectUniqueRows()
  })

  it('preserves a newer cached card when an earlier calibration response arrives late', async () => {
    const response = createGate()
    const returned = createGate()
    server.use(http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, async () => {
      await response.promise
      returned.release()
      return HttpResponse.json({ data: taskStatusMessage(false) })
    }))
    const { client } = renderChat(GROUP_A, page([taskStatusMessage(false), currentMessages[1]]))
    await settleInitialMessages('status-card-31 的旧正文')
    reconnectRealtime()
    try {
      await waitFor(() => expect(cardRequests('status-card-31')).toHaveLength(1))
      act(() => client.setQueryData<InfiniteData<Page<Message>>>(messagesKey(), (current) => current && ({
        ...current,
        pages: current.pages.map((item) => ({ ...item, data: item.data.map((row) => row.id === 'status-card-31' ? taskStatusMessage(true) : row) })),
      })))
      expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
      await act(async () => { response.release(); await returned.promise })
      await act(async () => { await new Promise<void>((resolve) => requestAnimationFrame(() => resolve())) })
      expect(screen.getByText('status-card-31 的正文已校准')).toBeInTheDocument()
      expect(screen.queryByText('status-card-31 的旧正文')).not.toBeInTheDocument()
      expectUniqueRows()
    } finally { response.release() }
  })

  it('keeps one server message when an incremental response arrives before the send acknowledgement', async () => {
    const sent = { ...message('sent-33', 33, '发送和实时消息是同一条'), senderId: 'chat-reader' }
    const acknowledgement = createGate()
    const acknowledgementReturned = createGate()
    const refetch = createGate()
    let afterSend = false
    let emitted = false
    server.use(
      http.post('/api/projects/:projectId/groups/:groupId/messages', async () => {
        afterSend = true
        await acknowledgement.promise
        acknowledgementReturned.release()
        return HttpResponse.json({ data: { message: sent, task: null } })
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => HttpResponse.json(page(emitted ? [sent] : []))),
      http.get('/api/projects/:projectId/groups/:groupId/messages', async () => {
        if (afterSend) await refetch.promise
        return HttpResponse.json(page(afterSend ? [...currentMessages, sent] : currentMessages))
      }),
    )
    const { client } = renderChat(GROUP_A, page(currentMessages))
    await settleInitialMessages()
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '发送和实时消息是同一条' } })
    fireEvent.click(screen.getByRole('button', { name: /发送$/ }))
    try {
      await waitFor(() => expect(afterSend).toBe(true))
      emitted = true
      emitMessageEvent()
      await waitFor(() => expect(document.getElementById('msg-sent-33')).not.toBeNull())
      await act(async () => { acknowledgement.release(); await acknowledgementReturned.promise })
      await waitFor(() => expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue(''))
      expect(screen.getAllByText('发送和实时消息是同一条')).toHaveLength(1)
      expectUniqueRows()
      const cachedRows = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data) ?? []
      expect(cachedRows.filter((item) => item.id === sent.id)).toHaveLength(1)
      expect(cachedRows.some((item) => item.pending)).toBe(false)
    } finally { acknowledgement.release(); refetch.release() }
  })
})

describe('ChatPanel CHAT-001 recovery calibration', () => {
  it.each(['visible', 'reconnect'] as const)('refreshes an existing status card body and steps after %s recovery with an empty increment', async (source) => {
    let updated = false
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page([taskStatusMessage(updated), currentMessages[1]]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(updated) })),
    )
    const { client } = renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    const originalRow = document.getElementById('msg-status-card-31')
    const beforeRecovery = incrementalRequests().length
    updated = true
    if (source === 'visible') returnToVisibleTab()
    else reconnectRealtime()

    await waitFor(() => expect(incrementalRequests().length).toBeGreaterThan(beforeRecovery))
    expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
    expect(screen.getByText('status-card-31 的执行步骤：SUCCEEDED')).toBeInTheDocument()
    expect(document.getElementById('msg-status-card-31')).toBe(originalRow)
    const cached = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data) ?? []
    expect(cached.filter((item) => item.id === 'status-card-31')).toHaveLength(1)
    expect(cached.find((item) => item.id === 'status-card-31')).toMatchObject({ sequence: 31, createdAt: taskStatusMessage(false).createdAt, content: { status: 'SUCCEEDED' } })
  })

  it.each(['TASK_STATUS', 'DIFF'] as const)('calibrates a SYSTEM fallback %s card by message type', async (kind) => {
    let updated = false
    const cardId = `system-${kind.toLowerCase()}`
    const systemCard = (): Message => {
      if (kind === 'TASK_STATUS') return { ...taskStatusMessage(updated, cardId), senderType: 'SYSTEM' }
      const content: DiffMessageContent = { diffId: 'diff-system-card', title: updated ? `${cardId} 的正文已校准` : `${cardId} 的旧正文`, reviewStatus: updated ? 'ACCEPTED' : 'PENDING_CONFIRMATION', deliveryStatus: updated ? 'DELIVERED' : 'PENDING' }
      return { ...message(cardId, 31, ''), type: 'DIFF', senderType: 'SYSTEM', content }
    }
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page([systemCard(), currentMessages[1]]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/${cardId}`, () => HttpResponse.json({ data: systemCard() })),
    )
    const { client } = renderChat()
    await settleInitialMessages(`${cardId} 的旧正文`)
    const originalRow = document.getElementById(`msg-${cardId}`)
    updated = true
    reconnectRealtime()

    expect(await screen.findByText(`${cardId} 的正文已校准`)).toBeInTheDocument()
    if (kind === 'TASK_STATUS') expect(screen.getByText(`${cardId} 的执行步骤：SUCCEEDED`)).toBeInTheDocument()
    else expect(screen.getByText('ACCEPTED / DELIVERED')).toBeInTheDocument()
    expect(document.getElementById(`msg-${cardId}`)).toBe(originalRow)
    const rows = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data) ?? []
    expect(rows.find((item) => item.id === cardId)).toMatchObject({ type: kind, senderType: 'SYSTEM', sequence: 31 })
  })

  it('restores the visible row offset when an earlier card grows after the reader scrolls during calibration', async () => {
    let updated = false
    const cardResponse = createGate()
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page([taskStatusMessage(updated), currentMessages[1]]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, async () => {
        await cardResponse.promise
        return HttpResponse.json({ data: taskStatusMessage(updated) })
      }),
    )
    renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    const viewport = getViewport()
    const cardRow = document.getElementById('msg-status-card-31')
    const readingRow = document.getElementById('msg-message-32')
    if (!cardRow || !readingRow) throw new Error('Expected the card and its following reading row')
    // jsdom has no layout: model a 90 px expansion only after the new content commits.
    const addedHeight = () => cardRow.textContent?.includes('正文已校准') ? 90 : 0
    Object.defineProperties(viewport, { clientHeight: { configurable: true, get: () => 300 }, scrollHeight: { configurable: true, get: () => 1400 + addedHeight() } })
    vi.spyOn(viewport, 'getBoundingClientRect').mockImplementation(() => new DOMRect(0, 10, 600, 300))
    vi.spyOn(cardRow, 'getBoundingClientRect').mockImplementation(() => new DOMRect(0, 10 - viewport.scrollTop, 600, 100 + addedHeight()))
    vi.spyOn(readingRow, 'getBoundingClientRect').mockImplementation(() => new DOMRect(0, 150 + addedHeight() - viewport.scrollTop, 600, 50))
    viewport.scrollTop = 80
    fireEvent.scroll(viewport)
    updated = true
    reconnectRealtime()
    try {
      await waitFor(() => expect(cardRequests('status-card-31').length).toBeGreaterThan(0))
      viewport.scrollTop = 120
      fireEvent.scroll(viewport)
      const offsetBeforeCommit = readingRow.getBoundingClientRect().top - viewport.getBoundingClientRect().top
      act(() => { cardResponse.release() })

      expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
      await waitFor(() => expect(viewport.scrollTop).toBe(210))
      expect(readingRow.getBoundingClientRect().top - viewport.getBoundingClientRect().top).toBe(offsetBeforeCommit)
      expect(document.getElementById('msg-message-32')).toBe(readingRow)
    } finally {
      cardResponse.release()
    }
  })

  it('preserves and calibrates cards in the loaded history window even when new messages displace that whole window', async () => {
    let updated = false
    const latest = (newContent: boolean) => [taskStatusMessage(newContent), currentMessages[1]]
    const older = (newContent: boolean) => [taskStatusMessage(newContent, 'history-card-29', 29), message('history-text-30', 30, '原有历史文本')]
    const arrival = Array.from({ length: 90 }, (_, index) => message(`new-${100 + index}`, 100 + index, `恢复期间新增 ${100 + index}`))
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        if (cursor === HISTORY_CURSOR) return HttpResponse.json(page(older(updated)))
        if (!updated) return HttpResponse.json(page(latest(false), HISTORY_CURSOR))
        if (cursor === 'arrival-page-2') return HttpResponse.json(page(arrival.slice(30, 60), 'arrival-page-3'))
        if (cursor === 'arrival-page-3') return HttpResponse.json(page(arrival.slice(0, 30)))
        return HttpResponse.json(page(arrival.slice(60), 'arrival-page-2'))
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', ({ request }) => {
        const after = Number(new URL(request.url).searchParams.get('afterSequence'))
        return HttpResponse.json(page(updated ? arrival.filter((item) => (item.sequence ?? 0) > after) : []))
      }),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(updated) })),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/history-card-29`, () => HttpResponse.json({ data: taskStatusMessage(updated, 'history-card-29', 29) })),
    )
    const { client } = renderChat(GROUP_A, { pages: [page(latest(false), HISTORY_CURSOR), page(older(false))], pageParams: [undefined, HISTORY_CURSOR] })
    await settleInitialMessages('status-card-31 的旧正文')
    await waitFor(() => expect(client.isFetching({ queryKey: messagesKey(), exact: true })).toBe(0))
    const latestRow = document.getElementById('msg-status-card-31')
    const historyRow = document.getElementById('msg-history-card-29')
    const viewport = getViewport()
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 3000 }, clientHeight: { configurable: true, get: () => 300 } })
    viewport.scrollTop = 80
    fireEvent.scroll(viewport)
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '保留历史窗口和草稿' } })
    updated = true
    reconnectRealtime()

    expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
    expect(await screen.findByText('history-card-29 的正文已校准')).toBeInTheDocument()
    expect(screen.getByText('history-card-29 的执行步骤：SUCCEEDED')).toBeInTheDocument()
    expect(await screen.findByText('恢复期间新增 189')).toBeInTheDocument()
    expect(screen.getByText('原有历史文本')).toBeInTheDocument()
    expect(document.getElementById('msg-status-card-31')).toBe(latestRow)
    expect(document.getElementById('msg-history-card-29')).toBe(historyRow)
    expect(getViewport()).toBe(viewport)
    expect(viewport.scrollTop).toBe(80)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('保留历史窗口和草稿')
    const cached = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data) ?? []
    const ids = cached.map((item) => item.id)
    expect(ids).toEqual(expect.arrayContaining(['status-card-31', 'history-card-29', 'message-32', 'history-text-30', ...arrival.map((item) => item.id)]))
    expect(new Set(ids).size).toBe(ids.length)
  })

  it.each(['incremental', 'history fallback'] as const)('fills all 250 new messages through multiple %s pages while retaining a previously complete old window', async (mode) => {
    let recovering = false
    let failNextIncrement = false
    const lastFallbackPageStarted = createGate()
    const lastFallbackPageResponse = createGate()
    const arrival = Array.from({ length: 250 }, (_, index) => message(`backlog-${33 + index}`, 33 + index, `积压消息 ${33 + index}`))
    const completeHistory = () => [...arrival, currentMessages[1], taskStatusMessage(true)].sort((left, right) => (right.sequence ?? 0) - (left.sequence ?? 0))
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', async ({ request }) => {
        if (!recovering) return HttpResponse.json(page([taskStatusMessage(false), currentMessages[1]]))
        const params = new URL(request.url).searchParams
        const limit = Number(params.get('limit') ?? 30)
        if (!Number.isInteger(limit) || limit < 1 || limit > 100) return HttpResponse.json({ error: { code: 'INVALID_LIMIT', message: 'limit must be 1..100' } }, { status: 400 })
        const cursor = params.get('cursor')
        const start = cursor?.startsWith('restore-') ? Number(cursor.slice('restore-'.length)) : 0
        if (mode === 'history fallback' && start >= 200) {
          lastFallbackPageStarted.release()
          await lastFallbackPageResponse.promise
        }
        const rows = completeHistory()
        const next = start + limit
        return HttpResponse.json(page(rows.slice(start, next), next < rows.length ? `restore-${next}` : null))
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', ({ request }) => {
        if (failNextIncrement) {
          failNextIncrement = false
          return unavailable()
        }
        if (!recovering || mode === 'history fallback') return HttpResponse.json(page([]))
        const after = Number(new URL(request.url).searchParams.get('afterSequence'))
        const remaining = arrival.filter((item) => (item.sequence ?? 0) > after)
        const chunk = remaining.slice(0, 100)
        return HttpResponse.json(page(chunk, remaining.length > chunk.length ? String(chunk.at(-1)?.sequence) : null))
      }),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(recovering) })),
    )
    const { client } = renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    await waitFor(() => expect(client.isFetching({ queryKey: messagesKey(), exact: true })).toBe(0))
    const originalRow = document.getElementById('msg-status-card-31')
    const initialRows = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())
    expect(initialRows?.pages[0].page.hasMore).toBe(false)
    const requestsBeforeRecovery = historyRequests().length
    recovering = true
    failNextIncrement = mode === 'history fallback'
    reconnectRealtime()
    try {
      if (mode === 'history fallback') {
        await act(async () => { await lastFallbackPageStarted.promise })
        expect(screen.getByText(SYNC_WARNING)).toBeInTheDocument()
        expect(screen.getByText('status-card-31 的旧正文')).toBeInTheDocument()
        expect(screen.queryByText('积压消息 282')).not.toBeInTheDocument()
        expect(document.getElementById('msg-status-card-31')).toBe(originalRow)
        act(() => { lastFallbackPageResponse.release() })
      }

      expect(await screen.findByText('积压消息 282')).toBeInTheDocument()
      expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
      expect(document.getElementById('msg-status-card-31')).toBe(originalRow)
      const rows = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data) ?? []
      const ids = rows.map((item) => item.id)
      expect(ids).toEqual(expect.arrayContaining(['status-card-31', 'message-32', ...arrival.map((item) => item.id)]))
      expect(new Set(ids).size).toBe(ids.length)
      if (mode === 'history fallback') {
        const recoveryRequests = historyRequests().slice(requestsBeforeRecovery)
        expect(recoveryRequests.filter((url) => url.searchParams.has('cursor')).length).toBeGreaterThanOrEqual(2)
        expect(recoveryRequests.every((url) => Number(url.searchParams.get('limit')) <= 100)).toBe(true)
      } else {
        expect(incrementalRequests().some((url) => url.searchParams.get('afterSequence') === '132')).toBe(true)
        expect(incrementalRequests().some((url) => url.searchParams.get('afterSequence') === '232')).toBe(true)
      }
    } finally {
      lastFallbackPageResponse.release()
    }
  })

  it('keeps unknown old rows from a recovery boundary page out of the latest window until normal history pagination', async () => {
    let recovering = false
    let failNextIncrement = false
    const unseenOldMessage = message('unseen-history-29', 29, '只应通过历史翻页出现的旧消息')
    const arrival = message('new-message-33', 33, '边界恢复新增消息')
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', ({ request }) => {
        if (new URL(request.url).searchParams.get('cursor') === HISTORY_CURSOR) return HttpResponse.json(page([unseenOldMessage]))
        return HttpResponse.json(page(recovering ? [arrival, currentMessages[1], taskStatusMessage(true), unseenOldMessage] : [taskStatusMessage(false), currentMessages[1]], HISTORY_CURSOR))
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => {
        if (failNextIncrement) {
          failNextIncrement = false
          return unavailable()
        }
        return HttpResponse.json(page([]))
      }),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(recovering) })),
    )
    const { client } = renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    recovering = true
    failNextIncrement = true
    reconnectRealtime()
    expect(await screen.findByText('边界恢复新增消息')).toBeInTheDocument()
    expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
    expect(screen.queryByText('只应通过历史翻页出现的旧消息')).not.toBeInTheDocument()
    expect(client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages[0].page.nextCursor).toBe(HISTORY_CURSOR)

    const viewport = getViewport()
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 1800 }, clientHeight: { configurable: true, get: () => 300 } })
    viewport.scrollTop = 20
    fireEvent.scroll(viewport)
    expect(await screen.findByText('只应通过历史翻页出现的旧消息')).toBeInTheDocument()
    const rows = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data) ?? []
    const ids = rows.map((item) => item.id)
    expect(ids.filter((id) => id === unseenOldMessage.id)).toHaveLength(1)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('completes one cached initial increment after StrictMode discards the first queued mount', async () => {
    const readResponse = createGate()
    let incrementCalls = 0
    server.use(
      // Hold the unrelated mark-read invalidation so it cannot replace the seeded cache during this mount race.
      http.post('/api/projects/:projectId/groups/:groupId/read', async () => {
        await readResponse.promise
        return HttpResponse.json({ data: { groupId: GROUP_A, lastReadSequenceNo: 32, unreadCount: 0 } })
      }),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', async ({ request }) => {
      incrementCalls += 1
      const after = Number(new URL(request.url).searchParams.get('afterSequence'))
      return HttpResponse.json(page(after < 33 ? [message('strict-current-33', 33, 'StrictMode 缓存补查完成')] : []))
      }),
    )
    const { client } = renderChat(GROUP_A, page(currentMessages), true)
    try {
      expect(await screen.findByText('StrictMode 缓存补查完成')).toBeInTheDocument()
      expect(incrementCalls).toBe(1)
      expect(screen.getByText('已经加载的消息一')).toBeInTheDocument()
      await act(async () => { await new Promise<void>((resolve) => requestAnimationFrame(() => resolve())) })
      expect(screen.queryByText('应忽略已清理挂载的迟到补查')).not.toBeInTheDocument()
      const rows = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey())?.pages.flatMap((item) => item.data) ?? []
      expect(rows.some((item) => item.id === 'strict-current-33')).toBe(true)
      expect(rows.some((item) => item.id === 'strict-stale-34')).toBe(false)
    } finally {
      readResponse.release()
    }
  })

  it('preserves the old card after a calibration GET fails and retries calibration even when increments stay empty', async () => {
    let failingCard = false
    let updated = false
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page([taskStatusMessage(updated), currentMessages[1]]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => failingCard ? unavailable() : HttpResponse.json({ data: taskStatusMessage(updated) })),
    )
    renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    const originalRow = document.getElementById('msg-status-card-31')
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '校准错误期间的草稿' } })
    failingCard = true
    updated = true
    reconnectRealtime()
    expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
    expect(screen.getByText('status-card-31 的旧正文')).toBeInTheDocument()
    expect(document.getElementById('msg-status-card-31')).toBe(originalRow)
    const retrySync = screen.getByRole('button', { name: /重试同步/ })
    await waitFor(() => expect(retrySync).not.toHaveClass('ant-btn-loading'))
    const beforeRetry = cardRequests('status-card-31').length
    failingCard = false
    fireEvent.click(retrySync)

    expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
    expect(screen.getByText('status-card-31 的执行步骤：SUCCEEDED')).toBeInTheDocument()
    expect(cardRequests('status-card-31').length).toBeGreaterThan(beforeRetry)
    await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('校准错误期间的草稿')
    expect(document.getElementById('msg-status-card-31')).toBe(originalRow)
  })

  it.each(['failure', 'success'] as const)('isolates a late old-group card calibration %s from the current group', async (outcome) => {
    const oldCardResponse = createGate()
    const oldCardReturned = createGate()
    let holdOldCard = false
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', ({ params }) => HttpResponse.json(page(params.groupId === GROUP_A ? [taskStatusMessage(false)] : [taskStatusMessage(false, 'group-b-card', 11, GROUP_B), ...otherMessages]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, async () => {
        if (holdOldCard) {
          await oldCardResponse.promise
          oldCardReturned.release()
          return outcome === 'failure' ? unavailable() : HttpResponse.json({ data: taskStatusMessage(true) })
        }
        return HttpResponse.json({ data: taskStatusMessage(false) })
      }),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_B}/messages/group-b-card`, () => outcome === 'success' ? unavailable() : HttpResponse.json({ data: taskStatusMessage(false, 'group-b-card', 11, GROUP_B) })),
    )
    const { client, switchGroup } = renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    holdOldCard = true
    const beforeOldCalibration = cardRequests('status-card-31').length
    reconnectRealtime()
    try {
      await waitFor(() => expect(cardRequests('status-card-31').length).toBeGreaterThan(beforeOldCalibration))
      switchGroup(GROUP_B)
      expect(await screen.findByText('另一个群的消息')).toBeInTheDocument()
      const currentRow = document.getElementById('msg-group-b-card')
      if (outcome === 'success') {
        reconnectRealtime()
        expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
      }
      await act(async () => {
        oldCardResponse.release()
        await oldCardReturned.promise
      })
      await act(async () => { await new Promise<void>((resolve) => requestAnimationFrame(() => resolve())) })

      expect(document.getElementById('msg-group-b-card')).toBe(currentRow)
      expect(screen.queryByText('status-card-31 的正文已校准')).not.toBeInTheDocument()
      if (outcome === 'failure') expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument()
      else expect(screen.getByText(SYNC_WARNING)).toBeInTheDocument()
      const cached = client.getQueryData<InfiniteData<Page<Message>>>(messagesKey(GROUP_B))?.pages.flatMap((item) => item.data) ?? []
      expect(cached.some((item) => item.id === 'status-card-31')).toBe(false)
    } finally {
      oldCardResponse.release()
    }
  })
})

describe('ChatPanel CHAT-002 expired cursor recovery', () => {
  it('keeps content, draft and scroll position after recovery failure and retries recovery', async () => {
    const { client } = renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    vi.spyOn(realtimeQueryClient, 'invalidateQueries').mockImplementation((filters, options) => client.invalidateQueries(filters, options))
    const viewport = getViewport()
    const row = document.getElementById('msg-message-31')
    Object.defineProperties(viewport, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
    viewport.scrollTop = 120
    fireEvent.scroll(viewport)
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '游标恢复失败时保留' } })
    let failing = true
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => failing ? unavailable() : HttpResponse.json(page(currentMessages))),
      http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', () => failing ? unavailable() : HttpResponse.json(page([]))),
    )
    act(() => invalidateProjectChatAfterCursorExpiry(PROJECT_ID))
    expect(await screen.findByText(SYNC_WARNING)).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: /重试同步/ })
    await waitFor(() => expect(retry).not.toHaveClass('ant-btn-loading'))
    expect(getViewport()).toBe(viewport)
    expect(document.getElementById('msg-message-31')).toBe(row)
    expect(viewport.scrollTop).toBe(120)
    failing = false
    const before = incrementalRequests().length
    fireEvent.click(retry)
    await waitFor(() => expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument())
    expect(incrementalRequests().length).toBeGreaterThan(before)
    expect(document.getElementById('msg-message-31')).toBe(row)
    expect(viewport.scrollTop).toBe(120)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('游标恢复失败时保留')
  })

  it('recovers an existing card and group unread summary without a new message event or mark-read request', async () => {
    let changed = false
    server.use(
      http.get('/api/projects/:projectId/groups/:groupId/messages', () => HttpResponse.json(page([taskStatusMessage(false), currentMessages[1]]))),
      http.get(`/api/projects/${PROJECT_ID}/groups/${GROUP_A}/messages/status-card-31`, () => HttpResponse.json({ data: taskStatusMessage(changed) })),
      http.get('/api/projects/:projectId/groups', () => HttpResponse.json({ data: [
        { id: GROUP_A, projectId: PROJECT_ID, type: 'REQUIREMENT', title: '群 A', status: 'ACTIVE', unreadCount: changed ? 7 : 0, mentionedUnread: changed ? 3 : 0 },
      ] })),
    )
    const { client } = renderChat()
    await settleInitialMessages('status-card-31 的旧正文')
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    vi.spyOn(realtimeQueryClient, 'invalidateQueries').mockImplementation((filters, options) => client.invalidateQueries(filters, options))
    const before = { increments: incrementalRequests().length, reads: observedRequests.filter((url) => url.pathname.endsWith('/read')).length }
    fireEvent.change(screen.getByPlaceholderText(INPUT_PLACEHOLDER), { target: { value: '游标恢复时保留的草稿' } })
    const original = document.getElementById('msg-status-card-31')
    changed = true
    act(() => invalidateProjectChatAfterCursorExpiry(PROJECT_ID))
    expect(await screen.findByText('status-card-31 的正文已校准')).toBeInTheDocument()
    await waitFor(() => expect(client.getQueryData<Group[]>(['groups', PROJECT_ID])?.[0]).toMatchObject({ unreadCount: 7, mentionedUnread: 3 }))
    expect(incrementalRequests()).toHaveLength(before.increments + 1)
    expect(observedRequests.filter((url) => url.pathname.endsWith('/read'))).toHaveLength(before.reads)
    expect(document.getElementById('msg-status-card-31')).toBe(original)
    expect(screen.getByPlaceholderText(INPUT_PLACEHOLDER)).toHaveValue('游标恢复时保留的草稿')
  })

  it('ignores recovery for a different project', async () => {
    const { client } = renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    vi.spyOn(realtimeQueryClient, 'invalidateQueries').mockImplementation((filters, options) => client.invalidateQueries(filters, options))
    const before = incrementalRequests().length
    act(() => invalidateProjectChatAfterCursorExpiry('another-project'))
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    expect(incrementalRequests()).toHaveLength(before)
    expect(screen.queryByText(SYNC_WARNING)).not.toBeInTheDocument()
  })
})

describe('ChatPanel CHAT-005 new message hint', () => {
  function serveIncremental(tail: () => Message[]) {
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages/incremental', ({ request }) => {
      const after = Number(new URL(request.url).searchParams.get('afterSequence'))
      return HttpResponse.json(page(tail().filter((item) => item.sequence !== undefined && item.sequence > after)))
    }))
  }

  /** 模拟用户上翻阅读历史：内容高于视口且远离底部，贴底标志转为 false。 */
  function scrollUpToHistory(scrollTop = 120) {
    const viewport = getViewport()
    Object.defineProperties(viewport, {
      scrollHeight: { configurable: true, get: () => 1200 },
      clientHeight: { configurable: true, get: () => 300 },
    })
    viewport.scrollTop = scrollTop
    fireEvent.scroll(viewport)
    return viewport
  }

  async function flushFrames() {
    await act(async () => { await new Promise<void>((resolve) => requestAnimationFrame(() => resolve())) })
  }

  it('hints messages that arrive while reading history without moving the viewport', async () => {
    let tail: Message[] = []
    serveIncremental(() => tail)
    renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    const viewport = scrollUpToHistory()

    tail = [message('message-33', 33, '阅读历史时到达的消息')]
    emitMessageEvent()
    expect(await screen.findByText('阅读历史时到达的消息')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '1 条新消息，回到最新' })).toBeInTheDocument()
    // 不打扰阅读：位置保持不动
    expect(viewport.scrollTop).toBe(120)

    tail = [message('message-33', 33, '阅读历史时到达的消息'), message('message-34', 34, '第二条新消息')]
    emitMessageEvent()
    expect(await screen.findByText('第二条新消息')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '2 条新消息，回到最新' })).toBeInTheDocument()
    expect(viewport.scrollTop).toBe(120)
  })

  it('returns to the latest message after the hint is clicked and keeps following new ones', async () => {
    let tail: Message[] = []
    serveIncremental(() => tail)
    renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    const viewport = scrollUpToHistory()

    tail = [message('message-33', 33, '点击前到达的消息')]
    emitMessageEvent()
    const hint = await screen.findByRole('button', { name: '1 条新消息，回到最新' })
    fireEvent.click(hint)
    await flushFrames()

    expect(viewport.scrollTop).toBe(1200)
    expect(screen.queryByRole('button', { name: /条新消息，回到最新/ })).not.toBeInTheDocument()

    // 回到最新后恢复贴底：后续消息不再累积提示
    tail = [message('message-33', 33, '点击前到达的消息'), message('message-34', 34, '点击后到达的消息')]
    emitMessageEvent()
    expect(await screen.findByText('点击后到达的消息')).toBeInTheDocument()
    await flushFrames()
    expect(screen.queryByRole('button', { name: /条新消息，回到最新/ })).not.toBeInTheDocument()
    expect(viewport.scrollTop).toBe(1200)
  })

  it('does not hint messages that arrive while the reader is already at the bottom', async () => {
    let tail: Message[] = []
    serveIncremental(() => tail)
    renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })

    tail = [message('message-33', 33, '贴底时到达的消息')]
    emitMessageEvent()
    expect(await screen.findByText('贴底时到达的消息')).toBeInTheDocument()
    await flushFrames()
    expect(screen.queryByRole('button', { name: /条新消息，回到最新/ })).not.toBeInTheDocument()
  })

  it('does not count earlier history that is prepended while reading', async () => {
    server.use(http.get('/api/projects/:projectId/groups/:groupId/messages', ({ request }) =>
      new URL(request.url).searchParams.get('cursor') === HISTORY_CURSOR
        ? HttpResponse.json(page([message('message-29', 29, '更早的历史消息')]))
        : HttpResponse.json(page(currentMessages, HISTORY_CURSOR))))
    renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    scrollUpToHistory(20)

    expect(await screen.findByText('更早的历史消息')).toBeInTheDocument()
    await flushFrames()
    expect(screen.queryByRole('button', { name: /条新消息，回到最新/ })).not.toBeInTheDocument()
  })

  it('drops the local hint when the reader switches to another group', async () => {
    let tail: Message[] = []
    serveIncremental(() => tail)
    const { switchGroup } = renderChat()
    await settleInitialMessages()
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)) })
    scrollUpToHistory()

    tail = [message('message-33', 33, '切群前到达的消息')]
    emitMessageEvent()
    expect(await screen.findByRole('button', { name: '1 条新消息，回到最新' })).toBeInTheDocument()

    switchGroup(GROUP_B)
    expect(await screen.findByText('另一个群的消息')).toBeInTheDocument()
    await flushFrames()
    expect(screen.queryByRole('button', { name: /条新消息，回到最新/ })).not.toBeInTheDocument()
  })
})
