import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Button, Empty, Spin, Typography } from 'antd'
import { ApiError, groupApi } from '@/api'
import { formatApiError } from '@/utils/formatApiError'
import type { Message, MessageContextPage } from '@/types'
import type { ChatReadingAnchor } from '@/store/chatSessionStore'
import { readChatAnchor, restoreChatAnchor } from './useChatReadingPosition'

type Cursor = { direction: 'before' | 'after'; cursor: string } | undefined
type ContextPage = MessageContextPage & { unavailable?: boolean }
type Anchor = { id?: string; offset: number; scrollTop: number }

export function ChatHistoryWindow({ projectId, groupId, messageId, hasNewMessages = false, initialAnchor, onReadingChange, onReturn, onLocated, renderMessage }: {
  projectId: string
  groupId: string
  messageId: string
  hasNewMessages?: boolean
  initialAnchor?: ChatReadingAnchor
  onReadingChange?: (anchor: ChatReadingAnchor) => void
  onReturn: () => void
  onLocated: () => void
  renderMessage: (message: Message, previous: Message | undefined) => ReactNode
}) {
  const viewportRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const initializedRef = useRef(false)
  const notifiedRef = useRef(false)
  const requestRef = useRef(false)
  const anchorRef = useRef<Anchor | null>(null)
  const boundaries = useRef({ before: false, after: false })
  const gestureRetryAt = useRef({ before: 0, after: 0 })
  const readingCallbackRef = useRef(onReadingChange)
  readingCallbackRef.current = onReadingChange
  const [restoreNotice, setRestoreNotice] = useState('')
  const query = useInfiniteQuery({
    queryKey: ['groups', projectId, groupId, 'messages', 'context', messageId],
    initialPageParam: undefined as Cursor,
    queryFn: async ({ pageParam, signal }): Promise<ContextPage> => {
      try {
        return await groupApi.getMessageContext(projectId, groupId, messageId, { ...pageParam, signal })
      } catch (error) {
        // Older servers can still show the isolated target; never pretend its neighbours were loaded.
        if (pageParam || !(error instanceof ApiError) || ![404, 405, 501].includes(error.status)) throw error
        const target = await groupApi.getMessage(projectId, groupId, messageId, signal)
        if (target.id !== messageId || target.groupId !== groupId) throw new Error('目标消息不属于当前群聊')
        return { anchorMessageId: messageId, messages: [target], unavailable: true,
          page: { beforeCursor: null, afterCursor: null, hasMoreBefore: false, hasMoreAfter: false } }
      }
    },
    getPreviousPageParam: (first): Cursor => first.page.hasMoreBefore && first.page.beforeCursor
      ? { direction: 'before', cursor: first.page.beforeCursor } : undefined,
    getNextPageParam: (last): Cursor => last.page.hasMoreAfter && last.page.afterCursor
      ? { direction: 'after', cursor: last.page.afterCursor } : undefined,
    retry: false,
    // ChatPanel coordinates network recovery with message/card synchronization.
    refetchOnReconnect: false,
  })
  const rows = useMemo(() => {
    const byId = new Map<string, Message>()
    for (const item of query.data?.pages ?? []) {
      for (const row of item.messages) if (!byId.has(row.id)) byId.set(row.id, row)
    }
    return [...byId.values()].sort((a, b) => (a.sequence ?? Number.MAX_SAFE_INTEGER) - (b.sequence ?? Number.MAX_SAFE_INTEGER) ||
      new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime())
  }, [query.data])
  const unavailable = query.data?.pages.some((item) => item.unavailable) ?? false
  const hasContextCache = Boolean(query.data)
  const paginationPending = query.isFetchingPreviousPage || query.isFetchingNextPage

  function captureAnchor() {
    const el = viewportRef.current
    if (!el) return
    const top = el.getBoundingClientRect().top
    const row = [...el.querySelectorAll<HTMLElement>('[id^="msg-"]')].find((item) => item.getBoundingClientRect().bottom > top)
    anchorRef.current = { id: row?.id, offset: row ? row.getBoundingClientRect().top - top : 0, scrollTop: el.scrollTop }
  }

  async function load(direction: 'before' | 'after') {
    if (requestRef.current || query.isFetching || unavailable ||
      !(direction === 'before' ? query.hasPreviousPage : query.hasNextPage)) return
    requestRef.current = true
    captureAnchor()
    try {
      if (direction === 'before') await query.fetchPreviousPage({ cancelRefetch: false })
      else await query.fetchNextPage({ cancelRefetch: false })
    } catch {
      // Query keeps the directional error; aborted/unmounted requests must not escape the scroll handler.
    } finally { requestRef.current = false }
  }

  function onScroll() {
    const el = viewportRef.current
    if (!el || !initializedRef.current) return
    readingCallbackRef.current?.(readChatAnchor(el, false))
    if (requestRef.current) captureAnchor()
    const before = el.scrollTop <= 32
    const after = el.scrollHeight - el.scrollTop - el.clientHeight <= 32
    const hitBefore = before && !boundaries.current.before
    const hitAfter = after && !boundaries.current.after
    boundaries.current = { before, after }
    if (hitBefore && query.hasPreviousPage) void load('before')
    else if (hitAfter && query.hasNextPage) void load('after')
  }

  // A short window may have no scrollbar yet; wheel/keyboard intent can still reach its edges.
  function loadAtEdge(direction: 'before' | 'after') {
    const el = viewportRef.current
    if (!el || !initializedRef.current) return
    const atEdge = direction === 'before' ? el.scrollTop <= 32 : el.scrollHeight - el.scrollTop - el.clientHeight <= 32
    if (!atEdge) return
    const failed = direction === 'before' ? query.isFetchPreviousPageError : query.isFetchNextPageError
    // Explicit input can retry even when the short window cannot leave the edge.
    // Limit failed momentum-wheel retries; re-renders never trigger requests on their own.
    if (failed) {
      const now = Date.now()
      if (now - gestureRetryAt.current[direction] < 1000) return
      gestureRetryAt.current[direction] = now
    }
    void load(direction)
  }

  useLayoutEffect(() => {
    const el = viewportRef.current
    if (!el || !query.data) return
    if (!initializedRef.current) {
      initializedRef.current = true
      if (!initialAnchor || !restoreChatAnchor(el, initialAnchor)) {
        document.getElementById(`msg-${messageId}`)?.scrollIntoView({ block: 'center', behavior: 'instant' })
        if (initialAnchor) setRestoreNotice('上次阅读位置已不在缓存中，已定位到目标消息。')
      }
      readingCallbackRef.current?.(readChatAnchor(el, false))
      return
    }
    const anchor = anchorRef.current
    if (!anchor || paginationPending) return
    const row = anchor.id ? document.getElementById(anchor.id) : null
    if (row && el.contains(row)) el.scrollTop += row.getBoundingClientRect().top - el.getBoundingClientRect().top - anchor.offset
    else el.scrollTop = anchor.scrollTop
    anchorRef.current = null
  }, [query.data, messageId, paginationPending, initialAnchor])

  // Keep the currently visible row stable when images/cards resize in this historical window.
  useEffect(() => {
    const el = viewportRef.current
    const content = contentRef.current
    if (!el || !content) return
    let saved: Anchor | null = null
    const remember = () => {
      const top = el.getBoundingClientRect().top
      const row = [...el.querySelectorAll<HTMLElement>('[id^="msg-"]')].find((item) => item.getBoundingClientRect().bottom > top)
      saved = { id: row?.id, offset: row ? row.getBoundingClientRect().top - top : 0, scrollTop: el.scrollTop }
      readingCallbackRef.current?.(readChatAnchor(el, false))
    }
    const observer = new ResizeObserver(() => {
      const row = saved?.id ? document.getElementById(saved.id) : null
      if (row && saved && el.contains(row) && !requestRef.current) {
        el.scrollTop += row.getBoundingClientRect().top - el.getBoundingClientRect().top - saved.offset
      }
      remember()
    })
    remember()
    el.addEventListener('scroll', remember)
    observer.observe(content)
    return () => {
      observer.disconnect(); el.removeEventListener('scroll', remember)
      if (saved) readingCallbackRef.current?.({ messageId: saved.id?.slice(4), offset: saved.offset, scrollTop: saved.scrollTop, atBottom: false })
    }
  }, [hasContextCache])

  useEffect(() => {
    if (!query.data || notifiedRef.current) return
    notifiedRef.current = true
    onLocated()
  }, [query.data, onLocated])

  function boundary(direction: 'before' | 'after') {
    const earlier = direction === 'before'
    const pending = earlier ? query.isFetchingPreviousPage : query.isFetchingNextPage
    const failed = earlier ? query.isFetchPreviousPageError : query.isFetchNextPageError
    const more = earlier ? query.hasPreviousPage : query.hasNextPage
    const label = earlier ? '更早' : '后续'
    return <div className="chat-panel__history-status" role="status" aria-live="polite" aria-busy={pending}>
      {pending ? <><Spin size="small" /><span>正在加载{label}消息…</span></> :
        failed ? <Typography.Text type="warning">{label}消息加载失败，再次滚动到{earlier ? '顶部' : '底部'}重试</Typography.Text> :
        unavailable ? <Typography.Text type="secondary">{label}消息尚未加载</Typography.Text> :
        <Typography.Text type="secondary">{more ? `向${earlier ? '上' : '下'}滚动加载${label}消息` : earlier ? '— 已到最早消息 —' : '— 已到最新消息 —'}</Typography.Text>}
    </div>
  }

  return <>
    <div className="chat-panel__context-toolbar">
      <Typography.Text>正在查看历史消息</Typography.Text>
      {hasNewMessages ? <Typography.Text type="secondary">有新消息，当前阅读位置保持</Typography.Text> : null}
      <Button size="small" onClick={onReturn}>回到最新消息</Button>
    </div>
    {unavailable ? <div className="chat-panel__context-notice" role="status">当前服务尚未支持上下文查询，仅显示目标消息，周边消息尚未加载。</div> : null}
    {restoreNotice ? <Typography.Text role="status" type="secondary">{restoreNotice}</Typography.Text> : null}
    <div className="chat-panel__context-sync" role="status" aria-live="polite">
      {query.data && query.isRefetchError ? <>
        <Typography.Text type="warning">历史消息同步未成功，当前显示已有内容</Typography.Text>
        <Button type="link" size="small" loading={query.isRefetching} onClick={() => void query.refetch()}>重试历史同步</Button>
      </> : query.isRefetching ? <Typography.Text type="secondary">正在同步历史消息…</Typography.Text> : null}
    </div>
    <div ref={viewportRef} className="chat-panel__message-list" onScroll={onScroll}
      onWheel={(event) => { if (event.deltaY) loadAtEdge(event.deltaY < 0 ? 'before' : 'after') }}
      onKeyDown={(event) => {
        if (['ArrowUp', 'PageUp', 'Home'].includes(event.key)) loadAtEdge('before')
        if (['ArrowDown', 'PageDown', 'End'].includes(event.key)) loadAtEdge('after')
      }} tabIndex={0} aria-label="对话内容" aria-busy={query.isFetching}>
      {!query.data ? query.isPending ? <div className="chat-panel__message-state" role="status"><Spin /><span>正在定位消息并加载上下文…</span></div> :
        <Empty description={`消息定位失败：${formatApiError(query.error)}`}><Button onClick={() => void query.refetch()}>重试定位</Button></Empty> :
        <div ref={contentRef} className="chat-panel__message-content chat-panel__context-content">
          {boundary('before')}
          {rows.map((row, index) => renderMessage(row, rows[index - 1]))}
          {boundary('after')}
        </div>}
    </div>
  </>
}
