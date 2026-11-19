import { http, HttpResponse } from 'msw'
import type { Message, MessageContextDirection, MessageContextPage } from '@/types'

/** HTTP-only mock of the proposed contract; components never read these records directly. */
export function createMessageContextHandler(getMessages: (projectId: string, groupId: string) => Message[] | undefined) {
  return http.get('/api/projects/:projectId/groups/:groupId/messages/:messageId/context', ({ params, request }) => {
    const projectId = String(params.projectId)
    const groupId = String(params.groupId)
    const messageId = String(params.messageId)
    const error = (code: string, message: string, status: number) => HttpResponse.json({ error: { code, message } }, { status })
    const source = getMessages(projectId, groupId)
    if (!source) return error('GROUP_NOT_FOUND', '群不存在或无权限', 404)
    const rows = [...source].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0) || a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))
    const anchor = rows.findIndex((row) => row.id === messageId && row.groupId === groupId)
    if (anchor < 0) return error('MESSAGE_NOT_FOUND', '消息不存在或不属于该群', 404)
    const url = new URL(request.url)
    const direction = url.searchParams.get('direction') ?? 'around'
    const limit = Number(url.searchParams.get('limit') ?? '30')
    if (!['around', 'before', 'after'].includes(direction) || !Number.isInteger(limit) || limit < 1 || limit > 100) {
      return error('INVALID_CONTEXT_QUERY', 'direction 或 limit 无效', 400)
    }
    let start: number
    let end: number
    const cursor = url.searchParams.get('cursor')
    if (direction === 'around' && !cursor) {
      start = Math.max(0, anchor - Math.floor(limit / 2))
      end = Math.min(rows.length, start + limit)
    } else {
      try {
        const decoded: unknown = JSON.parse(atob(cursor ?? ''))
        if (!decoded || typeof decoded !== 'object') throw new Error('Invalid cursor')
        const value = decoded as Record<string, unknown>
        if (value.projectId !== projectId || value.groupId !== groupId || value.anchorId !== messageId || value.direction !== direction) throw new Error('Cursor scope mismatch')
        const boundary = rows.findIndex((row) => row.id === value.boundaryId)
        if (boundary < 0 || direction === 'around') throw new Error('Invalid boundary')
        start = direction === 'before' ? Math.max(0, boundary - limit) : boundary + 1
        end = direction === 'before' ? boundary : Math.min(rows.length, start + limit)
      } catch { return error('INVALID_CONTEXT_CURSOR', '上下文游标无效', 400) }
    }
    const data = rows.slice(start, end)
    const encode = (side: MessageContextDirection, boundaryId: string) => btoa(JSON.stringify({ projectId, groupId, anchorId: messageId, direction: side, boundaryId }))
    const response: MessageContextPage = {
      anchorMessageId: messageId,
      messages: data,
      page: {
        beforeCursor: start > 0 && data.length ? encode('before', data[0].id) : null,
        afterCursor: end < rows.length && data.length ? encode('after', data[data.length - 1].id) : null,
        hasMoreBefore: start > 0 && data.length > 0,
        hasMoreAfter: end < rows.length && data.length > 0,
      },
    }
    return HttpResponse.json({ data: response })
  })
}
