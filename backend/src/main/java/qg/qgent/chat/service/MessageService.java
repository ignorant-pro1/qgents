package qg.qgent.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.event.GroupMessageCreatedEvent;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.dto.KeysetPagination;
import qg.qgent.common.dto.PageInfo;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 群消息业务：发送、clientMessageId 去重、消息序号分配、历史分页与断线补拉。
 */
@Service
public class MessageService {
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final Set<String> PUBLIC_TYPES = Set.of("TEXT", "CODE", "IMAGE", "FILE");

    private final MessageMapper messageMapper;
    private final ChatGroupMapper groupMapper;
    private final UserMapper userMapper;
    private final GroupService groupService;
    private final ObjectMapper mapper;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final MessageViews views;
    private final ApplicationEventPublisher eventPublisher;

    public MessageService(
        MessageMapper messageMapper,
        ChatGroupMapper groupMapper,
        UserMapper userMapper,
        GroupService groupService,
        ObjectMapper mapper,
        EventService eventService,
        NotificationService notificationService,
        MessageViews views,
        ApplicationEventPublisher eventPublisher) {
        this.messageMapper = messageMapper;
        this.groupMapper = groupMapper;
        this.userMapper = userMapper;
        this.groupService = groupService;
        this.mapper = mapper;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.views = views;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发送消息（用户路径）。
     * <p>
     * 发送前持有群行锁（FOR UPDATE）以保证 sequence 单调递增；client_message_id 在同一群内唯一，
     * 断线重试或并发撞唯一键时返回原消息（幂等）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   项目群聊 ID
     * @param body      发送请求
     * @return 消息视图
     */
    @Transactional
    public MessageResponse send(Long actor, Long projectId, Long groupId, MessageSendRequest body) {
        // 默认群对项目成员开放，新建群要求受邀成员身份。
        groupService.requireGroupMember(projectId, groupId, actor);
        ChatGroupEntity group = lockGroup(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        for (Mention mention :
            body.getMentions() == null ? List.<Mention>of() : body.getMentions()) {
            if ("AGENT".equals(mention.getType())) {
                groupService.requireGroupAgent(groupId, mention.getId());
            }
        }
        return doSend(projectId, groupId, actor, null, body);
    }

    /**
     * 对被 @ 的用户生成站内通知（MESSAGE_MENTION）：发送者、群名与消息文本摘要进通知内容；
     * 排除发送者本人；被提及的 Agent 由消息监听器登记任务。通知失败不影响消息发送。
     * <p>
     * 由 消息提交后的监听器 在消息事务提交后异步调用，不再占用发送事务与群行锁。
     *
     * @param event       消息发送事件（含 actor/projectId/groupId/messageId/mentions）
     * @param groupName   来源群名（已查好，避免再查一次）
     * @param textPreview 消息文本摘要（由监听器从已落库消息提取；为空则通知不含摘要）
     */
    public void notifyMentionedUsersAfterCommit(
        MessageSentEvent event, String groupName, String textPreview) {
        List<Mention> mentions = event.mentions();
        if (mentions == null || mentions.isEmpty()) {
            return;
        }
        try {
            List<Long> users =
                mentions.stream()
                    .filter(m -> "USER".equals(m.getType()))
                    .filter(m -> m.getId() != null && !m.getId().equals(event.actor()))
                    .map(Mention::getId)
                    .distinct()
                    .toList();
            if (users.isEmpty()) {
                return;
            }
            String senderName = senderDisplayName(event.actor());
            String title = "有人在群聊中提到了你";
            String description =
                senderName
                    + " 在群「"
                    + groupName
                    + "」中提到了你"
                    + (textPreview == null || textPreview.isBlank()
                    ? ""
                    : "：" + textPreview);
            for (Long userId : users) {
                notificationService.notify(
                    userId,
                    event.projectId(),
                    event.groupId(),
                    "MESSAGE_MENTION",
                    title,
                    description,
                    event.messageId() == null ? null : event.messageId().toString());
            }
        } catch (RuntimeException e) {
            log.warn(
                "mention notification skipped, projectId={}, groupId={}, actor={}: {}",
                event.projectId(),
                event.groupId(),
                event.actor(),
                e.getMessage());
        }
    }

    /**
     * 发送者显示名（用户）；查询失败回退「成员」。
     */
    private String senderDisplayName(Long actor) {
        try {
            UserEntity sender = userMapper.selectById(actor);
            return sender == null
                || sender.getDisplayName() == null
                || sender.getDisplayName().isBlank()
                ? "成员"
                : sender.getDisplayName();
        } catch (RuntimeException e) {
            return "成员";
        }
    }

    /**
     * 从消息 content 提取展示文本（TEXT 取 text；其余返回空）。
     */
    private String messageText(Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        Object text = content.get("text");
        if (text instanceof String s && !s.isBlank()) {
            return truncate(s);
        }
        return "";
    }

    /**
     * Agent 接收完整正文，避免把消息协议对象当成任务标题或需求。
     */
    public String agentInstruction(MessageEntity message) {
        Map<String, Object> content =
            readJson(message.getContent(), new TypeReference<Map<String, Object>>() {
            });
        Object text = content.get("text");
        return text instanceof String value ? value : message.getContent();
    }

    /**
     * 提取已落库消息的文本摘要（最长 60 字符），供 消息提交后的监听器 在事务提交后生成 @ 通知内容。
     * <p>
     * 解析失败返回空串而非抛异常：通知失败不阻塞、摘要缺失只影响通知文案。
     *
     * @param message 已落库消息
     * @return 文本摘要（可能为空串）
     */
    public String extractTextPreview(MessageEntity message) {
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            return "";
        }
        try {
            Map<String, Object> content =
                readJson(message.getContent(), new TypeReference<Map<String, Object>>() {
                });
            return messageText(content);
        } catch (RuntimeException e) {
            log.warn(
                "message text preview extract failed, messageId={}: {}",
                message.getId(),
                e.getMessage());
            return "";
        }
    }

    private String truncate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 60) + "…";
    }

    /**
     * Agent 回复与用户消息共用序号、引用和幂等规则；发送者为群内的具体 Agent。
     */
    @Transactional
    public MessageResponse sendAsAgent(Long agentId, Long groupId, MessageSendRequest body) {
        ChatGroupEntity group = lockGroup(groupId);
        if (group == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        groupService.requireGroupAgent(groupId, agentId);
        return doSend(group.getProjectId(), groupId, null, agentId, body);
    }

    /**
     * 运行收尾专用；群、成员身份或原消息已移除时，仍允许保存可查询的运行终态。
     */
    @Transactional
    public void sendAgentRunReply(Long agentId, Long groupId, Long messageId, Long runId, String text) {
        MessageSendRequest reply = new MessageSendRequest();
        reply.setType("TEXT");
        reply.setContent(Map.of("text", text));
        reply.setReplyToId(messageId);
        reply.setClientMessageId("qgent-reply:" + runId);
        try {
            sendAsAgent(agentId, groupId, reply);
        } catch (ApiException error) {
            if (!Set.of("GROUP_NOT_FOUND", "AGENT_NOT_IN_GROUP", "REPLY_MESSAGE_NOT_FOUND")
                .contains(error.code())) throw error;
            log.info("Agent run reply target removed runId={} code={}", runId, error.code());
        }
    }

    private ChatGroupEntity lockGroup(Long groupId) {
        return groupMapper.selectOne(
            Wrappers.<ChatGroupEntity>lambdaQuery()
                .eq(ChatGroupEntity::getId, groupId)
                .last("FOR UPDATE"));
    }

    private MessageResponse doSend(
        Long projectId,
        Long groupId,
        Long authorUserId,
        Long senderAgentId,
        MessageSendRequest body) {
        String type = normalizeType(body.getType());
        validateContent(type, body.getContent());
        List<Mention> mentions = body.getMentions() == null ? List.of() : body.getMentions();
        String clientMessageId = trimmed(body.getClientMessageId());

        // clientMessageId 去重：重试命中后返回原消息，不重复写入或触发提及。
        if (clientMessageId != null) {
            MessageEntity existing = findByClientMessageId(groupId, clientMessageId);
            if (existing != null) {
                return views.assemble(List.of(existing), projectId).getFirst();
            }
        }
        if (body.getReplyToId() != null) {
            MessageEntity reply = messageMapper.selectById(body.getReplyToId());
            if (reply == null || !reply.getGroupId().equals(groupId)) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "REPLY_MESSAGE_NOT_FOUND", "被回复的消息不存在");
            }
        }

        MessageEntity message = new MessageEntity();
        message.setGroupId(groupId);
        message.setSequenceNo(messageMapper.nextSequence(groupId));
        message.setAuthorUserId(authorUserId);
        message.setSenderType(authorUserId == null ? "AGENT" : "USER");
        message.setSenderAgentId(senderAgentId);
        message.setClientMessageId(clientMessageId);
        message.setMessageType(type);
        message.setContent(writeJson(body.getContent()));
        message.setMentions(writeJson(mentions));
        message.setReplyToMessageId(body.getReplyToId());
        // 显式使用 UTC：不依赖数据库 DEFAULT CURRENT_TIMESTAMP（其取 MySQL 服务器时区，可能为本地时间）
        message.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // 并发下 clientMessageId 撞唯一键：返回已存在的原消息
            if (clientMessageId != null) {
                MessageEntity existing = findByClientMessageId(groupId, clientMessageId);
                if (existing != null) {
                    return views.assemble(List.of(existing), projectId).getFirst();
                }
            }
            throw new ApiException(HttpStatus.CONFLICT, "MESSAGE_SEND_CONFLICT", "消息发送冲突，请重试");
        }
        groupMapper.update(
            null,
            Wrappers.<ChatGroupEntity>lambdaUpdate()
                .set(ChatGroupEntity::getLastMessageAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(ChatGroupEntity::getId, groupId));
        // 项目级 SSE：新消息信号（REST 存真相，前端收到后刷新消息列表）
        eventService.publish(
            projectId,
            groupId,
            "message.created",
            id(message.getId()),
            Map.of(
                "projectId",
                id(projectId),
                "groupId",
                id(groupId),
                "messageId",
                id(message.getId()),
                "sequence",
                message.getSequenceNo()));
        // 每条新消息都维护群记忆的活跃状态；与消息同事务，回滚时一起回滚。
        eventPublisher.publishEvent(new GroupMessageCreatedEvent(projectId, groupId));
        // 仅新建用户消息触发提及处理；幂等返回不重复通知或启动 Agent。
        if (authorUserId != null && !mentions.isEmpty()) {
            eventPublisher.publishEvent(
                new MessageSentEvent(
                    authorUserId, projectId, groupId, message.getId(), mentions));
        }
        return views.assemble(List.of(message), projectId).getFirst();
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }

    /**
     * 游标拉取群消息，新消息在前；limit 默认 30、最大 100。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   项目群聊 ID
     * @param cursor    上一页游标，可为空
     * @param limit     每页数量（自动收敛到 1..100）
     * @return 消息分页结果
     */
    public PageSlice<MessageResponse> list(
        Long actor, Long projectId, Long groupId, String cursor, int limit) {
        // 默认群对项目成员开放，新建群要求受邀成员身份。
        groupService.requireGroupMember(projectId, groupId, actor);
        int pageSize = KeysetPagination.pageSize(limit);
        String scope = "group-messages:" + groupId;
        Long before = KeysetPagination.decodeCursor(cursor, scope);
        List<MessageEntity> rows =
            messageMapper.selectList(
                Wrappers.<MessageEntity>lambdaQuery()
                    .eq(MessageEntity::getGroupId, groupId)
                    .lt(before != null, MessageEntity::getSequenceNo, before)
                    .orderByDesc(MessageEntity::getSequenceNo)
                    .last("limit " + (pageSize + 1)));
        Map<Long, MessageResponse> bySequence =
            views.assemble(rows, projectId).stream()
                .collect(
                    Collectors.toMap(
                        MessageResponse::getSequence, Function.identity()));
        return KeysetPagination.keysetPage(
            rows,
            pageSize,
            scope,
            MessageEntity::getSequenceNo,
            row -> bySequence.get(row.getSequenceNo()));
    }

    /**
     * 拉取指定序号之后的新消息，供实时连接恢复时补齐聊天窗口。
     * 返回结果严格按 sequence 升序；服务端不接受客户端伪造的消息 ID 作为游标。
     */
    public PageSlice<MessageResponse> listAfterSequence(
        Long actor, Long projectId, Long groupId, long afterSequence, int limit) {
        groupService.requireGroupMember(projectId, groupId, actor);
        if (afterSequence < 0) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_CURSOR", "afterSequence 必须为非负数");
        }
        int pageSize = KeysetPagination.pageSize(limit);
        List<MessageEntity> rows =
            messageMapper.selectAfterSequence(groupId, afterSequence, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<MessageResponse> page =
            views.assemble(rows.stream().limit(pageSize).toList(), projectId);
        // 增量补拉的续读参数是消息序号，不使用游标
        String nextCursor = hasMore ? String.valueOf(page.getLast().getSequence()) : null;
        return new PageSlice<>(page, new PageInfo(nextCursor, hasMore));
    }

    /**
     * 按消息 ID 拉取单条群消息，供通知跳转和回复定位使用。
     * <p>
     * 场景：通知点击跳转后，目标消息较旧不在前端已加载的分页窗口内时，前端调用本接口拉取
     * 该条消息合并进本地列表再滚动高亮。权限与群成员可见性校验同列表接口。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   项目群聊 ID
     * @param messageId 目标消息 ID
     * @return 消息视图（含发送者名称）
     */
    public MessageResponse getMessage(Long actor, Long projectId, Long groupId, Long messageId) {
        // 默认群对项目成员开放，新建群要求受邀成员身份。
        groupService.requireGroupMember(projectId, groupId, actor);
        MessageEntity message = messageMapper.selectById(messageId);
        if (message == null || !groupId.equals(message.getGroupId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "消息不存在或不属于该群");
        }
        return views.assemble(List.of(message), projectId).getFirst();
    }

    private MessageEntity findByClientMessageId(Long groupId, String clientMessageId) {
        return messageMapper.selectOne(
            Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getGroupId, groupId)
                .eq(MessageEntity::getClientMessageId, clientMessageId));
    }

    private String normalizeType(String type) {
        if (type == null) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID", "不支持的消息类型");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!PUBLIC_TYPES.contains(normalized)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID", "不支持的消息类型");
        }
        return normalized;
    }

    private void validateContent(String type, Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_CONTENT_INVALID", "消息内容不能为空");
        }
        switch (type) {
            case "TEXT" -> {
                requireField(content, "text", "文本消息缺少 text 字段");
            }
            case "CODE" -> {
                requireField(content, "language", "代码消息缺少 language 字段");
                requireField(content, "code", "代码消息缺少 code 字段");
            }
            case "IMAGE", "FILE" -> requireField(content, "attachmentId", type + " 消息缺少 attachmentId 字段");
            default -> throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID", "不支持的消息类型");
        }
    }

    private void requireField(Map<String, Object> content, String field, String message) {
        Object value = content.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_CONTENT_INVALID", message);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("消息内容序列化失败", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("消息内容解析失败", e);
        }
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
