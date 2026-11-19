import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { groupApi } from '@/api'
import { invalidateChatSummary } from '@/realtime/chatSyncQueue'
import type { Group } from '@/types'

/** 仍是整群已读：仅在可见首屏成功/前台恢复成功时提交，失败不乐观清零。 */
export function useGroupReadAcknowledgement({ projectId, groupId, session, ready, initialReady = ready, onRead }: {
  projectId: string
  groupId: string
  session: object
  ready: boolean
  initialReady?: boolean
  onRead: (sequence: number | null) => void
}) {
  const queryClient = useQueryClient()
  const activeSession = useRef<object | null>(session)
  activeSession.current = session
  const readyRef = useRef(ready)
  readyRef.current = ready
  const cycle = useMemo(() => ({ session, started: false, queued: false, inFlight: null as Promise<void> | null }), [session])
  const [status, setStatus] = useState<{ session: object; pending: boolean; failed: boolean } | null>(null)
  useEffect(() => {
    activeSession.current = session
    return () => { if (activeSession.current === session) activeSession.current = null }
  }, [session])

  const markRead = useCallback((afterRecovery = false): Promise<void> => {
    if (!readyRef.current || !projectId || !groupId || document.visibilityState !== 'visible' || activeSession.current !== cycle.session) return Promise.resolve()
    if (cycle.inFlight) {
      if (!afterRecovery) return cycle.inFlight
      // 首载 read 可能早于本轮恢复查到的新消息。等它完成后仅追加一次确认。
      cycle.queued = true
      return cycle.inFlight.then(() => {
        if (!cycle.queued) return
        cycle.queued = false
        return markRead()
      })
    }
    cycle.started = true
    setStatus({ session, pending: true, failed: false })
    cycle.inFlight = (async () => {
      try {
        const result = await groupApi.markRead(projectId, groupId)
        if (activeSession.current !== session) return
        onRead(result.lastReadSequenceNo ?? null)
        const clearAcknowledged = (groups: Group[] | undefined) => groups?.map((group) => group.id === groupId && group.projectId === projectId
          ? { ...group, unreadCount: result.unreadCount, mentionedUnread: 0 } : group)
        queryClient.setQueryData<Group[]>(['groups', projectId], clearAcknowledged)
        queryClient.setQueryData<Group[]>(['chat', 'main-groups'], clearAcknowledged)
        setStatus({ session, pending: false, failed: false })
        invalidateChatSummary(queryClient, ['groups', projectId])
        invalidateChatSummary(queryClient, ['chat', 'main-groups'])
      } catch {
        if (activeSession.current === session) setStatus({ session, pending: false, failed: true })
      } finally { cycle.inFlight = null }
    })()
    return cycle.inFlight
  }, [projectId, groupId, session, cycle, onRead, queryClient])

  useEffect(() => {
    if (initialReady && !cycle.started && document.visibilityState === 'visible') void markRead()
  }, [initialReady, cycle, markRead])
  return { markRead, pending: status?.session === session && status.pending, failed: status?.session === session && status.failed }
}
