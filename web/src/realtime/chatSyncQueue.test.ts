import { afterEach, describe, expect, it, vi } from 'vitest'
import { createChatSyncQueue } from './chatSyncQueue'

afterEach(() => vi.useRealTimers())

describe('chat synchronization queue', () => {
  it('merges same-turn signals and preserves a subsequent batch while a request runs', async () => {
    let release = () => {}
    const gate = new Promise<void>((resolve) => { release = resolve })
    const calls: string[][] = []
    let active = 0
    let maximum = 0
    const queue = createChatSyncQueue<string>(async (signals) => {
      calls.push([...signals].sort())
      maximum = Math.max(maximum, ++active)
      if (calls.length === 1) await gate
      active--
    }, () => {})
    queue.request('increment')
    queue.request('recover', 'increment')
    await Promise.resolve()
    for (let index = 0; index < 10; index++) queue.request('updated')
    expect(calls).toEqual([['increment', 'recover']])
    release()
    await vi.waitFor(() => expect(calls).toEqual([['increment', 'recover'], ['updated']]))
    expect(maximum).toBe(1)
    queue.dispose()
  })

  it('keeps later signals after failure without automatically retrying an idle failure', async () => {
    const onError = vi.fn()
    const run = vi.fn<(_: ReadonlySet<string>) => Promise<void>>().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    const queue = createChatSyncQueue(run, onError)
    queue.request('recover')
    await vi.waitFor(() => expect(onError).toHaveBeenCalledTimes(1))
    expect(run).toHaveBeenCalledTimes(1)
    queue.request('recover')
    await vi.waitFor(() => expect(run).toHaveBeenCalledTimes(2))
    queue.dispose()
  })

  it('deduplicates task deadlines, keeps the first deadline and clears timers on dispose', async () => {
    vi.useFakeTimers()
    const run = vi.fn<(_: ReadonlySet<string>) => Promise<void>>().mockResolvedValue(undefined)
    const queue = createChatSyncQueue(run, () => {})
    for (let index = 0; index < 8; index++) queue.defer('task-500', 500, 'increment')
    await vi.advanceTimersByTimeAsync(499)
    queue.defer('task-500', 500, 'increment')
    await vi.advanceTimersByTimeAsync(1)
    expect(run).toHaveBeenCalledTimes(1)
    queue.defer('task-1500', 1500, 'increment')
    queue.dispose()
    await vi.advanceTimersByTimeAsync(3000)
    expect(run).toHaveBeenCalledTimes(1)
  })

  it('discards pending work after leaving a group, including queued microtasks', async () => {
    const run = vi.fn<(_: ReadonlySet<string>) => Promise<void>>().mockResolvedValue(undefined)
    const queue = createChatSyncQueue(run, () => {})
    queue.request('increment')
    queue.dispose()
    queue.request('recover')
    await Promise.resolve()
    expect(run).not.toHaveBeenCalled()
  })
})
