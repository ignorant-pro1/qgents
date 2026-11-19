import type { QueryClient, QueryKey } from '@tanstack/react-query'

/** Merge same-turn signals; signals received during a request form one subsequent batch. */
export function createChatSyncQueue<Signal extends string>(
  run: (signals: ReadonlySet<Signal>) => Promise<void>,
  onError: (error: unknown) => void,
) {
  const pending = new Set<Signal>()
  const timers = new Map<string, ReturnType<typeof setTimeout>>()
  let scheduled = false
  let running = false
  let stopped = false

  async function flush() {
    scheduled = false
    if (stopped || running) return
    running = true
    try {
      while (!stopped && pending.size > 0) {
        const signals = new Set(pending)
        pending.clear()
        try { await run(signals) } catch (error) { if (!stopped) onError(error) }
      }
    } finally { running = false }
  }

  function request(...signals: Signal[]) {
    if (stopped) return
    for (const signal of signals) pending.add(signal)
    if (!scheduled && !running) {
      scheduled = true
      queueMicrotask(() => { void flush() })
    }
  }

  return {
    request,
    // Keep the first deadline so a busy task stream cannot postpone compensation forever.
    defer(key: string, delay: number, ...signals: Signal[]) {
      if (stopped || timers.has(key)) return
      timers.set(key, setTimeout(() => {
        timers.delete(key)
        request(...signals)
      }, delay))
    },
    dispose() {
      stopped = true
      pending.clear()
      for (const timer of timers.values()) clearTimeout(timer)
      timers.clear()
    },
  }
}

type ChatSummaryKey = readonly ['groups', string] | readonly ['chat', 'main-groups']
type SummaryQueue = ReturnType<typeof createChatSyncQueue<'refresh'>>
const summaryQueues = new WeakMap<QueryClient, Map<string, SummaryQueue>>()

/** Exact list invalidation must not refetch nested messages, members or context windows. */
function invalidateChatResource(client: QueryClient, queryKey: QueryKey, exact: boolean): void {
  let queues = summaryQueues.get(client)
  if (!queues) { queues = new Map(); summaryQueues.set(client, queues) }
  const hash = JSON.stringify([queryKey, exact])
  let queue = queues.get(hash)
  if (!queue) {
    queue = createChatSyncQueue<'refresh'>(async () => {
      await client.invalidateQueries({ queryKey, exact }, { cancelRefetch: false })
    }, () => { /* Query owns its error state; a later signal can retry. */ })
    queues.set(hash, queue)
  }
  queue.request('refresh')
}

export function invalidateChatSummary(client: QueryClient, queryKey: ChatSummaryKey): void {
  invalidateChatResource(client, queryKey, true)
}

export function invalidateChatMessages(client: QueryClient, projectId: string, groupId: string): void {
  invalidateChatResource(client, ['groups', projectId, groupId, 'messages'], false)
}
