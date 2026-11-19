import { create } from 'zustand'
import type { Mention } from '@/types'

export interface ChatScope {
  userId: string
  projectId: string
  groupId: string
  epoch: number
}

export interface ChatDraft {
  text: string
  mentions: Mention[]
  replyToId: string | null
  revision: number
}

export interface ChatReadingAnchor {
  messageId?: string
  offset: number
  scrollTop: number
  atBottom: boolean
}

export type ChatReadingPosition = ChatReadingAnchor & { contextMessageId?: string }

export const EMPTY_CHAT_DRAFT: ChatDraft = { text: '', mentions: [], replyToId: null, revision: 0 }
export const chatScopeKey = (scope: Pick<ChatScope, 'userId' | 'projectId' | 'groupId'>) =>
  JSON.stringify([scope.userId, scope.projectId, scope.groupId])

interface ChatSessionState {
  userId: string | null
  epoch: number
  drafts: Record<string, ChatDraft>
  positions: Record<string, ChatReadingPosition>
  activateUser: (userId: string | null) => void
  updateDraft: (scope: ChatScope, update: (draft: ChatDraft) => Omit<ChatDraft, 'revision'>) => void
  clearDraft: (scope: ChatScope, revision: number) => void
  savePosition: (scope: ChatScope, position: ChatReadingPosition) => void
}

/** UI-only, in-memory session data. No Message resources, Files or browser storage. */
export const useChatSessionStore = create<ChatSessionState>((set) => ({
  userId: null,
  epoch: 0,
  drafts: {},
  positions: {},
  activateUser: (userId) => set((state) => state.userId === userId ? state : {
    userId, epoch: state.epoch + 1, drafts: {}, positions: {},
  }),
  updateDraft: (scope, update) => set((state) => {
    if (!scope.userId || state.userId !== scope.userId || state.epoch !== scope.epoch) return state
    const key = chatScopeKey(scope)
    const previous = state.drafts[key] ?? EMPTY_CHAT_DRAFT
    const next = update(previous)
    return { drafts: { ...state.drafts, [key]: { ...next, revision: previous.revision + 1 } } }
  }),
  clearDraft: (scope, revision) => set((state) => {
    const key = chatScopeKey(scope)
    if (state.userId !== scope.userId || state.epoch !== scope.epoch || state.drafts[key]?.revision !== revision) return state
    return { drafts: { ...state.drafts, [key]: { ...EMPTY_CHAT_DRAFT, revision: revision + 1 } } }
  }),
  savePosition: (scope, position) => set((state) => {
    if (!scope.userId || state.userId !== scope.userId || state.epoch !== scope.epoch) return state
    return { positions: { ...state.positions, [chatScopeKey(scope)]: position } }
  }),
}))
