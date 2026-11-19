import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Message, MessageContextPage } from '@/types'
import { ChatHistoryWindow } from './ChatHistoryWindow'
import { createMessageContextHandler } from '@/mocks/messageContext'
import { groupApi } from '@/api'

const PROJECT = 'context-project'
const GROUP = 'context-group'
const TARGET = 'context-30'
const path = '/api/projects/:projectId/groups/:groupId/messages/:messageId/context'
const requests: URL[] = []
const clients: QueryClient[] = []
const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')
const scrollIntoView = vi.fn()
function row(sequence: number, groupId = GROUP): Message {
  return { id: `context-${sequence}`, groupId, sequence, type: 'TEXT', content: { text: `上下文消息 ${sequence}` }, senderType: 'USER', senderId: 'other', createdAt: new Date(Date.UTC(2026, 9, 19, 8, 0, sequence)).toISOString() }
}
function context(messages = [row(29), row(30), row(31)], before: string | null = 'opaque-before', after: string | null = 'opaque-after'): MessageContextPage {
  return { anchorMessageId: TARGET, messages, page: { beforeCursor: before, afterCursor: after, hasMoreBefore: before !== null, hasMoreAfter: after !== null } }
}
function gate() { let release = () => {}; const promise = new Promise<void>((resolve) => { release = resolve }); return { promise, release: () => release() } }
function failing(status = 503) { return HttpResponse.json({ error: { code: 'TEST_FAILURE', message: '测试故障' } }, { status }) }
const server = setupServer(http.get(path, () => HttpResponse.json({ data: context() })))
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
  server.events.on('request:start', ({ request }) => requests.push(new URL(request.url)))
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView })
})
beforeEach(() => { requests.length = 0; scrollIntoView.mockClear() })
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); server.resetHandlers(); vi.restoreAllMocks() })
afterAll(() => { server.close(); if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScrollIntoView); else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView') })
function renderWindow(groupId = GROUP, messageId = TARGET) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } } })
  clients.push(client)
  const onReturn = vi.fn()
  const onLocated = vi.fn()
  const tree = (group: string, id: string) => <QueryClientProvider client={client}><AntdApp>
    <ChatHistoryWindow key={`${group}/${id}`} projectId={PROJECT} groupId={group} messageId={id} onReturn={onReturn} onLocated={onLocated}
      renderMessage={(message) => <div key={message.id} id={`msg-${message.id}`}>{(message.content as { text: string }).text}</div>} />
  </AntdApp></QueryClientProvider>
  const view = render(tree(groupId, messageId))
  return { ...view, client, onReturn, onLocated, changeTarget: (group: string, id: string) => view.rerender(tree(group, id)) }
}
function viewport() { return screen.getByLabelText('对话内容') }
function setGeometry() {
  const el = viewport()
  Object.defineProperties(el, { scrollHeight: { configurable: true, get: () => 1200 }, clientHeight: { configurable: true, get: () => 300 } })
  return el
}
function scrollTo(top: number) { const el = viewport(); el.scrollTop = top; fireEvent.scroll(el) }

