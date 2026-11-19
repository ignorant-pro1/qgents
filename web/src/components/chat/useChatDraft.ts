import { useCallback, useLayoutEffect, useMemo, type SetStateAction } from 'react'
import { chatScopeKey, EMPTY_CHAT_DRAFT, useChatSessionStore } from '@/store/chatSessionStore'
import type { Mention } from '@/types'

export function useChatDraft(userId: string | undefined, projectId: string, groupId: string) {
  const epoch = useChatSessionStore((state) => state.epoch)
  useLayoutEffect(() => { useChatSessionStore.getState().activateUser(userId ?? null) }, [userId])
  const scope = useMemo(() => ({ userId: userId ?? '', projectId, groupId, epoch }), [userId, projectId, groupId, epoch])
  const key = chatScopeKey(scope)
  const value = useChatSessionStore((state) => state.userId === userId && state.epoch === epoch
    ? state.drafts[key] ?? EMPTY_CHAT_DRAFT : EMPTY_CHAT_DRAFT)
  const setDraft = useCallback((next: SetStateAction<string>) => {
    useChatSessionStore.getState().updateDraft(scope, (previous) => ({ ...previous,
      text: typeof next === 'function' ? next(previous.text) : next }))
  }, [scope])
  const setMentions = useCallback((next: SetStateAction<Mention[]>) => {
    useChatSessionStore.getState().updateDraft(scope, (previous) => ({ ...previous,
      mentions: typeof next === 'function' ? next(previous.mentions) : next }))
  }, [scope])
  const setReplyToId = useCallback((replyToId: string | null) => {
    useChatSessionStore.getState().updateDraft(scope, (previous) => ({ ...previous, replyToId }))
  }, [scope])
  return { scope, value, draft: value.text, mentions: value.mentions, setDraft, setMentions, setReplyToId }
}
