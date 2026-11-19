import { lazy, Suspense } from 'react'
import { Button, Empty, Spin, Typography } from 'antd'
import { useOutletContext, useParams } from 'react-router-dom'

const ChatPanel = lazy(async () => ({ default: (await import('@/components/chat/ChatPanel')).ChatPanel }))

export interface RequirementChatOutletContext {
  defaultGroupId: string | undefined
  groupsPending: boolean
  groupsPaused: boolean
  groupsFailed: boolean
  retryGroups: () => void
  openCreateGroup: () => void
}

function ChatLoading() {
  return (
    <div className="pd-chat-state" role="status">
      <Spin size="large" />
      <Typography.Text type="secondary">正在进入项目群聊…</Typography.Text>
    </div>
  )
}

/** 项目群聊入口。子路由使用侧栏已有的群列表查询结果。 */
export default function RequirementChatPage() {
  const { projectId = '', groupId } = useParams<{ projectId: string; groupId: string }>()
  const { defaultGroupId, groupsPending, groupsPaused, groupsFailed, retryGroups, openCreateGroup } =
    useOutletContext<RequirementChatOutletContext>()
  const selectedGroupId = groupId ?? defaultGroupId

  if (selectedGroupId) {
    return (
      <Suspense fallback={<ChatLoading />}>
        <ChatPanel key={`${projectId}/${selectedGroupId}`} projectId={projectId} groupId={selectedGroupId} />
      </Suspense>
    )
  }

  if (groupsPending && !groupsPaused) return <ChatLoading />

  return (
    <section className="pd-chat-state" aria-label="项目群聊状态">
      {groupsPaused ? (
        <Typography.Text type="secondary" role="status">等待网络恢复后加载群聊…</Typography.Text>
      ) : groupsFailed ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="群聊加载失败，请重试">
          <Button onClick={retryGroups}>重试加载群聊</Button>
        </Empty>
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用群聊">
          <Button type="primary" onClick={openCreateGroup}>新建需求群</Button>
        </Empty>
      )}
    </section>
  )
}