describe('CHAT-003B historical context window', () => {
  it('shows initial loading, neighbouring messages and one target alignment without pagination buttons', async () => {
    const response = gate()
    server.use(http.get(path, async () => { await response.promise; return HttpResponse.json({ data: context() }) }))
    const { onLocated, onReturn } = renderWindow()
    try {
      expect(await screen.findByText('正在定位消息并加载上下文…')).toBeInTheDocument()
      expect(viewport()).toHaveAttribute('aria-busy', 'true')
      await act(async () => response.release())
      expect(await screen.findByText('上下文消息 29')).toBeInTheDocument()
      expect(screen.getByText('上下文消息 31')).toBeInTheDocument()
      expect(onLocated).toHaveBeenCalledTimes(1)
      expect(scrollIntoView).toHaveBeenCalledTimes(1)
      expect(viewport()).toHaveAttribute('tabindex', '0')
      expect(screen.queryByRole('button', { name: /加载更早|加载后续/ })).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '回到最新消息' }))
      expect(onReturn).toHaveBeenCalledTimes(1)
    } finally { response.release() }
  })

  it('scrolls to load both directions once, keeps the reading anchor and deduplicates overlapping responses', async () => {
    const beforeResponse = gate()
    let prepended = false
    server.use(http.get(path, async ({ request }) => {
      const direction = new URL(request.url).searchParams.get('direction')
      if (direction === 'before') { await beforeResponse.promise; prepended = true; return HttpResponse.json({ data: context([row(27), row(28), row(29)], null, 'bridge-after') }) }
      if (direction === 'after') return HttpResponse.json({ data: context([row(31), row(32)], 'bridge-before', null) })
      return HttpResponse.json({ data: context() })
    }))
    renderWindow()
    await screen.findByText('上下文消息 30')
    const el = setGeometry()
    const original = document.getElementById('msg-context-29')!
    vi.spyOn(original, 'getBoundingClientRect').mockImplementation(() => ({ top: (prepended ? 220 : 20) - el.scrollTop, bottom: (prepended ? 260 : 60) - el.scrollTop, left: 0, right: 200, width: 200, height: 40, x: 0, y: 0, toJSON: () => ({}) }))
    try {
      scrollTo(0)
      expect(await screen.findByText('正在加载更早消息…')).toBeInTheDocument()
      scrollTo(0); scrollTo(0)
      expect(requests.filter((url) => url.searchParams.get('direction') === 'before')).toHaveLength(1)
      await act(async () => beforeResponse.release())
      await screen.findByText('上下文消息 27')
      expect(el.scrollTop).toBe(200)
      expect(document.getElementById('msg-context-29')).toBe(original)
      scrollTo(400); scrollTo(900)
      await screen.findByText('上下文消息 32')
      expect(el.scrollTop).toBe(900)
      expect(screen.getAllByText('上下文消息 31')).toHaveLength(1)
      expect(screen.getByText('— 已到最早消息 —')).toBeInTheDocument()
      expect(screen.getByText('— 已到最新消息 —')).toBeInTheDocument()
      scrollTo(400); scrollTo(900)
      expect(requests.filter((url) => url.searchParams.get('direction') === 'after')).toHaveLength(1)
      expect(requests.find((url) => url.searchParams.get('direction') === 'before')?.searchParams.get('cursor')).toBe('opaque-before')
      expect(requests.find((url) => url.searchParams.get('direction') === 'after')?.searchParams.get('cursor')).toBe('opaque-after')
    } finally { beforeResponse.release() }
  })

  it.each(['before', 'after'] as const)('preserves readable content after a %s failure and retries the same cursor on a new edge entry', async (direction) => {
    let fail = true
    server.use(http.get(path, ({ request }) => {
      const side = new URL(request.url).searchParams.get('direction')
      if (side === direction) return fail ? failing() : HttpResponse.json({ data: context([row(direction === 'before' ? 28 : 32)], null, null) })
      return HttpResponse.json({ data: context() })
    }))
    renderWindow()
    await screen.findByText('上下文消息 30')
    const el = setGeometry()
    const original = document.getElementById('msg-context-30')
    const edge = direction === 'before' ? 0 : 900
    scrollTo(edge)
    expect(await screen.findByText(new RegExp(`${direction === 'before' ? '更早' : '后续'}消息加载失败`))).toBeInTheDocument()
    expect(document.getElementById('msg-context-30')).toBe(original)
    scrollTo(edge)
    expect(requests.filter((url) => url.searchParams.get('direction') === direction)).toHaveLength(1)
    fail = false
    scrollTo(400); scrollTo(edge)
    await screen.findByText(`上下文消息 ${direction === 'before' ? 28 : 32}`)
    const retried = requests.filter((url) => url.searchParams.get('direction') === direction)
    expect(retried).toHaveLength(2)
    expect(retried[0].searchParams.get('cursor')).toBe(retried[1].searchParams.get('cursor'))
    expect(el.scrollTop).toBe(edge)
  })

  it('loads and retries on wheel and keyboard intent when a short window has no scrollbar', async () => {
    let failAfter = true
    server.use(http.get(path, ({ request }) => {
      const direction = new URL(request.url).searchParams.get('direction')
      if (direction === 'before') return HttpResponse.json({ data: context([row(28)], null, 'bridge-after') })
      if (direction === 'after') return failAfter ? failing() : HttpResponse.json({ data: context([row(32)], 'bridge-before', null) })
      return HttpResponse.json({ data: context() })
    }))
    renderWindow()
    await screen.findByText('上下文消息 30')
    const el = viewport()
    Object.defineProperties(el, { scrollHeight: { configurable: true, get: () => 200 }, clientHeight: { configurable: true, get: () => 300 } })
    fireEvent.wheel(el, { deltaY: 100 })
    await screen.findByText(/后续消息加载失败/)
    failAfter = false
    fireEvent.wheel(el, { deltaY: 100 })
    await screen.findByText('上下文消息 32')
    fireEvent.keyDown(el, { key: 'ArrowUp' })
    await screen.findByText('上下文消息 28')
    expect(requests.filter((url) => url.searchParams.get('direction') === 'before')).toHaveLength(1)
    expect(requests.filter((url) => url.searchParams.get('direction') === 'after')).toHaveLength(2)
  })

  it('clearly isolates a target on an older server without claiming that all its history was loaded', async () => {
    server.use(http.get(path, () => failing(404)), http.get('/api/projects/:projectId/groups/:groupId/messages/:messageId', () => HttpResponse.json({ data: row(30) })))
    renderWindow()
    await screen.findByText('上下文消息 30')
    expect(screen.getByText(/当前服务尚未支持上下文查询/)).toBeInTheDocument()
    expect(screen.queryByText('— 已到最早消息 —')).not.toBeInTheDocument()
    expect(screen.queryByText('— 已到最新消息 —')).not.toBeInTheDocument()
    setGeometry(); scrollTo(0); scrollTo(900)
    expect(requests.filter((url) => url.pathname.endsWith('/context'))).toHaveLength(1)
  })

  it('keeps authorization failures visible and retries initial loading without falling back to single-message reads', async () => {
    let fail = true
    server.use(http.get(path, () => fail ? failing(403) : HttpResponse.json({ data: context() })))
    renderWindow()
    await screen.findByText(/消息定位失败/)
    expect(requests.some((url) => url.pathname.endsWith(`/${TARGET}`))).toBe(false)
    fail = false
    fireEvent.click(screen.getByRole('button', { name: '重试定位' }))
    await screen.findByText('上下文消息 30')
  })

  it('ignores a late old-group response after switching the context window', async () => {
    const response = gate()
    server.use(http.get(path, async ({ params }) => {
      if (params.groupId === GROUP) { await response.promise; return HttpResponse.json({ data: context() }) }
      return HttpResponse.json({ data: { ...context([row(40, 'other-group')], null, null), anchorMessageId: 'context-40' } })
    }))
    const { changeTarget, onLocated } = renderWindow()
    try {
      await waitFor(() => expect(requests).toHaveLength(1))
      changeTarget('other-group', 'context-40')
      await screen.findByText('上下文消息 40')
      await act(async () => response.release())
      expect(screen.queryByText('上下文消息 30')).not.toBeInTheDocument()
      expect(onLocated).toHaveBeenCalledTimes(1)
    } finally { response.release() }
  })

  it('retains the window and displays a retryable sync failure when background refetch fails', async () => {
    let fail = false
    server.use(http.get(path, () => fail ? failing() : HttpResponse.json({ data: context() })))
    const { client } = renderWindow()
    await screen.findByText('上下文消息 30')
    const original = document.getElementById('msg-context-30')
    fail = true
    await act(async () => { await client.refetchQueries({ queryKey: ['groups', PROJECT, GROUP, 'messages', 'context', TARGET], exact: true }) })
    expect(await screen.findByText('历史消息同步未成功，当前显示已有内容')).toBeInTheDocument()
    expect(document.getElementById('msg-context-30')).toBe(original)
    fail = false
    fireEvent.click(screen.getByRole('button', { name: '重试历史同步' }))
    await waitFor(() => expect(screen.queryByText('历史消息同步未成功，当前显示已有内容')).not.toBeInTheDocument())
  })

  it('uses the shared HTTP mock contract to page outwards with scoped opaque cursors', async () => {
    const source = Array.from({ length: 80 }, (_, index) => row(index + 1))
    server.use(createMessageContextHandler((projectId, groupId) => projectId === PROJECT && groupId === GROUP ? source : undefined))
    renderWindow()
    await screen.findByText('上下文消息 30')
    expect(screen.getByText('上下文消息 15')).toBeInTheDocument()
    expect(screen.getByText('上下文消息 44')).toBeInTheDocument()
    setGeometry(); scrollTo(0)
    await screen.findByText('上下文消息 1')
    scrollTo(400); scrollTo(900)
    await screen.findByText('上下文消息 74')
    const ids = [...viewport().querySelectorAll('[id^="msg-"]')].map((item) => item.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('rejects reusing a context cursor for another group, target or direction in the HTTP mock', async () => {
    const source = Array.from({ length: 80 }, (_, index) => row(index + 1))
    server.use(createMessageContextHandler((_projectId, groupId) => source.map((item) => ({ ...item, groupId }))))
    const initial = await groupApi.getMessageContext(PROJECT, GROUP, TARGET)
    const cursor = initial.page.beforeCursor!
    await expect(groupApi.getMessageContext(PROJECT, 'another-group', TARGET, { direction: 'before', cursor })).rejects.toMatchObject({ status: 400 })
    await expect(groupApi.getMessageContext(PROJECT, GROUP, 'context-31', { direction: 'before', cursor })).rejects.toMatchObject({ status: 400 })
    await expect(groupApi.getMessageContext(PROJECT, GROUP, TARGET, { direction: 'after', cursor })).rejects.toMatchObject({ status: 400 })
  })
})
