import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Layout, Button, Typography, theme, Empty, Spin } from "antd";
import { App } from "antd";
import type { TextAreaRef } from "antd/es/input/TextArea";
import { ArrowDownOutlined, MessageOutlined } from "@ant-design/icons";
import {
  useInfiniteQuery,
  infiniteQueryOptions,
  onlineManager,
  replaceEqualDeep,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from "@tanstack/react-query";
import { formatApiError } from "@/utils/formatApiError";
import {
  ApiError,
  groupApi,
  projectApi,
  attachmentApi,
  uploadAttachment,
  memoryApi,
  tasksApi,
  mergeRequestsApi,
} from "@/api";
import { AttachmentPreviewModal } from "@/components/chat/AttachmentPreviewModal";
import { ChatHeader } from "@/components/chat/ChatHeader";
import { ChatMessageViewport } from "@/components/chat/ChatMessageList";
import { ChatComposer } from "@/components/chat/ChatComposer";
import { ChatMessageRow } from "@/components/chat/ChatMessageRow";
import { ChatHistoryWindow } from "@/components/chat/ChatHistoryWindow";
import { MessageBubble } from "@/components/chat/ChatMessageBubble";
import { ChatMentionGroup } from "@/components/chat/ChatMentionGroup";
import { ChatSettingsDrawer } from "@/components/chat/ChatSettingsDrawer";
import { useAuth } from "@/context/AuthContext";
import { useAgents } from "@/hooks/agents";
import { subscribeRealtimeReconnect } from "@/realtime";
import { createChatSyncQueue, invalidateChatSummary } from "@/realtime/chatSyncQueue";
import { useProjectTaskSyncState } from "@/realtime/useProjectTaskDomainEvents";
import "./ChatPanel.css";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { TaskTriggerModal } from "@/components/task-domain";
import { PATHS } from "@/routes/paths";
import { taskModelQueryKeys } from "@/query";
import { useChatDraft } from "./useChatDraft";
import { useGroupReadAcknowledgement } from "./useGroupReadAcknowledgement";
import { chatScopeKey, useChatSessionStore } from "@/store/chatSessionStore";
import { useChatReadingPosition } from "./useChatReadingPosition";
import type {
  Message,
  Mention,
  MentionType,
  Page,
  FileMessageContent,
  DiffMessageContent,
  QuoteMessageContent,
} from "@/types";
import type { TaskMergeRequestPreflightList } from "@/types/task-model";

const { Text } = Typography;
type ChatSyncSignal = "incremental" | "reconcile" | "query" | "context" | "read";

// 增量按 ID 更新所有已加载页；新 ID 只追加一次，保留页边界和历史游标。
function mergeMessageUpdates(
  current: InfiniteData<Page<Message>>,
  incoming: Message[],
): InfiniteData<Page<Message>> {
  if (!current.pages.length) return current;
  const updates = new Map(incoming.map((item) => [item.id, item]));
  const seen = new Set<string>();
  const pages = current.pages.map((item) => ({
    ...item,
    data: item.data.flatMap((row) => {
      if (seen.has(row.id)) return [];
      seen.add(row.id);
      return [updates.get(row.id) ?? row];
    }),
  }));
  const additions = [...updates.values()].filter((row) => !seen.has(row.id));
  pages[0] = { ...pages[0], data: [...pages[0].data, ...additions] };
  return replaceEqualDeep(current, { ...current, pages });
}

// 回退已完整接回旧窗口后，合并恢复消息，保留已加载页和历史游标。
function mergeRecoveredMessages(
  current: InfiniteData<Page<Message>> | undefined,
  incoming: Message[],
  afterSequence: number,
): InfiniteData<Page<Message>> | undefined {
  if (!current?.pages.length) return current;
  const updates = new Map(incoming.map((item) => [item.id, item]));
  const known = new Set(current.pages.flatMap((item) => item.data).map((item) => item.id));
  const pages = current.pages.map((item) => ({
    ...item,
    data: item.data.map((row) => updates.get(row.id) ?? row),
  }));
  const additions = [...updates.values()].filter((row) =>
    !known.has(row.id) && row.sequence != null && row.sequence > afterSequence);
  pages[0] = { ...pages[0], data: [...pages[0].data, ...additions] };
  return replaceEqualDeep(current, { ...current, pages });
}

/**
 * 群聊聊天面板 —— 消息列表（含时间分隔线）+ @提及 + 发送 + 发起任务入口。
 * 供项目详情（RequirementChatPage）与「项目群聊」工作台（ChatWorkspacePage）复用。
 */
export function ChatPanel({
  projectId,
  groupId,
}: {
  projectId: string;
  groupId: string;
}) {
  const { token } = theme.useToken();
  const { message } = App.useApp();
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const { scope: chatScope, value: draftState, draft, mentions, setDraft, setMentions, setReplyToId } = useChatDraft(user?.id, projectId, groupId);
  const [sending, setSending] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [triggerOpen, setTriggerOpen] = useState(false);
  // 群聊设置栏（收纳成员管理 / AI 沉淀 / 归档等除「发起任务」外的操作）
  const [settingsOpen, setSettingsOpen] = useState(false);
  // 回复引用：选中某条消息后，输入区显示引用条，发送时以 QUOTE 类型 + replyToId 提交
  const replyQueryKey = useMemo(() => ["groups", projectId, groupId, "draft-reply", user?.id, draftState.replyToId], [projectId, groupId, user?.id, draftState.replyToId]);
  const replyQuery = useQuery({
    queryKey: replyQueryKey,
    queryFn: async () => {
      const target = await groupApi.getMessage(projectId, groupId, draftState.replyToId!);
      if (target.groupId !== groupId || target.id !== draftState.replyToId) throw new Error("引用消息不属于当前会话");
      return target;
    },
    enabled: Boolean(user?.id && draftState.replyToId),
    retry: false,
  });
  const replyTo = draftState.replyToId ? replyQuery.data ?? null : null;
  const setReplyTo = useCallback((target: Message | null) => {
    if (target) queryClient.setQueryData(["groups", projectId, groupId, "draft-reply", user?.id, target.id], target);
    setReplyToId(target?.id ?? null);
  }, [projectId, groupId, user?.id, queryClient, setReplyToId]);

  const quoteDiffBlockReason = useCallback(
    (preflight: TaskMergeRequestPreflightList): string | null => {
      for (const item of preflight.items) {
        const mergeStatus = item.mergeRequest?.status;
        if (mergeStatus === "MERGED") {
          return "该 Diff 对应的 MR 已合并，当前不能引用继续修改。";
        }
        if (mergeStatus === "CLOSED") {
          return "该 Diff 对应的 MR 已关闭，当前不能引用继续修改。";
        }
        if (mergeStatus === "OPEN" || item.status === "MR_CREATED") {
          return "该 Diff 已进入 MR 流程，当前不能引用继续修改。";
        }
        switch (item.status) {
          case "REQUESTED":
          case "DRY_RUN_QUEUED":
          case "DRY_RUN_RUNNING":
            return "当前 Diff 正在进行 MR 预检，请等待预检完成后再引用继续修改。";
          case "WAITING_CQ":
            return "当前 Diff 正在等待 CQ+1 审查，暂不能引用继续修改。";
          case "CREATING_MR":
            return "当前 Diff 正在创建 MR，暂不能引用继续修改。";
          default:
            break;
        }
      }
      return null;
    },
    [],
  );

  const handleReply = useCallback(
    async (target: Message) => {
      if (target.type !== "DIFF") {
        setReplyTo(target);
        return;
      }
      const content = target.content as DiffMessageContent;
      const taskId =
        typeof content.taskId === "string" ? content.taskId.trim() : "";
      if (!taskId) {
        message.error(
          "当前 Diff 缺少任务上下文，暂时无法引用继续修改，请刷新页面后重试。",
        );
        return;
      }
      try {
        const preflight = await queryClient.fetchQuery({
          queryKey: taskModelQueryKeys.mergeRequests.preflightByTask(
            projectId,
            taskId,
          ),
          queryFn: () => mergeRequestsApi.getTaskPreflight(projectId, taskId),
          staleTime: 0,
        });
        const blockReason = quoteDiffBlockReason(preflight);
        if (blockReason) {
          message.error(blockReason);
          return;
        }
        setReplyTo(target);
        const reviewReason =
          typeof content.reviewReason === "string"
            ? content.reviewReason.trim()
            : "";
        if (content.reviewStatus === "REJECTED" && reviewReason) {
          setDraft((current) =>
            current.trim()
              ? current
              : `请根据以下拒绝意见修改：\n${reviewReason}\n\n请继续修改：`,
          );
        }
        requestAnimationFrame(() => inputRef.current?.focus());
      } catch (error) {
        message.error(`暂时无法确认当前 Diff 状态：${formatApiError(error)}`);
      }
    },
    [message, projectId, queryClient, quoteDiffBlockReason, setReplyTo, setDraft],
  );
  // 附件内联预览（增量契约 §4/§5）：点击 IMAGE/FILE 打开页内预览弹窗
  const [previewTarget, setPreviewTarget] = useState<{
    attachmentId: string;
    fileName?: string;
    embeddedPreviewUrl?: string;
  } | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const syncQueueRef = useRef<ReturnType<typeof createChatSyncQueue<ChatSyncSignal>> | null>(null);
  const messageQueryKey = useMemo(
    () => ["groups", projectId, groupId, "messages"] as const,
    [projectId, groupId],
  );
  const readScope = `${chatScope.userId}/${chatScope.epoch}/${projectId}/${groupId}`;
  const savedReading = useMemo(() => useChatSessionStore.getState().positions[chatScopeKey(chatScope)], [chatScope]);
  const explicitNavigation = Boolean((location.state as { mentionMessageId?: string; autoJumpMention?: boolean } | null)?.mentionMessageId ||
    (location.state as { autoJumpMention?: boolean } | null)?.autoJumpMention);
  const explicitNavigationRef = useRef(explicitNavigation);
  explicitNavigationRef.current = explicitNavigation;
  const [historyTarget, setHistoryTarget] = useState<{ scope: string; id: string; navigationKey: string; latestId?: string } | null>(null);
  const contextTarget = historyTarget?.scope === readScope ? historyTarget.id : null;
  const historyModeRef = useRef<string | null>(null);
  historyModeRef.current = contextTarget;
  useLayoutEffect(() => {
    setHistoryTarget(!explicitNavigationRef.current && savedReading?.contextMessageId
      ? { scope: readScope, id: savedReading.contextMessageId, navigationKey: `resume/${readScope}` } : null);
    // Resume selection is applied once per account/project/group entry; new notification navigation takes precedence.
  }, [readScope, savedReading]);
  const readSession = useMemo(() => ({ scope: readScope }), [readScope]);
  const activeReadScopeRef = useRef<typeof readSession | null>(readSession);
  activeReadScopeRef.current = readSession;
  useEffect(() => {
    activeReadScopeRef.current = readSession;
    return () => {
      if (activeReadScopeRef.current === readSession) activeReadScopeRef.current = null;
    };
  }, [readSession]);
  const [readStatus, setReadStatus] = useState({
    scope: readScope,
    historyFailed: false,
    queryFailed: false,
    incrementalFailed: false,
    reconcileFailed: false,
    queryRetryPending: false,
    incrementalPending: false,
    reconcilePending: false,
  });
  const readStatusRef = useRef(readStatus);
  const updateReadStatus = useCallback(
    (session: typeof readSession, patch: Partial<Omit<typeof readStatus, "scope">>) => {
      if (activeReadScopeRef.current !== session) return;
      const scope = session.scope;
      const previous = readStatusRef.current;
      const next = {
        ...(previous.scope === scope ? previous : {
          scope,
          historyFailed: false,
          queryFailed: false,
          incrementalFailed: false,
          reconcileFailed: false,
          queryRetryPending: false,
          incrementalPending: false,
          reconcilePending: false,
        }),
        ...patch,
      };
      readStatusRef.current = next;
      setReadStatus(next);
    },
    [],
  );
  // 群聊 @ 提及：已读游标（markRead 返回）与「有人@我」提示条
  const [readCursor, setReadCursor] = useState<{ session: typeof readSession; sequence: number | null } | null>(null);
  const lastReadSeq = readCursor?.session === readSession ? readCursor.sequence : null;
  const [mentionFlashId, setMentionFlashId] = useState<string | null>(null);
  // 仅记录本次会话已定位的提示，不推进后端已读游标。
  const [handledMentions, setHandledMentions] = useState<{ session: typeof readSession; ids: ReadonlySet<string> } | null>(null);
  // 消息列表内部真正承载消息的内容容器（ResizeObserver 监听其高度变化）
  const contentRef = useRef<HTMLDivElement>(null);
  // 用户是否「应该保持贴底」：发送消息/切群/首载时置 true；用户主动上滚查看历史时置 false。
  // 图片加载完成、内容高度变化时据此决定是否自动滚到底，避免把看历史的用户拉回底部。
  const shouldStickToBottomRef = useRef(true);
  // CHAT-005：阅读历史时到达的新消息。纯前端计数，不改变 markRead 的服务端含义。
  // 只有「同一会话内、尾部继续追加」才计入；头部前插历史、切群、缓存整体替换都不计入。
  const [unseenCount, setUnseenCountState] = useState(0);
  const unseenCountRef = useRef(0);
  const setUnseenCount = useCallback((next: number) => {
    if (unseenCountRef.current === next) return;
    unseenCountRef.current = next;
    setUnseenCountState(next);
  }, []);
  // 上一次已渲染到的尾部消息 id：用它区分「尾部新消息」与「头部前插的历史」。
  const seenTailRef = useRef<{ session: typeof readSession; id: string | null } | null>(null);
  const [readingResetToken, setReadingResetToken] = useState(0);
  const returnToLatest = useCallback(() => {
    historyModeRef.current = null;
    useChatSessionStore.getState().savePosition(chatScope, { atBottom: true, offset: 0, scrollTop: 0 });
    shouldStickToBottomRef.current = true;
    setUnseenCount(0);
    setHistoryTarget(null);
    setReadingResetToken((value) => value + 1);
  }, [chatScope, setUnseenCount]);
  const contentUpdateAnchorRef = useRef<{
    session: typeof readSession;
    messageId?: string;
    offset: number;
    scrollTop: number;
  } | null>(null);
  const rememberReadingPosition = useCallback(() => {
    const el = listRef.current;
    if (!el || shouldStickToBottomRef.current) return;
    const top = el.getBoundingClientRect().top;
    const row = [...el.querySelectorAll<HTMLElement>('[id^="msg-"]')]
      .find((item) => item.getBoundingClientRect().bottom > top);
    contentUpdateAnchorRef.current = {
      session: readSession,
      messageId: row?.id,
      offset: row ? row.getBoundingClientRect().top - top : 0,
      scrollTop: el.scrollTop,
    };
  }, [readSession]);
  // 本次发送后需要强制滚到底（即使布局滚动事件把 stick 标志重算为 false 也照滚），待消息渲染后清除。
  const pendingScrollRef = useRef(false);
  // 消息输入框 ref：右键「@ta」后聚焦进入编辑态
  const inputRef = useRef<TextAreaRef>(null);
  // 右键成员消息的上下文菜单（@ta）：记录触发位置与目标消息
  const [ctxMenu, setCtxMenu] = useState<{
    x: number;
    y: number;
    message: Message;
  } | null>(null);
  const { pollingInterval: taskPollingInterval, connectionStatus } = useProjectTaskSyncState(projectId, 5_000);

  const { data: groups = [] } = useQuery({
    queryKey: ["groups", projectId],
    queryFn: () => groupApi.listByProject(projectId),
    enabled: !!projectId,
  });
  const group = groups.find((g) => g.id === groupId);
  const mainGroup = groups.find((g) => g.type === "PROJECT_MAIN");

  // 归档需求群（仅创建者可见，Project Admin 兜底后端校验）
  const archiveGroup = useMutation({
    mutationFn: () => groupApi.archive(projectId, groupId),
    onSuccess: () => {
      invalidateChatSummary(queryClient, ["groups", projectId]);
      if (mainGroup) navigate(PATHS.projectReqChat(projectId, mainGroup.id));
    },
  });

  // 群成员（项目成员 + Agent），@ 提及用户候选来源
  const { data: members = [] } = useQuery({
    queryKey: ["groups", projectId, groupId, "members"],
    queryFn: () => groupApi.listMembers(projectId, groupId),
    enabled: !!projectId && !!groupId,
  });
  const currentUserId = user?.id;
  const userMembers = members.filter((m) => m.memberType === "USER");
  // 过滤掉自己的用户（Agent 保留，因为没有"自己"）
  const otherUserMembers = userMembers.filter((m) => m.id !== currentUserId);

  // @ Agent 候选来源：团队 Agent 列表（不依赖群成员，v1.8.0 §7/§22）
  const { data: project } = useQuery({
    queryKey: ["projects", projectId],
    queryFn: () => projectApi.getById(projectId),
    enabled: !!projectId,
  });
  const { data: taskPage } = useQuery({
    queryKey: taskModelQueryKeys.tasks.list(projectId, { limit: 100 }),
    queryFn: () => tasksApi.list(projectId, { limit: 100 }),
    enabled: !!projectId,
    refetchInterval: taskPollingInterval,
  });
  const taskStatusById = new Map(
    (taskPage?.data ?? []).map((task) => [task.id, task.status]),
  );
  const teamId = project?.teamId;
  // @ Agent 候选：走 useAgents hook（queryKeys.agents.list 前缀），
  // 与 AgentTeamPage 的 create/publish/archive mutation invalidate 的 queryKeys.agents.all 对齐，
  // 避免内联 key 分裂导致 @ 候选列表永不刷新
  const { data: agentsPage } = useAgents(projectId, teamId);
  // 仅展示可被 @ 的 Agent（ACTIVE 状态）
  const teamAgents = (agentsPage?.data ?? []).filter(
    (a) => a.status === "ACTIVE" && a.name === "编排助手",
  );

  const messageQueryOptions = useMemo(() => infiniteQueryOptions({
    queryKey: messageQueryKey,
    queryFn: ({ pageParam }) => groupApi.listMessages(projectId, groupId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage: Page<Message>) =>
      lastPage.page.hasMore ? lastPage.page.nextCursor : undefined,
    enabled: !!projectId && !!groupId,
    // 网络恢复由当前群调度器补增量及校准；首载的离线暂停仍由 Query 自身恢复。
    refetchOnReconnect: false,
  }), [messageQueryKey, projectId, groupId]);
  const {
    data: page,
    isLoading,
    isLoadingError,
    fetchNextPage,
    refetch: refetchMessages,
    hasNextPage,
    isFetchingNextPage,
    isRefetching,
  } = useInfiniteQuery(messageQueryOptions);
  // 只让对应 REST 操作的终态清错；增量 setQueryData 的 manual success
  // 不能抹掉仍未恢复的历史分页或整页同步失败。
  useEffect(() => {
    const cached = queryClient.getQueryState(messageQueryKey);
    const isHistory = cached?.fetchMeta?.fetchMore?.direction === "forward";
    updateReadStatus(readSession, {
      historyFailed: cached?.status === "error" && cached.data !== undefined && isHistory,
      queryFailed: cached?.status === "error" && cached.data !== undefined && !isHistory,
    });
    return queryClient.getQueryCache().subscribe((event) => {
      if (event.type !== "updated" ||
        event.query.queryKey.length !== messageQueryKey.length ||
        !messageQueryKey.every((part, index) => event.query.queryKey[index] === part)) return;
      const { action, query } = event;
      const history = query.state.fetchMeta?.fetchMore?.direction === "forward";
      if (action.type === "error" && query.state.data !== undefined) {
        updateReadStatus(readSession, history ? { historyFailed: true } : { queryFailed: true });
      } else if (action.type === "success" && !action.manual) {
        updateReadStatus(readSession, history ? { historyFailed: false } : { queryFailed: false });
      }
    });
  }, [messageQueryKey, queryClient, readSession, updateReadStatus]);
  const hasMessageCache = page !== undefined;
  const currentReadStatus = readStatus.scope === readScope ? readStatus : undefined;
  const historyFailed = currentReadStatus?.historyFailed ?? false;
  const querySyncFailed = currentReadStatus?.queryFailed ?? false;
  const incrementalFailed = currentReadStatus?.incrementalFailed ?? false;
  const reconcileFailed = currentReadStatus?.reconcileFailed ?? false;
  const syncFailed = querySyncFailed || incrementalFailed || reconcileFailed;
  const syncPending = isRefetching || currentReadStatus?.queryRetryPending ||
    currentReadStatus?.incrementalPending || currentReadStatus?.reconcilePending;
  const refreshMessageQuery = useCallback(async (isCurrent: () => boolean = () => true): Promise<boolean> => {
    const currentQuery = queryClient.getQueryCache().find({ queryKey: messageQueryKey, exact: true });
    if (currentQuery?.state.fetchMeta?.fetchMore?.direction === "forward") {
      // cancelRefetch:false 会复用历史请求；先等它结束，随后明确发起最新消息重查。
      await currentQuery.promise?.catch(() => undefined);
    }
    if (!isCurrent() || activeReadScopeRef.current !== readSession) return false;
    // fetchInfiniteQuery 等待实际请求（包括离线暂停后恢复），不会将 paused 当作成功。
    await queryClient.fetchInfiniteQuery({ ...messageQueryOptions, staleTime: 0 });
    return isCurrent() && activeReadScopeRef.current === readSession;
  }, [messageQueryKey, messageQueryOptions, queryClient, readSession]);
  // 通知单条与历史页可以重叠：先按 ID 去重，再按 sequence / createdAt 排序。
  // 保留靠前缓存页的副本，避免后拼入的旧历史覆盖已校准卡片；显式更新由合并入口处理。
  const messages = useMemo(() => {
    const list = page?.pages.flatMap((p) => p.data) ?? [];
    const seen = new Set<string>();
    return list.filter((item) => {
      if (seen.has(item.id)) return false;
      seen.add(item.id);
      return true;
    }).sort((a, b) => {
      const as = a.sequence ?? Number.MAX_SAFE_INTEGER;
      const bs = b.sequence ?? Number.MAX_SAFE_INTEGER;
      if (as !== bs) return as - bs;
      return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
    });
  }, [page]);

  // 所有入口由同一队列串行调用；保留直接增量请求独立的失败/重试语义。
  const syncIncrementalMessages = useCallback(async (isCurrent: () => boolean): Promise<void> => {
      if (!isCurrent()) return;
      updateReadStatus(readSession, { incrementalPending: true });
      try {
        const cached = queryClient.getQueryData<InfiniteData<Page<Message>>>(messageQueryKey);
        let afterSequence = (cached?.pages.flatMap((item) => item.data) ?? [])
          .reduce((max, item) => Math.max(max, item.sequence ?? 0), 0);
        const received: Message[] = [];
        let lastPage: Page<Message> | undefined;
        while (isCurrent()) {
          // 后端接口为 /messages/incremental?afterSequence=N（listMessagesIncremental）
          const result = await groupApi.listMessagesIncremental(
            projectId,
            groupId,
            afterSequence,
          );
          lastPage = result;
          received.push(...result.data);
          if (!result.page.hasMore) break;
          const nextSequence = Number(result.page.nextCursor);
          if (
            !Number.isSafeInteger(nextSequence) ||
            nextSequence <= afterSequence
          )
            throw new Error("增量游标未推进");
          afterSequence = nextSequence;
        }
        if (!isCurrent()) return;
        updateReadStatus(readSession, { incrementalFailed: false });
        if (received.length === 0) return;
        rememberReadingPosition();
        queryClient.setQueryData<InfiniteData<Page<Message>>>(messageQueryKey, (old) => {
          if (!old?.pages.length || !lastPage) {
            return {
              pages: [{ ...(lastPage as Page<Message>), data: [...new Map(received.map((item) => [item.id, item])).values()] }],
              pageParams: [undefined],
            };
          }
          return mergeMessageUpdates(old, received);
        });
      } catch {
        if (isCurrent()) updateReadStatus(readSession, { incrementalFailed: true });
      } finally {
        if (isCurrent()) updateReadStatus(readSession, { incrementalPending: false });
      }
  }, [projectId, groupId, messageQueryKey, queryClient, readSession, updateReadStatus, rememberReadingPosition]);

  // 发送者头像（微信式，边缘展示）：优先群成员 avatarUrl，自己用当前用户头像；SYSTEM 无头像
  const memberAvatarById = new Map(
    members
      .filter((mem) => mem.avatarUrl)
      .map((mem) => [mem.id, mem.avatarUrl as string]),
  );
  function resolveSenderAvatar(m: Message): string | undefined {
    if (m.senderType === "SYSTEM") return undefined;
    if (m.senderType === "USER" && m.senderId === user?.id)
      return user?.avatarUrl;
    return m.senderId ? memberAvatarById.get(m.senderId) : undefined;
  }

  // 无条件滚到底部：切群 / 首次加载 / 自己发送新消息时使用
  const scrollToBottom = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight;
    });
  }, []);

  const prevScrollAnchorRef = useRef<{
    session: typeof readSession;
    messageId?: string;
    offset: number;
    scrollTop: number;
  } | null>(null);
  const historyRequestRef = useRef<{ session: typeof readSession } | null>(null);
  const loadEarlierMessages = useCallback(async () => {
    const el = listRef.current;
    if (!el || !hasNextPage || isFetchingNextPage || isRefetching ||
      historyRequestRef.current?.session === readSession) return;
    const request = { session: readSession };
    historyRequestRef.current = request;
    const viewportTop = el.getBoundingClientRect().top;
    const visibleMessage = [...el.querySelectorAll<HTMLElement>('[id^="msg-"]')]
      .find((row) => row.getBoundingClientRect().bottom > viewportTop);
    prevScrollAnchorRef.current = {
      session: readSession,
      messageId: visibleMessage?.id,
      offset: visibleMessage ? visibleMessage.getBoundingClientRect().top - viewportTop : 0,
      scrollTop: el.scrollTop,
    };
    shouldStickToBottomRef.current = false;
    try {
      await fetchNextPage({ cancelRefetch: false });
    } catch {
      updateReadStatus(readSession, { historyFailed: true });
    } finally {
      if (historyRequestRef.current === request) historyRequestRef.current = null;
    }
  }, [hasNextPage, isFetchingNextPage, isRefetching, readSession, fetchNextPage, updateReadStatus]);

  // 单张图片加载完成：如果用户「应该保持贴底」，则重新滚到底（rAF 等布局稳定后再滚）。
  // 多张图片时每张触发一次，第 1 张、第 2 张……依次改变高度都能重新贴底。
  const handleImageLoad = useCallback(() => {
    if (!shouldStickToBottomRef.current) return;
    const el = listRef.current;
    if (!el) return;
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight;
    });
  }, []);

  // 用户滚动消息列表：据此更新「是否应该保持贴底」。
  // 接近底部 → 贴底；明显上翻查看历史 → 不贴底（图片加载也不拉回）。
  // 滚到顶部且还有更早的历史 → 触发分页加载（cursor 翻页，往上加载更早消息）。
  const handleScroll = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    shouldStickToBottomRef.current =
      el.scrollHeight - el.scrollTop - el.clientHeight <= 80;
    // 回到最新（用户自己滚到底也算）即视为已见，本地提示随之消失
    if (shouldStickToBottomRef.current && unseenCountRef.current > 0) {
      setUnseenCount(0);
    }
    // 顶部触发加载更早历史：scrollTop 接近 0 且还有下一页，且不在加载中
    if (el.scrollTop <= 24 && !historyFailed) {
      void loadEarlierMessages();
    }
  }, [historyFailed, loadEarlierMessages, setUnseenCount]);

  // 成功前插历史后恢复同一可见消息；失败时消息节点和高度保持，不重建列表。
  useLayoutEffect(() => {
    const el = listRef.current;
    const anchor = prevScrollAnchorRef.current;
    if (!el || isFetchingNextPage || !anchor || anchor.session !== readSession) return;
    const row = anchor.messageId ? document.getElementById(anchor.messageId) : null;
    if (row && el.contains(row)) {
      el.scrollTop += row.getBoundingClientRect().top - el.getBoundingClientRect().top - anchor.offset;
    } else {
      el.scrollTop = anchor.scrollTop;
    }
    prevScrollAnchorRef.current = null;
  }, [isFetchingNextPage, page, historyFailed, readSession]);

  // 卡片正文/步骤变高时也恢复阅读锚点；在写缓存前采样，允许请求中继续滚动。
  useLayoutEffect(() => {
    const el = listRef.current;
    const anchor = contentUpdateAnchorRef.current;
    if (!el || !anchor || anchor.session !== readSession) return;
    const row = anchor.messageId ? document.getElementById(anchor.messageId) : null;
    if (row && el.contains(row)) {
      el.scrollTop += row.getBoundingClientRect().top - el.getBoundingClientRect().top - anchor.offset;
    } else {
      el.scrollTop = anchor.scrollTop;
    }
    contentUpdateAnchorRef.current = null;
  }, [page, readSession]);

  // ResizeObserver：监听消息内容容器高度变化（图片加载、新消息渲染都会改变高度）。
  // 辅助机制：图片 onLoad 是主信号，RO 兜底覆盖「图片失败/懒加载等无 onLoad 场景」。
  useEffect(() => {
    const el = listRef.current;
    const content = contentRef.current;
    if (!el || !content) return;
    const observer = new ResizeObserver(() => {
      if (shouldStickToBottomRef.current) {
        el.scrollTop = el.scrollHeight;
      }
    });
    observer.observe(content);
    return () => observer.disconnect();
  }, [messages.length, isLoading, contextTarget]);

  const readingNotice = useChatReadingPosition({
    scope: chatScope, viewportRef: listRef, contentRef,
    ready: hasMessageCache, data: page, enabled: !contextTarget,
    stickRef: shouldStickToBottomRef, resetToken: readingResetToken, ignoreSaved: explicitNavigation,
  });

  // 切群 / 切账号：重建基线，避免把上一个群（或上一账号）的尾部误当成新消息
  useEffect(() => {
    seenTailRef.current = null;
    setUnseenCount(0);
  }, [readScope, setUnseenCount]);

  // 点击「N 条新消息」：回到最新并恢复自动跟随；不推进服务端已读（仍由既有已读规则负责）
  const jumpToLatest = useCallback(() => {
    shouldStickToBottomRef.current = true;
    // 先写回「贴底」：滚动事件是异步的，避免这两步之间卸载时把旧锚点存回去
    useChatSessionStore
      .getState()
      .savePosition(chatScope, { atBottom: true, offset: 0, scrollTop: 0 });
    setUnseenCount(0);
    scrollToBottom();
  }, [chatScope, scrollToBottom, setUnseenCount]);

  // 消息变化 / 切换群聊：
  // - 切群、首次加载 → 无条件滚到底（并把贴底标志置 true）
  // - 新消息到达（含自己发送）→ 用户之前在底部则滚，在查看历史只累计本地提示
  // - 本群刚发送新消息（pendingScrollRef）→ 强制滚到底，忽略滚动事件把 stick 标志重算成 false
  const lastMessageId = messages[messages.length - 1]?.id;
  useEffect(() => {
    if (contextTarget) {
      seenTailRef.current = null;
      return;
    }
    if (messages.length === 0) {
      seenTailRef.current = { session: readSession, id: null };
      setUnseenCount(0);
      return;
    }
    const tailId = messages[messages.length - 1].id;
    const previous = seenTailRef.current;
    const keepSticking =
      pendingScrollRef.current || shouldStickToBottomRef.current;
    // 本来就在底部（或刚切群、首次加载）→ 重置，不产生提示
    if (keepSticking || previous?.session !== readSession) {
      setUnseenCount(0);
    } else if (previous?.id && previous.id !== tailId) {
      const seenIndex = messages.findIndex((m) => m.id === previous.id);
      // 找不到上一条尾部说明缓存被整体替换，无法可靠计算增量，只重建基线
      if (seenIndex >= 0) {
        const arrived = messages.length - seenIndex - 1;
        if (arrived > 0) setUnseenCount(unseenCountRef.current + arrived);
      }
    }
    seenTailRef.current = { session: readSession, id: tailId };
    if (keepSticking) {
      pendingScrollRef.current = false;
      scrollToBottom();
    }
    // 切群时强制贴底：groupId 变化代表进入新群，无视历史滚动位置
    // 通过重置标志 + 无条件滚动实现
  }, [messages.length, lastMessageId, groupId, readSession, scrollToBottom, contextTarget, setUnseenCount]);

  // 切群关闭菜单；阅读位置由会话锚点恢复，首次进入才贴底。
  useEffect(() => {
    // 右键 @ta 菜单随群切换关闭，避免残留到新群的旧坐标菜单
    setCtxMenu(null);
  }, [readScope]);

  const onReadAcknowledged = useCallback((sequence: number | null) => {
    if (activeReadScopeRef.current === readSession) setReadCursor({ session: readSession, sequence });
  }, [readSession]);
  const readAcknowledgement = useGroupReadAcknowledgement({
    projectId, groupId, session: readSession,
    ready: hasMessageCache,
    initialReady: hasMessageCache && !syncFailed && !syncPending,
    onRead: onReadAcknowledged,
  });
  const markGroupReadNow = readAcknowledgement.markRead;
  // v2.0.23 §23：TASK_STATUS/DIFF 原地改 content，不会增加 sequence。
  // 按原已加载卡片 ID 校准，避免最新页移动后漏掉正在阅读的旧卡片。
  const refreshLoadedCards = useCallback(async (
    snapshot: InfiniteData<Page<Message>> | undefined,
    isCurrent: () => boolean,
  ): Promise<boolean> => {
    const cards = [...new Map((snapshot?.pages.flatMap((item) => item.data) ?? [])
      .filter((item) => item.type === "TASK_STATUS" || item.type === "DIFF")
      .map((item) => [item.id, item])).values()];
    const before = new Set(queryClient.getQueryData<InfiniteData<Page<Message>>>(messageQueryKey)
      ?.pages.flatMap((item) => item.data) ?? []);
    const updates = new Map<string, Message>();
    for (let start = 0; start < cards.length; start += 4) {
      if (!isCurrent() || activeReadScopeRef.current !== readSession) return false;
      const results = await Promise.all(cards.slice(start, start + 4)
        .map((item) => groupApi.getMessage(projectId, groupId, item.id)));
      for (const item of results) updates.set(item.id, item);
    }
    if (!isCurrent() || activeReadScopeRef.current !== readSession) return false;
    if (updates.size > 0) {
      rememberReadingPosition();
      queryClient.setQueryData<InfiniteData<Page<Message>>>(messageQueryKey, (current) => {
        if (!current) return current;
        return {
          ...current,
          pages: current.pages.map((item) => ({
            ...item,
            // 读取期间其他实时查询已替换的卡片，不能被旧响应覆盖。
            data: item.data.map((row) => before.has(row) ? updates.get(row.id) ?? row : row),
          })),
        };
      });
    }
    return true;
  }, [groupId, projectId, messageQueryKey, queryClient, readSession, rememberReadingPosition]);

  // 增量不可用时，历史回退必须接回原最大序号，不能只取最新一页就宣称补齐。
  const recoverMessagesFromHistory = useCallback(async (afterSequence: number, isCurrent: () => boolean): Promise<boolean> => {
    const rows = await queryClient.fetchQuery({
      queryKey: [...messageQueryKey, "recovery", afterSequence],
      staleTime: 0,
      queryFn: async (): Promise<Message[]> => {
        const recovered: Message[] = [];
        const cursors = new Set<string>();
        let cursor: string | undefined;
        while (true) {
          if (!isCurrent() || activeReadScopeRef.current !== readSession) throw new Error("已切换群聊，停止旧群同步");
          const result = await groupApi.listMessages(projectId, groupId, cursor, 100);
          recovered.push(...result.data);
          if (result.data.some((item) => item.sequence != null && item.sequence <= afterSequence) ||
            !result.page.hasMore) return recovered;
          const nextCursor = result.page.nextCursor;
          if (!nextCursor || cursors.has(nextCursor)) throw new Error("历史游标未推进，同步尚未完成");
          cursors.add(nextCursor);
          cursor = nextCursor;
        }
      },
    });
    if (!isCurrent() || activeReadScopeRef.current !== readSession) return false;
    // 与历史前插串行，合并时读取最新缓存，保留请求期间加载的旧页与新消息。
    const historyQuery = queryClient.getQueryCache().find({ queryKey: messageQueryKey, exact: true });
    if (historyQuery?.state.fetchMeta?.fetchMore?.direction === "forward") {
      await historyQuery.promise?.catch(() => undefined);
    }
    if (!isCurrent() || activeReadScopeRef.current !== readSession) return false;
    rememberReadingPosition();
    queryClient.setQueryData<InfiniteData<Page<Message>>>(messageQueryKey,
      (current) => mergeRecoveredMessages(current, rows, afterSequence));
    return true;
  }, [groupId, projectId, messageQueryKey, queryClient, readSession, rememberReadingPosition]);

  /** 补新增消息，再按 ID 校准原窗口卡片；增量为空也不能跳过内容校准。 */
  const reconcileMessages = useCallback(async (isCurrent: () => boolean): Promise<boolean> => {
    if (!projectId || !groupId || !isCurrent()) return false;
    updateReadStatus(readSession, { reconcilePending: true });
    try {
      // useInfiniteQuery 缓存结构为 { pages, pageParams }：取全部页的消息算最大 sequence
      const cached = queryClient.getQueryData<InfiniteData<Page<Message>>>([
        "groups",
        projectId,
        groupId,
        "messages",
      ]);
      const all = cached?.pages.flatMap((p) => p.data) ?? [];
      const sequences = all
        .map((m) => m.sequence)
        .filter((s): s is number => typeof s === "number");
      const maxSequence = sequences.length > 0 ? Math.max(...sequences) : null;

      if (maxSequence == null) {
        // 本地没有 sequence 信息（旧数据/首次）→ 整页重拉
        const recovered = await refreshMessageQuery(isCurrent) && await refreshLoadedCards(cached, isCurrent);
        if (recovered) {
          updateReadStatus(readSession, { reconcileFailed: false, incrementalFailed: false });
        }
        invalidateChatSummary(queryClient, ["groups", projectId]);
        invalidateChatSummary(queryClient, ["chat", "main-groups"]);
        return recovered;
      }

      let incremental: Page<Message>;
      try {
        let afterSequence = maxSequence;
        const received: Message[] = [];
        while (true) {
          if (!isCurrent() || activeReadScopeRef.current !== readSession) return false;
          const part = await groupApi.listMessagesIncremental(projectId, groupId, afterSequence);
          received.push(...part.data);
          if (!part.page.hasMore) {
            incremental = { ...part, data: received };
            break;
          }
          const next = Number(part.page.nextCursor);
          if (!Number.isSafeInteger(next) || next <= afterSequence) throw new Error("增量游标未推进");
          afterSequence = next;
        }
      } catch {
        // 历史回退完整接回旧窗口，再校准原窗口的可更新卡片。
        if (!isCurrent() || activeReadScopeRef.current !== readSession) return false;
        updateReadStatus(readSession, { reconcileFailed: true });
        const recovered = await recoverMessagesFromHistory(maxSequence, isCurrent) && await refreshLoadedCards(cached, isCurrent);
        if (recovered) {
          updateReadStatus(readSession, { reconcileFailed: false, incrementalFailed: false });
        }
        invalidateChatSummary(queryClient, ["groups", projectId]);
        invalidateChatSummary(queryClient, ["chat", "main-groups"]);
        return recovered;
      }
      if (!isCurrent() || activeReadScopeRef.current !== readSession) return false;
      updateReadStatus(readSession, { incrementalFailed: false });
      if (incremental.data.length > 0) {
        // 新消息只补入最新页；已有卡片内容由下面的独立 REST 校准负责。
        queryClient.setQueryData<InfiniteData<Page<Message>>>(
          ["groups", projectId, groupId, "messages"],
          (prev) => {
            if (!prev?.pages.length) {
              return {
                pages: [{ ...incremental, data: [...new Map(incremental.data.map((item) => [item.id, item])).values()] }],
                pageParams: [undefined],
              };
            }
            return mergeMessageUpdates(prev, incremental.data);
          },
        );
      }
      const calibrated = await refreshLoadedCards(cached, isCurrent);
      if (calibrated) updateReadStatus(readSession, { reconcileFailed: false });
      invalidateChatSummary(queryClient, ["groups", projectId]);
      invalidateChatSummary(queryClient, ["chat", "main-groups"]);
      return calibrated;
    } catch {
      if (isCurrent()) updateReadStatus(readSession, { reconcileFailed: true });
      return false;
    } finally {
      if (isCurrent()) updateReadStatus(readSession, { reconcilePending: false });
    }
  }, [projectId, groupId, queryClient, readSession, updateReadStatus, refreshMessageQuery, refreshLoadedCards, recoverMessagesFromHistory]);

  // 更新信号也刷新独立历史窗口；先等分页结束，不取消用户正在读取的历史请求。
  const refreshContextMessages = useCallback(async (isCurrent: () => boolean) => {
    const contexts = queryClient.getQueryCache().findAll({ queryKey: [...messageQueryKey, "context"], type: "active" });
    for (const context of contexts) {
      await context.promise?.catch(() => undefined);
      if (!isCurrent() || !context.isActive()) return;
      await queryClient.refetchQueries({ queryKey: context.queryKey, exact: true, type: "active" }, { cancelRefetch: false });
    }
  }, [messageQueryKey, queryClient]);

  const retryMessageSync = () => {
    if (syncPending) return;
    if (querySyncFailed) syncQueueRef.current?.request("query");
    if (incrementalFailed) syncQueueRef.current?.request("incremental");
    if (reconcileFailed) syncQueueRef.current?.request("reconcile");
  };

  // WS/SSE、首载补偿、重连、回前台和错误重试统一排队；恢复批次包含增量和旧卡片校准。
  useEffect(() => {
    if (!projectId || !groupId) return;
    let stopped = false;
    const isCurrent = () => !stopped && activeReadScopeRef.current === readSession;
    const queue = createChatSyncQueue<ChatSyncSignal>(async (signals) => {
      if (!isCurrent()) return;
      let recovered = false;
      if (signals.has("reconcile")) recovered = await reconcileMessages(isCurrent);
      else if (signals.has("incremental")) await syncIncrementalMessages(isCurrent);
      if (!isCurrent()) return;
      if (signals.has("query")) {
        updateReadStatus(readSession, { queryRetryPending: true });
        try { await refreshMessageQuery(isCurrent); }
        catch { /* Query 保留失败类型及内容，不由其他同步成功清错。 */ }
        finally { if (isCurrent()) updateReadStatus(readSession, { queryRetryPending: false }); }
      }
      if (isCurrent() && signals.has("context")) await refreshContextMessages(isCurrent);
      if (isCurrent() && recovered && signals.has("read") &&
        !readStatusRef.current.queryFailed && !readStatusRef.current.incrementalFailed && !readStatusRef.current.reconcileFailed &&
        !queryClient.getQueryCache().findAll({ queryKey: [...messageQueryKey, "context"], type: "active" }).some((query) => query.state.status === "error")) {
        void markGroupReadNow(true);
      }
    }, () => { if (isCurrent()) updateReadStatus(readSession, { reconcileFailed: true }); });
    syncQueueRef.current = queue;
    const onMessageEvent = (event: Event) => {
      const detail = (event as CustomEvent<{ projectId?: string; groupId?: string; eventType?: string }>).detail;
      if (detail?.projectId !== projectId || detail.groupId !== groupId) return;
      // 仅当前群认领；全局层仍将未挂载群标记失效，并刷新摘要。
      event.preventDefault();
      if (detail.eventType === "task.updated") {
        queue.request("incremental");
        for (const delay of [500, 1500, 3000]) queue.defer(`task-${delay}`, delay, "incremental");
      } else if (detail.eventType === "message.updated") {
        queue.request("query", "context");
      } else {
        queue.request("incremental", "context");
      }
    };
    const onReconnect = () => queue.request("reconcile", "context");
    const onChatResync = (event: Event) => {
      if ((event as CustomEvent<{ projectId: string }>).detail?.projectId !== projectId) return;
      event.preventDefault();
      onReconnect();
    };
    const onVisible = () => {
      if (document.visibilityState !== "visible") return;
      queue.request("reconcile", "context", "read");
    };
    const unsubscribe = subscribeRealtimeReconnect(onReconnect);
    const unsubscribeOnline = onlineManager.subscribe((online) => {
      if (online && queryClient.getQueryData(messageQueryKey) !== undefined) onReconnect();
    });
    window.addEventListener("qgents:message-event", onMessageEvent);
    window.addEventListener("qgents:realtime-reconnected", onReconnect);
    window.addEventListener("qgents:chat-resync", onChatResync);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      stopped = true;
      queue.dispose();
      if (syncQueueRef.current === queue) syncQueueRef.current = null;
      unsubscribe();
      unsubscribeOnline();
      window.removeEventListener("qgents:message-event", onMessageEvent);
      window.removeEventListener("qgents:realtime-reconnected", onReconnect);
      window.removeEventListener("qgents:chat-resync", onChatResync);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [projectId, groupId, readSession, messageQueryKey, queryClient, reconcileMessages, syncIncrementalMessages, refreshMessageQuery, refreshContextMessages, updateReadStatus, markGroupReadNow]);

  // 首屏完成后补一次，覆盖事件早于首载缓存的竞态；StrictMode 重挂载也应补查。
  const initialSyncSessionRef = useRef<typeof readSession | null>(null);
  useEffect(() => {
    if (!hasMessageCache || initialSyncSessionRef.current === readSession) return;
    initialSyncSessionRef.current = readSession;
    syncQueueRef.current?.request("incremental");
    return () => {
      if (initialSyncSessionRef.current === readSession) initialSyncSessionRef.current = null;
    };
  }, [hasMessageCache, readSession]);

  // @ 提及面板：最后一个 @ 到行尾无空格时弹出；@ 后输入的字符作为候选过滤关键词（如 @张 → 只剩名字带「张」）
  const lastAt = draft.lastIndexOf("@");
  const mentionOpen = lastAt >= 0 && !draft.slice(lastAt).includes(" ");
  const mentionQuery =
    lastAt >= 0
      ? draft
          .slice(lastAt + 1)
          .toLowerCase()
          .trim()
      : "";
  const filteredAgents = teamAgents.filter(
    (a) => !mentionQuery || a.name.toLowerCase().includes(mentionQuery),
  );
  const filteredUsers = otherUserMembers.filter(
    (m) => !mentionQuery || m.displayName.toLowerCase().includes(mentionQuery),
  );

  // 未读「@ 我」消息列表（升序）：seq > 已读游标 且 mentions 含我 且非本人发送；
  // lastReadSeq 为空（已读确认完成前）不提示，避免把历史 @ 消息当未读
  const mentionMessages = useMemo(() => {
    if (lastReadSeq == null || !currentUserId) return [];
    return messages.filter(
      (m) =>
        m.senderType !== "AGENT" &&
        m.senderId !== currentUserId &&
        (m.sequence ?? 0) > lastReadSeq &&
        (m.mentions ?? []).some(
          (mention) => mention.type === "USER" && mention.id === currentUserId,
        ),
    );
  }, [messages, lastReadSeq, currentUserId]);
  const pendingMentions = mentionMessages.filter((m) =>
    handledMentions?.session !== readSession || !handledMentions.ids.has(m.id),
  );
  const mentionMessage = pendingMentions[0] ?? null;

  function handleMention(messageId: string): void {
    if (activeReadScopeRef.current !== readSession) return;
    setHandledMentions((previous) => ({
      session: readSession,
      ids: new Set([...(previous?.session === readSession ? previous.ids : []), messageId]),
    }));
  }

  /** 滚动到指定消息并临时高亮（复用「有人@你」点击效果） */
  function flashMention(messageId: string): void {
    setMentionFlashId(messageId);
    handleMention(messageId);
    shouldStickToBottomRef.current = false;
    document
      .getElementById(`msg-${messageId}`)
      ?.scrollIntoView({ behavior: "smooth", block: "center" });
    window.setTimeout(() => {
      if (activeReadScopeRef.current === readSession) setMentionFlashId(null);
    }, 2200);
  }

  /** 每次定位最早一条待处理提及；其余提示继续保留。 */
  function jumpToMention() {
    if (!mentionMessage) return;
    flashMention(mentionMessage.id);
  }
  const showMentionBar = mentionMessage !== null;
  const canOpenTaskTrigger =
    group?.type === "REQUIREMENT" &&
    group.status === "ACTIVE" &&
    !group.isArchived;

  // 从「@ 提及」通知跳转过来：自动滚动到被 @ 的消息并高亮（模拟点击提示条）。
  // 通知带 resourceId=messageId → 精确定位；缺 messageId（旧数据）→ 兜底跳到最上面（最早）一条被 @ 的消息。
  // 精确目标不在已加载窗口时进入独立历史窗口，不把孤立旧消息拼进最新页。
  const handledMentionRef = useRef<string | null>(null);
  useEffect(() => {
    const navigationKey = `${readScope}/${location.key}`;
    if (handledMentionRef.current === navigationKey || !currentUserId) return;
    const mentionMessageId = (
      location.state as { mentionMessageId?: string } | null
    )?.mentionMessageId;
    const hasAutoJumpFlag =
      typeof mentionMessageId === "string" ||
      (location.state as { autoJumpMention?: boolean } | null)
        ?.autoJumpMention === true;
    if (!hasAutoJumpFlag) return;
    const isUserMention = (m: Message): boolean =>
      m.senderType !== "AGENT" &&
      m.senderId !== currentUserId &&
      (m.mentions ?? []).some(
        (mention) => mention.type === "USER" && mention.id === currentUserId,
      );
    const target = mentionMessageId
      ? messages.find((m) => m.id === mentionMessageId && isUserMention(m))
      : messages.find(isUserMention);
    if (target) {
      handledMentionRef.current = navigationKey;
      flashMention(target.id);
      navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: {} });
      return;
    }
    if (typeof mentionMessageId === "string") {
      handledMentionRef.current = navigationKey;
      shouldStickToBottomRef.current = false;
      setHistoryTarget({ scope: readScope, id: mentionMessageId, navigationKey, latestId: messages.at(-1)?.id });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages, location.pathname, location.key, navigate, location.state, currentUserId, readScope]);

  function pickMention(target: {
    id: string;
    displayName: string;
    type: MentionType;
  }) {
    // 用「@显示名 + 空格」替换掉最后一个 @ 及其后的过滤字符，避免残留查询词
    setDraft((prev) => {
      const at = prev.lastIndexOf("@");
      return at >= 0
        ? `${prev.slice(0, at)}@${target.displayName} `
        : `${prev}@${target.displayName} `;
    });
    setMentions((prev) => [...prev, { type: target.type, id: target.id }]);
  }

  /**
   * 右键「@ta」：把目标消息的发送者解析为可 @ 的候选（与候选面板同源：userMembers / teamAgents）。
   * 解析不到的（如 SYSTEM、自己、非候选 Agent）返回 null，不弹出菜单。
   */
  function resolveCtxMention(
    m: Message,
  ): { displayName: string; type: MentionType } | null {
    if (m.senderType === "USER") {
      const member = userMembers.find((mem) => mem.id === m.senderId);
      return member ? { displayName: member.displayName, type: "USER" } : null;
    }
    if (m.senderType === "AGENT") {
      const agent = teamAgents.find((a) => a.id === m.senderId);
      return agent ? { displayName: agent.name, type: "AGENT" } : null;
    }
    return null;
  }

  /** 右键菜单「@ta」：在输入框追加 @该成员 + 空格，登记提及并聚焦输入框进入编辑态 */
  function mentionFromCtx(m: Message): void {
    const resolved = resolveCtxMention(m);
    if (!resolved || !m.senderId) return;
    setDraft((prev) => {
      const base = prev.trimEnd();
      return base
        ? `${base} @${resolved.displayName} `
        : `@${resolved.displayName} `;
    });
    setMentions((prev) => [
      ...prev,
      { type: resolved.type, id: m.senderId as string },
    ]);
    setCtxMenu(null);
    // 等 draft 更新后再聚焦（TextArea 由受控 value 驱动，聚焦本身不依赖 draft）
    window.setTimeout(() => inputRef.current?.focus(), 0);
  }

  // 右键菜单打开时：点击任意处 / 滚动 / 失焦关闭（菜单自身 mousedown 会阻止冒泡，避免点菜单即关）
  useEffect(() => {
    if (!ctxMenu) return;
    const close = () => setCtxMenu(null);
    document.addEventListener("mousedown", close);
    document.addEventListener("scroll", close, true);
    window.addEventListener("blur", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("scroll", close, true);
      window.removeEventListener("blur", close);
    };
  }, [ctxMenu]);

  // ──── 乐观发送：先把消息插进缓存展示（带转圈），后端返回后替换为真实消息 ────
  const messagesKey = ["groups", projectId, groupId, "messages"] as const;

  function insertOptimisticMessage(msg: Message): void {
    queryClient.setQueryData<InfiniteData<Page<Message>>>(
      messagesKey,
      (prev) => {
        const page: Page<Message> = prev?.pages[0] ?? {
          data: [],
          page: { nextCursor: null, hasMore: false },
        };
        if (!prev) {
          return { pages: [{ ...page, data: [msg] }], pageParams: [undefined] };
        }
        return {
          ...prev,
          pages: [
            { ...page, data: [msg, ...page.data] },
            ...prev.pages.slice(1),
          ],
        };
      },
    );
  }

  function replaceOptimisticMessage(optimisticId: string, real: Message): void {
    queryClient.setQueryData<InfiniteData<Page<Message>>>(
      messagesKey,
      (prev) => {
        if (!prev) return prev;
        // 实时补查可能先于发送响应取得正式消息；移除临时行后复用已有正式记录。
        const alreadyLoaded = prev.pages.some((item) => item.data.some((row) => row.id === real.id && row.id !== optimisticId));
        const withoutOptimistic = {
          ...prev,
          pages: prev.pages.map((p) => ({
            ...p,
            data: p.data.filter((m) => m.id !== optimisticId),
          })),
        };
        return mergeMessageUpdates(withoutOptimistic, alreadyLoaded ? [] : [{ ...real, pending: false }]);
      },
    );
  }

  function removeOptimisticMessage(optimisticId: string): void {
    queryClient.setQueryData<InfiniteData<Page<Message>>>(
      messagesKey,
      (prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          pages: prev.pages.map((p) => ({
            ...p,
            data: p.data.filter((m) => m.id !== optimisticId),
          })),
        };
      },
    );
  }

  async function handleSend() {
    if (draftState.replyToId && !replyTo) {
      setSendError("引用消息尚未恢复，请重试恢复引用或取消回复后发送。");
      return;
    }
    const text = draft.trim();
    // 允许纯引用（引用 DIFF 卡等）：回复目标存在时正文可为空（B3，服务端用群描述兜底）
    if ((!text && !replyTo) || sending) return;

    // 提及与正文对齐：用户删除 @某某 文本后不再携带该提及（修复「删掉 @agent 仍触发建任务」）。
    // pickMention 只在候选面板加载完成（teamAgents/userMembers 已就绪）时可点，故此处映射可靠。
    const mentionDisplayName = (mention: Mention): string | undefined =>
      mention.type === "AGENT"
        ? teamAgents.find((agent) => agent.id === mention.id)?.name
        : userMembers.find((member) => member.id === mention.id)?.displayName;
    const effectiveMentions = mentions.filter((mention) => {
      const displayName = mentionDisplayName(mention);
      return !!displayName && text.includes(displayName);
    });
    const hasAgentMention = effectiveMentions.some(
      (mention) => mention.type === "AGENT",
    );
    const taskText = taskTextFromReply(
      text,
      effectiveMentions,
      mentionDisplayName,
    );
    setSending(true);
    setSendError(null);
    // 乐观发送：先本地构造一条临时消息（pending 转圈）插入缓存，避免等待后端返回期间"卡住没反应"
    const optimisticId = `cmsg_${Date.now()}`;
    const optimisticContent = replyTo
      ? {
          // QUOTE 同时提供通用 text 字段。自动建任务和群摘要按普通消息取正文时，
          // 不应退化为序列化整个引用元数据对象。
          text: taskText,
          quotedMessageId: replyTo.id,
          quotedText: quotePreview(replyTo),
          quotedSenderName:
            replyTo.senderName ??
            (replyTo.senderType === "AGENT" ? "Agent" : "成员"),
          replyText: text,
        }
      : { text };
    insertOptimisticMessage({
      id: optimisticId,
      groupId,
      type: replyTo ? "QUOTE" : "TEXT",
      content: optimisticContent,
      senderType: "USER",
      senderId: user?.id,
      senderName: user?.displayName,
      createdAt: new Date().toISOString(),
      replyToId: replyTo ? replyTo.id : null,
      replyText: replyTo ? text : undefined,
      pending: true,
    });
    try {
      // 自己发消息 → 应当保持贴底（新消息渲染 + 历史图片继续加载时都滚到底）
      shouldStickToBottomRef.current = true;
      // 强制滚底：发送期间产生的滚动/布局事件可能把 stick 标志重算为 false，用 pending 标志兜底
      pendingScrollRef.current = true;
      // 回复引用：type=QUOTE，quotedText 为被引用消息的原始内容摘要，replyText 为回复正文。
      // §7 冻结：replyText 放顶层；content 内保留一份以兼容旧后端/旧数据读取
      const result = await groupApi.sendMessage(projectId, groupId, {
        type: replyTo ? "QUOTE" : "TEXT",
        content: optimisticContent,
        replyText: replyTo ? text : undefined,
        mentions: effectiveMentions.length > 0 ? effectiveMentions : undefined,
        replyToId: replyTo ? replyTo.id : null,
        clientMessageId: optimisticId,
      });
      const sentMessage = result.message;
      const quotedDiff = replyTo?.type === "DIFF";
      // 成功：用真实消息替换乐观消息（去掉 pending 转圈）
      replaceOptimisticMessage(optimisticId, sentMessage);
      if (activeReadScopeRef.current === readSession) returnToLatest();
      useChatSessionStore.getState().clearDraft(chatScope, draftState.revision);
      await queryClient.invalidateQueries({
        queryKey: ["groups", projectId, groupId, "messages"],
      });
      // 发送完成后强制滚一次，确保最新消息可见（pending 标志由消息变化效果统一清除）
      scrollToBottom();
      if (hasAgentMention && canOpenTaskTrigger) {
        void queryClient.invalidateQueries({
          queryKey: ["qgents", "projects", projectId, "tasks"],
        });
      }
      if (result.task) {
        message.success(
          `${result.task.displayCode} 已创建，当前状态：${result.task.status}`,
        );
      } else if (quotedDiff && sentMessage) {
        // 引用 DIFF 卡续作：优先走续作（复用源 Workspace，不得传 repositoryIds），后端未自动建任务时显式触发。
        // 传入发送时的正文作为任务标题/需求（此时 draft 已清空，不能读组件状态）
        triggerFromMessage.mutate({
          messageId: sentMessage.id,
          title: taskTitleFromMessage(text),
          requirement: text,
        });
      } else if (hasAgentMention && canOpenTaskTrigger) {
        // 普通 @编排助手消息由 MessageSentListener 在服务端自动建 Task。
        // 这里不再并行调用 /trigger-task，避免自动触发尚未落库时重复创建任务
        // 产生误报（任务随后仍由服务端按项目仓库范围异步创建）。
        if (canOpenTaskTrigger) {
          void queryClient.invalidateQueries({
            queryKey: ["qgents", "projects", projectId, "tasks"],
          });
          message.info("需求已发送，任务正在后台创建。");
        }
      }
      return sentMessage;
    } catch (error) {
      // 失败：移除乐观消息，展示错误（原 draft 保留，可重发）
      removeOptimisticMessage(optimisticId);
      setSendError(formatApiError(error));
      return null;
    } finally {
      setSending(false);
    }
  }

  // 显式触发任务（§7 从消息触发任务；续作引用时不得传 repositoryIds，C1/C2）。
  // 触发参数由调用方传入：handleSend 里 draft 会在触发前清空，不能闭包读 draft（否则 title/requirement 恒空）。
  const triggerFromMessage = useMutation({
    mutationFn: (input: {
      messageId: string;
      title: string;
      requirement?: string;
    }) =>
      groupApi.triggerTask(projectId, groupId, input.messageId, {
        title: input.title,
        requirement: input.requirement,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: ["qgents", "projects", projectId, "tasks"],
      });
      message.success("增量任务已创建，正在生成执行方案。");
    },
    onError: (error) => {
      // C3：引用 DIFF 续作的 422 错误码给出明确提示；其余走通用错误文案
      const code =
        error instanceof ApiError &&
        error.body &&
        typeof error.body === "object" &&
        "error" in error.body
          ? (error.body as { error?: { code?: unknown } }).error?.code
          : undefined;
      if (code === "QUOTED_DIFF_INVALID")
        message.error("该 Diff 已失效，请刷新消息列表后重试");
      else if (code === "QUOTED_DIFF_NOT_ACCESSIBLE")
        message.error("无权引用该 Diff，请确认项目权限");
      else
        message.error(
          error instanceof Error ? error.message : "任务触发失败，请重试",
        );
    },
  });

  /** 选择附件后：直传 OSS → 发送 IMAGE/FILE 消息（§18 附件链路 + 增量契约 §6 attachmentId） */
  async function handleUpload(file: File) {
    if (uploading) return;
    setUploading(true);
    try {
      const attachmentId = await uploadAttachment(projectId, file);
      const url = attachmentApi.contentUrl(projectId, attachmentId);
      const isImage = file.type.startsWith("image/");
      await groupApi.sendMessage(projectId, groupId, {
        type: isImage ? "IMAGE" : "FILE",
        content: isImage
          ? // §6.2：IMAGE content 必须带 attachmentId（多模态输入依赖）
            { url, attachmentId }
          : {
              url,
              attachmentId,
              name: file.name,
              size: file.size,
              mimeType: file.type,
            },
        clientMessageId: `cmsg_${Date.now()}`,
      });
      await queryClient.invalidateQueries({
        queryKey: ["groups", projectId, groupId, "messages"],
      });
    } catch (e) {
      message.error(e instanceof Error ? e.message : "附件发送失败");
    } finally {
      setUploading(false);
    }
  }

  /**
   * 打开文件消息：解析 attachmentId 后打开页内预览弹窗（增量契约 §4/§5）。
   * 无法解析附件 ID 时提示（不再直接跳新标签；预览弹窗内负责 preview-url / download 降级）。
   */
  const openFile = useCallback(
    (target: Message) => {
      const c = target.content as FileMessageContent;
      // §6：content.attachmentId 为必填；兼容旧消息从 url 解析
      const attachmentId = c.attachmentId || extractAttachmentId(c.url);
      if (!attachmentId) {
        message.error("无法解析附件 ID，请刷新后重试");
        return;
      }
      setPreviewTarget({
        attachmentId,
        fileName: c.name,
        // §7：后端若已回填 previewUrl（相对路径带 token），零请求直接预览
        embeddedPreviewUrl: c.previewUrl,
      });
    },
    [message],
  );

  // AI 自动沉淀 Memory（草稿）：后端自动检索当前群最近聊天并生成草稿，投给用户/Admin 审核确认
  const createAiMemory = useMutation({
    mutationFn: () => memoryApi.generateDraft(projectId, { groupId }),
    onSuccess: () => {
      message.success(
        "AI 已根据最近群聊生成 Memory 草稿，可在交付中心提交审核",
      );
      void queryClient.invalidateQueries({ queryKey: ["memories", projectId] });
    },
    onError: (error) => {
      message.error(
        error instanceof Error ? error.message : "Memory 生成失败，请重试",
      );
    },
  });

  // TASK_STATUS 任务状态卡按消息流时间位置渲染（不置顶）；
  // 后端单卡持续更新（message.updated → 重查消息列表），content 原地刷新

  function renderChatRow(m: Message, previousMessage: Message | undefined, flashing: boolean) {
    const isSelf = m.senderType === "USER" && m.senderId === user?.id;
    const grouped = Boolean(previousMessage && previousMessage.senderType !== "SYSTEM" && m.senderType !== "SYSTEM" &&
      previousMessage.senderType === m.senderType && previousMessage.senderId === m.senderId && previousMessage.type !== "TASK_STATUS" && m.type !== "TASK_STATUS");
    return <ChatMessageRow key={m.id} message={m} isSelf={isSelf} grouped={grouped} flashing={flashing}
      userAvatarUrl={user?.avatarUrl} userDisplayName={user?.displayName} senderAvatarUrl={resolveSenderAvatar(m)}
      onContextMenu={!isSelf && m.senderType !== "SYSTEM" ? (e) => { e.preventDefault(); setCtxMenu({ x: e.clientX, y: e.clientY, message: m }); } : undefined}>
      <MessageBubble message={m} isSelf={isSelf} selfDisplayName={user?.displayName ?? "我"} projectId={projectId}
        taskStatusById={taskStatusById} onReply={handleReply} onOpenFile={openFile} onImageLoad={() => { if (!contextTarget) handleImageLoad(); }} />
    </ChatMessageRow>;
  }

  return (
    // 聊天区错误边界：渲染崩溃只挂聊天区，侧栏/动态面板不受影响；切群自动复位
    <ErrorBoundary resetKey={groupId}>
      <Layout className="chat-panel" style={{ height: "100%", background: token.colorBgBase }}>
        {/* 顶部：群标题 + 操作入口；多选模式下切换为「取消 | 已选择 N 条」 */}
        <ChatHeader
          group={group}
          project={project}
          canOpenTaskTrigger={canOpenTaskTrigger}
          onOpenTaskTrigger={() => setTriggerOpen(true)}
          onOpenSettings={() => setSettingsOpen(true)}
        />
        <div className="chat-panel__read-status" role="status" aria-live="polite">
          <Text className="chat-panel__connection" type={connectionStatus === "disconnected" ? "warning" : "secondary"}>
            {connectionStatus === "connected" ? "实时连接已建立" : connectionStatus === "connecting" ? "正在连接实时更新…" : connectionStatus === "disabled" ? "实时更新未启用" : "实时连接已断开，正在重连"}
          </Text>
          <Text type={syncFailed ? "warning" : "secondary"}>
            {contextTarget ? (syncFailed ? "最新消息同步未成功，历史窗口继续可读" : "历史消息与最新消息分开加载") : hasMessageCache ? (
              syncFailed ? "同步未成功，当前显示已有消息" : syncPending ? "正在同步消息…" : "显示当前已加载消息"
            ) : null}
          </Text>
          {hasMessageCache && syncFailed ? (
            <Button type="link" size="small" loading={Boolean(syncPending)} onClick={retryMessageSync}>
              重试同步
            </Button>
          ) : null}
          <div className="chat-panel__read-ack">
            {readAcknowledgement.failed ? <><Text type="warning">已读未确认，保留未读状态</Text><Button type="link" size="small" disabled={syncFailed || Boolean(syncPending)} onClick={() => void markGroupReadNow()}>重试已读</Button></> :
              readAcknowledgement.pending ? <Text type="secondary">正在确认已读…</Text> : null}
          </div>
        </div>
        {/* 消息列表 */}
        {readingNotice && !contextTarget ? <Text role="status" type="secondary">{readingNotice}</Text> : null}
        {contextTarget ? <ChatHistoryWindow key={historyTarget?.navigationKey} projectId={projectId} groupId={groupId} messageId={contextTarget}
          initialAnchor={!explicitNavigation && savedReading?.contextMessageId === contextTarget ? savedReading : undefined}
          onReadingChange={(anchor) => {
            if (activeReadScopeRef.current === readSession && historyModeRef.current === contextTarget) {
              useChatSessionStore.getState().savePosition(chatScope, { ...anchor, contextMessageId: contextTarget });
            }
          }}
          hasNewMessages={Boolean(historyTarget?.latestId && lastMessageId && historyTarget.latestId !== lastMessageId)}
          onReturn={() => {
            returnToLatest();
            navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: {} });
          }}
          onLocated={() => {
            if (activeReadScopeRef.current !== readSession) return;
            handleMention(contextTarget);
            navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: {} });
          }}
          renderMessage={(m, previous) => renderChatRow(m, previous, m.id === contextTarget)} /> :
        <div className="chat-panel__viewport">
        <ChatMessageViewport listRef={listRef} onScroll={handleScroll}>
          {!hasMessageCache && isLoading ? (
            <div
              style={{
                height: "100%",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <div style={{ textAlign: "center" }}>
                <Spin size="large" />
                <div style={{ marginTop: 16, color: "#5b6b82", fontSize: 14 }}>
                  <MessageOutlined style={{ marginRight: 8 }} />
                  正在加载群聊消息…
                </div>
              </div>
            </div>
          ) : !hasMessageCache && isLoadingError ? (
            <Empty description="消息加载失败">
              <Button loading={isRefetching || isLoading} onClick={() => void refetchMessages({ cancelRefetch: false })}>
                重试加载消息
              </Button>
            </Empty>
          ) : messages.length === 0 ? (
            <Empty description="还没有消息，来说点什么吧" />
          ) : (
            <div
              ref={contentRef}
              className="chat-panel__message-content"
              style={{ display: "flex", flexDirection: "column", gap: 12 }}
            >
              {/* 顶部：加载更早历史的分页指示 */}
              <div className="chat-panel__history-status" role="status" aria-live="polite">
                {historyFailed ? (
                  <>
                    <Text type="warning">更早消息加载失败</Text>
                    <Button type="link" size="small" loading={isFetchingNextPage} disabled={isRefetching} onClick={() => void loadEarlierMessages()}>
                      重试加载历史
                    </Button>
                  </>
                ) : isFetchingNextPage ? (
                  <Spin size="small" />
                ) : hasNextPage ? (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    向上滚动加载更早消息
                  </Text>
                ) : (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    — 已加载全部历史消息 —
                  </Text>
                )}
              </div>
              {messages.map((m, index) => renderChatRow(m, messages[index - 1], mentionFlashId === m.id))}
            </div>
          )}
        </ChatMessageViewport>
        <div className="chat-panel__reading-actions">
          {showMentionBar ? <button type="button" className="chat-panel__mention-jump" onClick={jumpToMention}>
            <span aria-hidden>↑</span><span aria-live="polite" aria-atomic="true">有人@你 · {pendingMentions.length} 条</span>
          </button> : null}
        {/* CHAT-005：阅读历史时到达普通新消息。浮在视口内、脱离内容流，
            因此不改 scrollHeight、不影响贴底判断与阅读锚点；点一下回到最新。 */}
        {unseenCount > 0 ? (
          <button
            type="button"
            className="chat-panel__new-messages"
            style={{
              background: token.colorPrimary,
              color: token.colorTextLightSolid,
            }}
            onClick={jumpToLatest}
            aria-label={`${unseenCount} 条新消息，回到最新`}
          >
            <span className="chat-panel__new-messages-icon" aria-hidden>
              <ArrowDownOutlined />
            </span>
            <span className="chat-panel__new-messages-label">
              <span className="chat-panel__new-messages-count">
                {unseenCount > 99 ? "99+" : unseenCount}
              </span>
              {" 条新消息"}
            </span>
          </button>
        ) : null}
        </div>
        </div>}

        {/* 底部输入区 */}
        <ChatComposer
          draft={draft}
          sending={sending}
          uploading={uploading}
          sendError={sendError}
          replyTo={draftState.replyToId}
          replyLabel={`回复 ${replyTo?.senderName ?? (replyTo?.senderType === "AGENT" ? "Agent" : "成员")}`}
          replyPreview={replyTo ? quotePreview(replyTo) : draftState.replyToId ? "正在恢复引用消息…" : ""}
          replyPending={Boolean(draftState.replyToId && !replyTo)}
          onRetryReply={replyQuery.isError ? () => { void replyQuery.refetch(); } : undefined}
          replyWarning={
            draftState.replyToId && !replyTo && replyQuery.isError ? "原引用消息暂时无法恢复，可重试或取消回复；草稿仍保留。" : replyTo?.type === "DIFF"
              ? "引用 Diff 卡将发起增量修改（复用源工作区）"
              : undefined
          }
          inputRef={inputRef}
          mentionPanel={
            mentionOpen &&
            (filteredAgents.length > 0 || filteredUsers.length > 0) ? (
              <div
                style={{
                  position: "absolute",
                  bottom: "100%",
                  left: 20,
                  right: 20,
                  marginBottom: 8,
                  background: token.colorBgContainer,
                  border: `1px solid ${token.colorBorder}`,
                  borderRadius: 10,
                  boxShadow: "0 8px 24px rgba(15, 23, 42, 0.12)",
                  maxHeight: 240,
                  overflowY: "auto",
                  padding: 6,
                  zIndex: 10,
                }}
              >
                {/* Agent 候选：仅活跃需求群可 @ Agent（项目总群不提供 @Agent，发起任务必须挂 REQUIREMENT 群） */}
                {canOpenTaskTrigger && filteredAgents.length > 0 && (
                  <ChatMentionGroup
                    label="Agent"
                    members={filteredAgents.map((a) => ({
                      id: a.id,
                      displayName: a.name,
                      type: "AGENT" as const,
                      avatarUrl: a.avatar,
                    }))}
                    onPick={pickMention}
                  />
                )}
                {filteredUsers.length > 0 && (
                  <ChatMentionGroup
                    label="成员"
                    members={filteredUsers.map((m) => ({
                      id: m.id,
                      displayName: m.displayName,
                      type: "USER" as const,
                      avatarUrl: m.avatarUrl,
                    }))}
                    onPick={pickMention}
                  />
                )}
              </div>
            ) : undefined
          }
          onDraftChange={setDraft}
          onSend={handleSend}
          onUpload={(file) => {
            void handleUpload(file);
            return false;
          }}
          onCancelReply={() => setReplyTo(null)}
        />

        {/* @Agent 发起任务弹窗（B 的 TaskTriggerModal） */}
        <TaskTriggerModal
          open={triggerOpen}
          projectId={projectId}
          groupId={groupId}
          initialInstruction=""
          onClose={() => setTriggerOpen(false)}
        />

        <ChatSettingsDrawer
          open={settingsOpen}
          projectId={projectId}
          group={group ?? null}
          canArchive={
            group?.type === "REQUIREMENT" &&
            group.createdBy === user?.id &&
            !group.isArchived
          }
          memoryLoading={createAiMemory.isPending}
          archiveLoading={archiveGroup.isPending}
          onClose={() => setSettingsOpen(false)}
          onCreateMemory={() => {
            createAiMemory.mutate();
            setSettingsOpen(false);
          }}
          onArchive={() => {
            archiveGroup.mutate();
            setSettingsOpen(false);
          }}
        />
        {/* 附件内联预览（增量契约 §4/§5/§7）：图片放大 / PDF iframe / 文本代码高亮 / 不支持回退下载 */}
        {previewTarget ? (
          <AttachmentPreviewModal
            open
            projectId={projectId}
            attachmentId={previewTarget.attachmentId}
            fileName={previewTarget.fileName}
            embeddedPreviewUrl={previewTarget.embeddedPreviewUrl}
            onClose={() => setPreviewTarget(null)}
          />
        ) : null}
        {/* 右键成员消息上下文菜单：@ta 该成员（点击空白/滚动/失焦自动关闭） */}
        {ctxMenu ? (
          <div
            style={{
              position: "fixed",
              left: ctxMenu.x,
              top: ctxMenu.y,
              zIndex: 1000,
              minWidth: 120,
              padding: 4,
              background: token.colorBgContainer,
              border: `1px solid ${token.colorBorder}`,
              borderRadius: 8,
              boxShadow: "0 8px 24px rgba(15, 23, 42, 0.16)",
            }}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <Button
              type="text"
              block
              onClick={() => mentionFromCtx(ctxMenu.message)}
              style={{ textAlign: "left" }}
            >
              <Text strong style={{ marginRight: 6 }}>
                @
              </Text>
              ta
            </Button>
          </div>
        ) : null}
      </Layout>
    </ErrorBoundary>
  );
}

