import { afterEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '@/query'
import { invalidateProjectChatAfterCursorExpiry, invalidateProjectTaskEvent, invalidateProjectTaskModel, queryKeysForProjectTaskEvent } from './queryInvalidation'
import type { ProjectTaskEvent } from './eventParser'

const projectId = 'project-1'

afterEach(() => {
  vi.restoreAllMocks()
})

function keysFor(type: ProjectTaskEvent['type'], payload: Record<string, unknown>) {
  return queryKeysForProjectTaskEvent(projectId, {
    id: 'evt-1',
    type,
    payload: { projectId, ...payload },
  }).map((key) => JSON.stringify(key))
}

describe('project SSE Task model query invalidation mapping', () => {
  it('maps task.updated to Task list, detail, and work-branches', () => {
    expect(keysFor('task.updated', { taskId: 'task-1' })).toEqual([
      JSON.stringify(['qgents', 'projects', projectId, 'tasks']),
      JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1']),
      JSON.stringify(['qgents', 'projects', projectId, 'work-branches']),
    ])
  })

  it('maps task-step.updated to Task, TaskStep list, and TaskRun list', () => {
    const keys = keysFor('task-step.updated', { taskId: 'task-1', taskStepId: 'step-1' })
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1', 'steps']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1', 'task-runs']))
  })

  it('maps task-run.updated to run detail, task runs, task list/detail, and TaskStep list', () => {
    const keys = keysFor('task-run.updated', { taskId: 'task-1', taskRunId: 'run-1', agentId: 'agent-1' })
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1', 'task-runs']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1', 'steps']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'agents', 'agent-1', 'runtime']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'agent', 'agent-1']))
  })

  it('maps progress to TaskRun detail and logs (also accepting legacy stepId)', () => {
    const keys = keysFor('task-run.step.progress', { taskId: 'task-1', taskRunId: 'run-1', stepId: 'step-1', content: 'raw event content' })
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1', 'logs', {}]))

    const taskStepIdKeys = keysFor('task-run.step.progress', { taskId: 'task-1', taskRunId: 'run-1', taskStepId: 'step-1' })
    expect(taskStepIdKeys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1']))
    expect(taskStepIdKeys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1', 'logs', {}]))
  })

  it('maps workspace preview updates without invalidating formal Diff queries', () => {
    const keys = keysFor('workspace.diff-preview.updated', { taskId: 'task-1', taskRunId: 'run-1', workspaceId: 'workspace-1', previewRevision: 2, filesChanged: 2, additions: 8, deletions: 3, eventVersion: 1 })
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1', 'workspace-diff-preview']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1']))
    expect(keys.some((key) => key.includes('"diffs"'))).toBe(false)
  })

  it.each(['input-required', 'approval-required'] as const)('maps %s to run, input requests, and Task detail', (type) => {
    const keys = keysFor(type, { taskId: 'task-1', taskStepId: 'step-1', taskRunId: 'run-1', inputRequestId: 'input-1' })
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1', 'input-requests']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1']))
  })

  it('maps diff.created to Diff list/detail, Task, optional TaskRun detail, and work-branches', () => {
    const keys = keysFor('diff.created', { taskId: 'task-1', diffId: 'diff-1', taskRunId: 'run-1' })
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'diffs']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'diffs', 'diff-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks', 'task-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1']))
    expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'work-branches']))
    expect(keysFor('diff.created', { taskId: 'task-1', diffId: 'diff-1' }).some((key) => key.includes('task-runs'))).toBe(false)
  })

  it('maps artifact events to artifact timeline and related run queries', () => {
    expect(keysFor('task.artifact.created', { taskId: 'task-1', artifactId: 'artifact-1', sequenceNo: 1, artifactType: 'PLAN' })).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-artifacts', 'task-1']))
    expect(keysFor('task-run.artifact.created', { taskId: 'task-1', taskRunId: 'run-1', taskStepId: 'step-1', artifactId: 'artifact-1', sequenceNo: 2, artifactType: 'CODING' })).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-runs', 'run-1']))
  })

  it('maps Diff review and delivery events to Task and batch queries', () => {
    for (const type of ['diff-review.created', 'task.awaiting-diff-confirmation', 'diff-review.confirmed', 'diff-review.rejected', 'diff-review.superseded', 'delivery.started', 'delivery.completed', 'delivery.failed', 'task.diff-review.failed'] as const) {
      const keys = keysFor(type, { taskId: 'task-1', reviewBatchId: 'batch-1', deliveryStatus: 'FAILED', reason: 'failed' })
      expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks']))
      expect(keys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'task-diff-review', 'task-1']))
    }
    const supersededKeys = keysFor('diff-review.superseded', { workspaceId: 'workspace-1', taskId: 'task-1', reviewBatchId: 'batch-1', reviewStatus: 'SUPERSEDED' })
    expect(supersededKeys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'diffs']))
    expect(supersededKeys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'work-branches']))
    const repositoryKeys = keysFor('delivery.repository.updated', { taskId: 'task-1', diffId: 'diff-1', deliveryStatus: 'DELIVERED' })
    expect(repositoryKeys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'tasks']))
    expect(repositoryKeys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'diffs', 'diff-1']))
    expect(repositoryKeys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'merge-requests']))
  })

  it('maps merge-request.updated to the project MR list, DeliveryCenter, and work-branches', () => {
    expect(keysFor('merge-request.updated', { mergeRequestId: 'mr-1' })).toEqual([
      JSON.stringify(['qgents', 'projects', projectId, 'delivery-center']),
      JSON.stringify(['qgents', 'projects', projectId, 'merge-requests']),
      JSON.stringify(['qgents', 'projects', projectId, 'work-branches']),
    ])
    expect(keysFor('merge-request.updated', {})).toEqual([])
  })

  it('maps group and message events to their scoped group queries', () => {
    expect(keysFor('message.created', { groupId: 'group-1', messageId: 'message-1' })).toEqual([
      JSON.stringify(['groups', projectId]),
      JSON.stringify(['groups', projectId, 'group-1', 'messages']),
    ])
    expect(keysFor('group.updated', { groupId: 'group-1' })).toEqual([
      JSON.stringify(['groups', projectId]),
    ])
    expect(keysFor('group.member.updated', { groupId: 'group-1' })).toEqual([
      JSON.stringify(['groups', projectId]),
      JSON.stringify(['groups', projectId, 'group-1', 'members']),
    ])
  })

  it('maps test-run.updated and dry-run.updated to the matching run queries', () => {
    expect(keysFor('test-run.updated', { testRunId: 'testrun-1' })).toEqual([
      JSON.stringify(['qgents', 'projects', projectId, 'test-runs']),
      JSON.stringify(['qgents', 'projects', projectId, 'test-runs', 'testrun-1']),
      JSON.stringify(['qgents', 'projects', projectId, 'work-branches']),
    ])
    expect(keysFor('dry-run.updated', { dryRunId: 'dryrun-1' })).toEqual([
      JSON.stringify(['qgents', 'projects', projectId, 'dry-runs']),
      JSON.stringify(['qgents', 'projects', projectId, 'dry-runs', 'dryrun-1', 'report']),
    ])
    expect(keysFor('test-run.updated', {})).toEqual([])
  })

  it('invalidates DeliveryCenter and resource queries for frozen Memory and Skill events', () => {
    const memoryKeys = keysFor('memory.approved', { resourceType: 'MEMORY', resourceId: 'memory-1', eventVersion: 1, updatedAt: '2026-08-15T00:00:00Z' })
    expect(memoryKeys).toContain(JSON.stringify(['qgents', 'projects', projectId, 'delivery-center']))
    expect(memoryKeys).toContain(JSON.stringify(['memories', projectId]))
    const skillKeys = keysFor('skill.published', { resourceType: 'SKILL', resourceId: 'skill-1', eventVersion: 1, updatedAt: '2026-08-15T00:00:00Z' })
    expect(skillKeys).toContain(JSON.stringify(['skills', projectId]))
  })

  it('maps skipped Diff and MR updates without writing entity cache', () => {
    const skipped = keysFor('diff-review.skipped', { taskId: 'task-1', reason: 'FINAL_DIFF_EMPTY' })
    expect(skipped).toContain(JSON.stringify(['qgents', 'projects', projectId, 'delivery-center']))
    expect(skipped).toContain(JSON.stringify(['qgents', 'projects', projectId, 'work-branches']))
    const mergeRequest = keysFor('merge-request.updated', { mergeRequestId: 'mr-1' })
    expect(mergeRequest).toEqual([
      JSON.stringify(['qgents', 'projects', projectId, 'delivery-center']),
      JSON.stringify(['qgents', 'projects', projectId, 'merge-requests']),
      JSON.stringify(['qgents', 'projects', projectId, 'work-branches']),
    ])
  })

  it('ignores mismatched projects and missing required IDs without broad invalidation', () => {
    expect(queryKeysForProjectTaskEvent(projectId, {
      id: 'evt-2',
      type: 'task.updated',
      payload: { projectId: 'project-2', taskId: 'task-1' },
    })).toEqual([])
    expect(keysFor('task.updated', {})).toEqual([])
    expect(keysFor('task-step.updated', { taskId: 'task-1' })).toEqual([])
    expect(keysFor('task-run.updated', { taskId: 'task-1' })).toEqual([])
    expect(keysFor('task-run.step.progress', { taskId: 'task-1', taskRunId: 'run-1' })).toEqual([])
    expect(keysFor('input-required', { taskId: 'task-1', taskStepId: 'step-1', taskRunId: 'run-1' })).toEqual([])
    expect(keysFor('task-step.updated', { taskId: 'task-1', stepId: 'old-step-id' })).toEqual([])
    expect(keysFor('diff.created', { taskId: 'task-1' })).toEqual([])
  })

  it('does not map retired or unrelated events', () => {
    const retiredEvent = {
      id: 'evt-3',
      type: 'task.updated' as const,
      payload: { projectId, taskId: 'task-1', eventName: 'orchestration-run.updated' },
    }
    expect(queryKeysForProjectTaskEvent(projectId, retiredEvent)).not.toHaveLength(0)
  })

  it('refreshes the current project model roots after cursor expiration', () => {
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)
    invalidateProjectTaskModel(projectId)
    expect(invalidateQueries.mock.calls.map(([options]) => JSON.stringify(options?.queryKey))).toEqual([
      JSON.stringify(['qgents', 'projects', projectId, 'tasks']),
      JSON.stringify(['qgents', 'projects', projectId, 'task-runs']),
      JSON.stringify(['qgents', 'projects', projectId, 'diffs']),
      JSON.stringify(['qgents', 'projects', projectId, 'task-artifacts']),
      JSON.stringify(['qgents', 'projects', projectId, 'task-diff-review']),
      JSON.stringify(['qgents', 'projects', projectId, 'delivery-center']),
      JSON.stringify(['qgents', 'projects', projectId, 'merge-requests']),
      JSON.stringify(['qgents', 'projects', projectId, 'work-branches']),
    ])
  })

  it('coalesces unclaimed message invalidations and keeps summary invalidation exact', async () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)
    for (let index = 0; index < 8; index++) invalidateProjectTaskEvent(projectId, {
      id: null, type: 'message.created', payload: { projectId, groupId: 'inactive', messageId: `m-${index}` },
    })
    await Promise.resolve()
    expect(invalidate).toHaveBeenCalledTimes(2)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['groups', projectId], exact: true }, { cancelRefetch: false })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['groups', projectId, 'inactive', 'messages'], exact: false }, { cancelRefetch: false })
  })

  it('invalidates only the expired project messages and both unread summaries', async () => {
    const keys = [
      ['groups', projectId], ['chat', 'main-groups'],
      ['groups', projectId, 'g1', 'messages'],
      ['groups', projectId, 'g1', 'messages', 'context', 'old'],
      ['groups', projectId, 'g1', 'members'],
      ['groups', 'another-project', 'g1', 'messages'],
    ]
    try {
      keys.forEach((key) => queryClient.setQueryData(key, []))
      invalidateProjectChatAfterCursorExpiry(projectId)
      await Promise.resolve()
      expect(keys.map((key) => queryClient.getQueryState(key)?.isInvalidated)).toEqual([true, true, true, true, false, false])
    } finally { queryClient.clear() }
  })

  it('lets the active chat queue own recovery and retains refetch fallback without a chat', async () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)
    const claim = (event: Event) => event.preventDefault()
    window.addEventListener('qgents:chat-resync', claim)
    try {
      invalidateProjectChatAfterCursorExpiry(projectId)
      expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['groups', projectId], refetchType: 'none' }), { cancelRefetch: false })
    } finally { window.removeEventListener('qgents:chat-resync', claim) }
    invalidateProjectChatAfterCursorExpiry(projectId)
    await Promise.resolve()
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['groups', projectId], refetchType: 'active' }), { cancelRefetch: false })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['groups', projectId], exact: true }, { cancelRefetch: false })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['chat', 'main-groups'], exact: true }, { cancelRefetch: false })
  })

  it('lets only the active group claim synchronization and rejects mismatched project events', async () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)
    const onMessage = (event: Event) => {
      const detail = (event as CustomEvent<{ projectId: string; groupId: string }>).detail
      if (detail.projectId === projectId && detail.groupId === 'active') event.preventDefault()
    }
    window.addEventListener('qgents:message-event', onMessage)
    try {
      invalidateProjectTaskEvent(projectId, { id: null, type: 'message.updated', payload: { projectId: 'other-project', groupId: 'active', messageId: 'm-1' } })
      expect(invalidate).not.toHaveBeenCalled()
      for (const groupId of ['active', 'inactive']) invalidateProjectTaskEvent(projectId, {
        id: null, type: 'message.updated', payload: { projectId, groupId, messageId: 'm-1' },
      })
      await Promise.resolve()
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['groups', projectId, 'active', 'messages'], refetchType: 'none' })
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['groups', projectId, 'inactive', 'messages'], exact: false }, { cancelRefetch: false })
    } finally { window.removeEventListener('qgents:message-event', onMessage) }
  })
})
