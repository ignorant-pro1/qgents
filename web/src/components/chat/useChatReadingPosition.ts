import { useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { chatScopeKey, useChatSessionStore, type ChatScope, type ChatReadingAnchor } from '@/store/chatSessionStore'

export function readChatAnchor(el: HTMLElement, atBottom: boolean): ChatReadingAnchor {
  const top = el.getBoundingClientRect().top
  const row = [...el.querySelectorAll<HTMLElement>('[id^="msg-"]')].find((item) => item.getBoundingClientRect().bottom > top)
  return { messageId: row?.id.slice(4), offset: row ? row.getBoundingClientRect().top - top : 0, scrollTop: el.scrollTop, atBottom }
}

export function restoreChatAnchor(el: HTMLElement, anchor: ChatReadingAnchor): boolean {
  const row = anchor.messageId ? [...el.querySelectorAll<HTMLElement>('[id^="msg-"]')].find((item) => item.id === `msg-${anchor.messageId}`) : undefined
  if (!row) return false
  el.scrollTop += row.getBoundingClientRect().top - el.getBoundingClientRect().top - anchor.offset
  return true
}

export function useChatReadingPosition({ scope, viewportRef, contentRef, ready, data, enabled, stickRef, resetToken = 0, ignoreSaved = false }: {
  scope: ChatScope
  viewportRef: RefObject<HTMLDivElement | null>
  contentRef: RefObject<HTMLDivElement | null>
  ready: boolean
  data: unknown
  enabled: boolean
  stickRef: RefObject<boolean>
  resetToken?: number
  ignoreSaved?: boolean
}) {
  const stateRef = useRef<{ key: string; restored: boolean; anchor?: ChatReadingAnchor; saved?: ChatReadingAnchor }>({ key: '', restored: false })
  const [notice, setNotice] = useState('')
  const key = `${chatScopeKey(scope)}/${scope.epoch}/${resetToken}`
  useLayoutEffect(() => {
    if (!enabled) { stateRef.current.key = ''; return }
    if (stateRef.current.key !== key) {
      const stored = useChatSessionStore.getState().positions[chatScopeKey(scope)]
      const saved = !ignoreSaved && !stored?.contextMessageId ? stored : undefined
      stateRef.current = { key, restored: false, saved }
      stickRef.current = saved?.atBottom ?? true
      setNotice('')
    }
    const state = stateRef.current
    const el = viewportRef.current
    if (!ready || !el) return
    if (!state.restored) {
      if (state.saved && !state.saved.atBottom) {
        if (!restoreChatAnchor(el, state.saved)) {
          stickRef.current = true
          el.scrollTop = el.scrollHeight
          setNotice('上次阅读的消息已不在缓存中，已回到最新消息。')
        }
      } else if (stickRef.current) el.scrollTop = el.scrollHeight
      state.restored = true
    }
    const remember = () => {
      state.anchor = readChatAnchor(el, stickRef.current)
      useChatSessionStore.getState().savePosition(scope, state.anchor)
    }
    const onScroll = () => {
      stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight <= 80
      remember()
    }
    const observer = new ResizeObserver(() => {
      if (!stickRef.current && state.anchor) restoreChatAnchor(el, state.anchor)
      remember()
    })
    remember()
    el.addEventListener('scroll', onScroll)
    if (contentRef.current) observer.observe(contentRef.current)
    return () => {
      el.removeEventListener('scroll', onScroll)
      observer.disconnect()
      // Last observed geometry belongs to the old DOM, even if this component is reused for another group.
      if (state.anchor) useChatSessionStore.getState().savePosition(scope, state.anchor)
    }
  }, [key, scope, ready, data, enabled, viewportRef, contentRef, stickRef, ignoreSaved])
  return notice
}