function taskTitleFromMessage(text: string): string {
  const withoutLeadingMentions = text.replace(/(?:^|\s)@\S+/g, " ").trim();
  return (withoutLeadingMentions || text).slice(0, 80);
}

/**
 * 消息气泡保留 @ 提及以表达对 Agent 的指派；任务正文不应把它当作需求的一部分。
 * 仅移除本次实际提及对象开头的名称，避免误删需求正文中的普通 @ 字符。
 */
function taskTextFromReply(
  text: string,
  mentions: readonly Mention[],
  displayNameForMention: (mention: Mention) => string | undefined,
): string {
  let result = text.trim();
  const names = mentions
    .map(displayNameForMention)
    .filter((name): name is string => Boolean(name))
    .sort((left, right) => right.length - left.length);

  for (const name of names) {
    const escapedName = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    result = result
      .replace(new RegExp(`^@${escapedName}(?:[\\s，,：:]+)?`), "")
      .trim();
  }
  return result || text.trim();
}

/** 从附件 URL（§18.5 content 地址）中提取 attachmentId，供 §18.3 download-url 使用 */
function extractAttachmentId(url: string | undefined | null): string | null {
  if (!url) return null;
  const match = url.match(/\/attachments\/([^/?#]+)(?:\/content)?/);
  return match ? match[1] : null;
}

/** 生成被引用消息的一行摘要（回复引用条展示用；DIFF/IMAGE/FILE 等无文本类型给占位文案） */
function quotePreview(message: Message): string {
  const content = message.content as Record<string, unknown> | null;
  switch (message.type) {
    case "CODE":
      return `[代码块] ${typeof content?.code === "string" ? content.code.slice(0, 40) : ""}`;
    case "IMAGE":
      return "[图片]";
    case "FILE":
      return `[文件] ${typeof content?.name === "string" ? content.name : ""}`;
    case "DIFF":
      return `[Diff] ${typeof content?.title === "string" ? content.title : "代码变更"}`;
    case "TASK_STATUS":
      return `[任务状态] ${typeof content?.message === "string" ? content.message : typeof content?.status === "string" ? content.status : ""}`;
    case "QUOTE": {
      // 引用一条「引用消息」时，被引用内容应为该消息实际回复的正文（replyText），
      // 而不是其引用的上层内容——避免嵌套引用叠加成 [引用][引用]…
      const quoted = content as QuoteMessageContent | null;
      // §7 冻结：replyText 回显在顶层；content.replyText 兼容旧数据
      const text =
        message.replyText ?? quoted?.replyText ?? quoted?.quotedText ?? "";
      return text || "[引用]";
    }
    default: {
      const text = typeof content?.text === "string" ? content.text : "";
      return text || "[消息]";
    }
  }
}

/** @ 提及面板分组 */
/** 单条消息气泡 —— 区分 USER / AGENT / SYSTEM，按类型渲染 IMAGE/FILE/QUOTE/DIFF/TASK_STATUS */
