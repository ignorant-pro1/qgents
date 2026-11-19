import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ChatHeader } from './ChatHeader'

describe('CHAT-010A project identification in the chat header', () => {
  it('shows the project alongside a same-name main group and retains labelled actions', () => {
    const openSettings = vi.fn()
    render(<ChatHeader group={{ id: 'g', projectId: 'p', title: '项目总群', type: 'PROJECT_MAIN', status: 'ACTIVE' }}
      project={{ id: 'p', teamId: 't', name: '研发项目' }} canOpenTaskTrigger={false} onOpenTaskTrigger={vi.fn()} onOpenSettings={openSettings} />)
    expect(screen.getByText(/项目：研发项目/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '群聊设置' }))
    expect(openSettings).toHaveBeenCalledOnce()
    expect(screen.queryByRole('button', { name: '发起任务' })).not.toBeInTheDocument()
  })
})
