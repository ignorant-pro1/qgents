import { useEffect, useMemo, useState } from 'react'
import { Layout, Input, List, Avatar, Typography, Space, theme, Empty, Badge, Spin, Button } from 'antd'
import { SearchOutlined, TeamOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { groupApi, projectApi, teamApi } from '@/api'
import { ChatPanel } from '@/components/chat/ChatPanel'
import { latestMessageText } from '@/utils/messageSummary'
import { useAppUiStore, useCurrentTeamId } from '@/store/appUiStore'
import type { Group } from '@/types'
import './ChatWorkspacePage.css'

const { Text } = Typography

interface MainGroupSession {
  projectId: string
  groupId: string
  groupTitle: string
  projectName: string
  latestMessage?: Group['latestMessage']
  latestActivityAt?: string
  unreadCount?: number
  mentionedUnread?: number
}

/**
 * 项目群聊工作台 —— 左边聚合「**当前团队**的所有项目主群」，右边内嵌聊天面板。
 * 数据：GET /chat/main-groups 主群聚合（含全部可见项目）+ GET /teams/{id}/projects 按团队过滤；
 * 数据加载完成后自动选中第一个主群，避免刚进入时的空态闪烁。
 *
 * 团队上下文挂在路由层（/app/chat?teamId=xxx）：刷新页面后仍停留在上次团队，
 * 不再退化为「第一个团队」导致串到别的团队的项目主群（v2.0.6）。
 */
export default function ChatWorkspacePage() {
  const { token } = theme.useToken()
  const [searchParams, setSearchParams] = useSearchParams()
  const routeTeamId = searchParams.get('teamId')
  const currentTeamId = useCurrentTeamId()
  const setCurrentTeam = useAppUiStore((s) => s.setCurrentTeam)
  const [keyword, setKeyword] = useState('')
  const [fallback, setFallback] = useState<{ search: string; text: string } | null>(null)

  // 当前团队兜底：从未进入团队/项目页时取第一个团队
  const teamsQuery = useQuery({
    queryKey: ['teams', 'mine'],
    queryFn: teamApi.listMine,
  })
  const teams = teamsQuery.data ?? []
  // Pending also includes offline/paused reads; they cannot establish an empty team list.
  const teamsLoading = teamsQuery.isPending
  const effectiveTeamId = teams.find((team) => team.id === routeTeamId)?.id
    ?? teams.find((team) => team.id === currentTeamId)?.id ?? teams[0]?.id ?? ''

  // 团队确定后写入全局上下文 + 同步回 URL（刷新不丢团队；replace 不产生历史记录）
  useEffect(() => {
    if (!effectiveTeamId) return
    if (currentTeamId !== effectiveTeamId) setCurrentTeam(effectiveTeamId)
  }, [effectiveTeamId, currentTeamId, setCurrentTeam])

  // 当前团队的项目 id 集合：主群聚合按团队过滤（聚合接口不返回 teamId）
  const projectsQuery = useQuery({
    queryKey: ['teams', effectiveTeamId, 'projects'],
    queryFn: () => projectApi.listByTeam(effectiveTeamId),
    enabled: !!effectiveTeamId,
  })
  const teamProjects = useMemo(() => projectsQuery.data ?? [], [projectsQuery.data])
  const teamProjectsLoading = !!effectiveTeamId && projectsQuery.isPending
  const teamProjectIds = useMemo(() => new Set(teamProjects.map((p) => p.id)), [teamProjects])
  // 项目头像映射：主群会话头像 = 项目头像（v2.0.6）
  const projectAvatarById = useMemo(
    () => new Map(teamProjects.map((p) => [p.id, p.avatarUrl]).filter(([, url]) => !!url) as [string, string][]),
    [teamProjects],
  )
  const projectNameById = useMemo(() => new Map(teamProjects.map((p) => [p.id, p.name])), [teamProjects])

  // 主群聚合：按最近活跃倒序；含 latestMessage / memberCount / unreadCount / mentionedUnread
  const groupsQuery = useQuery({
    queryKey: ['chat', 'main-groups'],
    queryFn: groupApi.listMainGroups,
  })
  const mainGroups = useMemo(() => groupsQuery.data ?? [], [groupsQuery.data])
  const mainGroupsLoading = groupsQuery.isPending

  const loading = mainGroupsLoading || teamProjectsLoading || teamsLoading

  const sessions = useMemo<MainGroupSession[]>(() => {
    if (loading) return []
    return mainGroups
      .filter((g) => g.type === 'PROJECT_MAIN' && teamProjectIds.has(g.projectId))
      .map((g) => ({
        projectId: g.projectId,
        groupId: g.id,
        groupTitle: g.title,
        projectName: projectNameById.get(g.projectId) ?? g.projectId,
        latestMessage: g.latestMessage,
        latestActivityAt: g.latestActivityAt,
        unreadCount: g.unreadCount,
        mentionedUnread: g.mentionedUnread,
      }))
  }, [mainGroups, loading, teamProjectIds, projectNameById])

  const initialError = teamsQuery.isLoadingError || projectsQuery.isLoadingError || groupsQuery.isLoadingError
  const projectId = searchParams.get('projectId')
  const groupId = searchParams.get('groupId')
  const requested = sessions.find((session) => session.projectId === projectId && session.groupId === groupId)
  const validTeam = routeTeamId === effectiveTeamId
  const selected = !initialError && !loading ? (validTeam ? requested : undefined) ?? sessions[0] ?? null : null

  // URL 是会话选择的来源。仅在数据成功就绪后规范化；瞬时请求失败不改写深链。
  useEffect(() => {
    if (loading || initialError) return
    const next = new URLSearchParams(searchParams)
    if (effectiveTeamId) next.set('teamId', effectiveTeamId)
    else next.delete('teamId')
    if (selected) {
      next.set('projectId', selected.projectId)
      next.set('groupId', selected.groupId)
    } else {
      next.delete('projectId'); next.delete('groupId')
    }
    if (next.toString() === searchParams.toString()) return
    if ((routeTeamId && !validTeam) || ((projectId || groupId) && (!requested || !validTeam))) {
      setFallback({ search: next.toString(), text: selected ? '链接中的团队或会话不可用，已打开当前团队的可用群聊。' : '链接中的团队或会话不可用，当前没有可访问的项目群聊。' })
    }
    setSearchParams(next, { replace: true })
  }, [loading, initialError, effectiveTeamId, selected, searchParams, setSearchParams, routeTeamId, validTeam, projectId, groupId, requested])

  const selectSession = (session: MainGroupSession) => {
    const next = new URLSearchParams(searchParams)
    next.set('teamId', effectiveTeamId)
    next.set('projectId', session.projectId)
    next.set('groupId', session.groupId)
    setFallback(null)
    if (next.toString() !== searchParams.toString()) setSearchParams(next)
  }

  const filtered = useMemo(() => {
    const q = keyword.trim().toLowerCase()
    if (!q) return sessions
    return sessions.filter(
      (s) =>
        s.projectId.toLowerCase().includes(q) ||
        s.projectName.toLowerCase().includes(q) ||
        s.groupTitle.toLowerCase().includes(q),
    )
  }, [sessions, keyword])

  return (
    <Layout className="chat-workspace" style={{ height: 'calc(100dvh - 56px)', background: token.colorBgBase }}>
      <Layout.Sider
        width={280}
        className="chat-workspace__sidebar"
        theme="dark"
        style={{
          borderRight: `1px solid ${token.colorBorder}`,
          background: token.colorBgContainer,
          overflow: 'auto',
        }}
      >
        <div style={{ padding: 12 }}>
          <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
            <Input
              prefix={<SearchOutlined />}
              placeholder="搜索项目群聊"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              allowClear
            />
          </Space.Compact>

          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>
            项目主群
          </Text>

          {fallback?.search === searchParams.toString() ? <Text role="status" type="secondary">{fallback.text}</Text> : null}
          {initialError ? <Empty description="会话列表加载失败，链接已保留"><Button onClick={() => { void teamsQuery.refetch(); if (effectiveTeamId) void projectsQuery.refetch(); void groupsQuery.refetch() }}>重试加载会话</Button></Empty> : loading ? (
            <div style={{ display: 'flex', justifyContent: 'center', padding: '40px 0' }}>
              <Spin size="small" />
            </div>
          ) : filtered.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无项目群聊" />
          ) : (
            <List
              dataSource={filtered}
              rowKey={(s) => `${s.projectId}/${s.groupId}`}
              split={false}
              renderItem={(s) => (
                <List.Item style={{ padding: 0, border: 'none' }}>
                <button
                  type="button"
                  className="chat-workspace__session"
                  onClick={() => selectSession(s)}
                  aria-label={`打开群聊 ${s.groupTitle}，项目 ${s.projectName}`} aria-current={selected?.projectId === s.projectId && selected.groupId === s.groupId ? 'page' : undefined}
                  style={{
                    padding: '10px 12px',
                    cursor: 'pointer',
                    background: selected?.groupId === s.groupId ? 'rgba(34, 197, 94, 0.12)' : undefined,
                    borderRadius: 8,
                    border: 'none',
                  }}
                >
                  <List.Item.Meta
                    avatar={
                      <Avatar
                        src={projectAvatarById.get(s.projectId)}
                        style={{ background: '#3b82f6', flexShrink: 0 }}
                        icon={<TeamOutlined />}
                        size={36}
                      />
                    }
                    title={
                      <Space direction="vertical" size={0} style={{ width: '100%' }}>
                        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                          <Text strong className="chat-workspace__group-title">
                            {s.groupTitle}
                          </Text>
                          <Space size={4}>
                            {/* 已读确认成功后由缓存及后端摘要清除，当前选中不能代替已读。 */}
                            {typeof s.mentionedUnread === 'number' &&
                            s.mentionedUnread > 0 ? (
                              <span
                                style={{
                                  padding: '0 7px',
                                  borderRadius: 999,
                                  background: 'rgba(239, 68, 68, 0.16)',
                                  color: '#f87171',
                                  fontSize: 11,
                                  lineHeight: '16px',
                                  fontWeight: 600,
                                  whiteSpace: 'nowrap',
                                }}
                              >
                                有人@你
                              </span>
                            ) : null}
                            <Badge count={s.unreadCount} overflowCount={99} size="small" />
                          </Space>
                        </Space>
                        <Text type="secondary" className="chat-workspace__project-name">项目：{s.projectName}</Text>
                      </Space>
                    }
                    description={
                      <Text type="secondary" ellipsis style={{ fontSize: 12 }}>
                        {s.latestMessage
                          ? `${s.latestMessage.senderName ? `${s.latestMessage.senderName}: ` : ''}${latestMessageText(s.latestMessage)}`
                          : s.groupTitle}
                      </Text>
                    }
                  />
                </button>
                </List.Item>
              )}
            />
          )}
        </div>
      </Layout.Sider>

      {initialError ? <Empty description="暂时无法打开群聊，请重试加载会话" /> : loading ? (
        <Layout style={{ background: token.colorBgBase }}>
          <div
            style={{
              flex: 1,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Spin />
          </div>
        </Layout>
      ) : selected ? (
        <ChatPanel key={`${selected.projectId}/${selected.groupId}`} projectId={selected.projectId} groupId={selected.groupId} />
      ) : (
        <Layout style={{ background: token.colorBgBase }}>
          <div
            style={{
              flex: 1,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Empty description="暂无项目群聊" />
          </div>
        </Layout>
      )}
    </Layout>
  )
}
