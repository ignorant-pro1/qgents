import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ProjectEventStreamInit } from '@/api/projectEvents'
import { queryClient } from '@/query'
import { EventCursorExpiredError } from './projectEventConnection'
import { useProjectTaskDomainEvents, useProjectTaskSyncState } from './useProjectTaskDomainEvents'

const connectProjectEventsMock = vi.hoisted(() => vi.fn(async (init: ProjectEventStreamInit) => {
  await init.onopen(new Response(null, { status: 200, headers: { 'content-type': 'text/event-stream' } }))
}))

vi.mock('@/api/projectEvents', () => ({
  connectProjectEvents: connectProjectEventsMock,
  projectEventsEnabled: projectEventsEnabledMock,
}))
const projectEventsEnabledMock = vi.hoisted(() => vi.fn(() => true))
const realtimeStatusMock = vi.hoisted(() => vi.fn(() => 'idle' as 'idle' | 'connected' | 'connecting' | 'disconnected'))
vi.mock('./realtimeClient', () => ({ useRealtimeConnectionStatus: realtimeStatusMock }))

describe('project task event subscription', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    connectProjectEventsMock.mockClear()
    projectEventsEnabledMock.mockReturnValue(true)
    realtimeStatusMock.mockReturnValue('idle')
  })

  it('shares one connection and switches projects by aborting the old stream', async () => {
    const streams: ProjectEventStreamInit[] = []
    connectProjectEventsMock.mockImplementation(async (init: ProjectEventStreamInit) => {
      streams.push(init)
      await init.onopen(new Response(null, { status: 200, headers: { 'content-type': 'text/event-stream' } }))
    })
    const first = renderHook(({ projectId }) => useProjectTaskDomainEvents(projectId), {
      initialProps: { projectId: 'project-1' },
    })
    const second = renderHook(({ projectId }) => useProjectTaskDomainEvents(projectId), {
      initialProps: { projectId: 'project-1' },
    })
    await act(async () => undefined)

    expect(connectProjectEventsMock).toHaveBeenCalledOnce()

    second.unmount()
    first.rerender({ projectId: 'project-2' })
    await act(async () => undefined)

    expect(connectProjectEventsMock).toHaveBeenCalledTimes(2)
    expect(streams[0]?.signal.aborted).toBe(true)
    expect(streams[1]?.signal.aborted).toBe(false)

    first.unmount()
    expect(streams[1]?.signal.aborted).toBe(true)
  })

  it('does not create a real stream in Mock mode', () => {
    projectEventsEnabledMock.mockReturnValue(false)
    const hook = renderHook(() => useProjectTaskDomainEvents('project-mock'))
    expect(hook.result.current).toBe('idle')
    expect(connectProjectEventsMock).not.toHaveBeenCalled()
    hook.unmount()
  })

  it('recovers chat immediately on expiry and ignores late expiry after a project switch', async () => {
    const streams: ProjectEventStreamInit[] = []
    connectProjectEventsMock.mockImplementation(async (init: ProjectEventStreamInit) => {
      streams.push(init)
      await init.onopen(new Response(null, { status: 200, headers: { 'content-type': 'text/event-stream' } }))
    })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)
    const projects: string[] = []
    const onResync = (event: Event) => projects.push((event as CustomEvent<{ projectId: string }>).detail.projectId)
    window.addEventListener('qgents:chat-resync', onResync)
    const hook = renderHook(({ projectId }) => useProjectTaskDomainEvents(projectId), { initialProps: { projectId: 'expired-project' } })
    try {
      await act(async () => undefined)
      await act(async () => {
        expect(() => streams[0]?.onerror(new EventCursorExpiredError())).toThrow(EventCursorExpiredError)
      })
      expect(projects).toEqual(['expired-project'])
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['groups', 'expired-project'], exact: true }, { cancelRefetch: false })
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['qgents', 'projects', 'expired-project', 'tasks'] })
      hook.rerender({ projectId: 'new-project' })
      await act(async () => undefined)
      invalidate.mockClear()
      expect(() => streams[0]?.onerror(new EventCursorExpiredError())).toThrow(EventCursorExpiredError)
      expect(invalidate).not.toHaveBeenCalled()
      expect(projects).toEqual(['expired-project'])
      await act(async () => {
        expect(() => streams[1]?.onerror(new EventCursorExpiredError())).toThrow(EventCursorExpiredError)
      })
      expect(projects).toEqual(['expired-project', 'new-project'])
    } finally {
      hook.unmount()
      window.removeEventListener('qgents:chat-resync', onResync)
    }
  })

  it('reuses the WebSocket status without opening a second stream and falls back to shared SSE', async () => {
    realtimeStatusMock.mockReturnValue('connected')
    const hook = renderHook(() => useProjectTaskSyncState('status-project', 5000))
    expect(hook.result.current).toEqual({ connectionStatus: 'connected', pollingInterval: false })
    expect(connectProjectEventsMock).not.toHaveBeenCalled()
    realtimeStatusMock.mockReturnValue('disconnected')
    hook.rerender()
    await act(async () => undefined)
    expect(hook.result.current).toEqual({ connectionStatus: 'connected', pollingInterval: false })
    expect(connectProjectEventsMock).toHaveBeenCalledOnce()
    hook.unmount()
  })

  it('reports a disabled real-time channel in Mock mode without pretending to be synchronized', () => {
    projectEventsEnabledMock.mockReturnValue(false)
    const hook = renderHook(() => useProjectTaskSyncState('mock-status', 5000))
    expect(hook.result.current).toEqual({ connectionStatus: 'disabled', pollingInterval: 5000 })
    expect(connectProjectEventsMock).not.toHaveBeenCalled()
    hook.unmount()
  })
})
