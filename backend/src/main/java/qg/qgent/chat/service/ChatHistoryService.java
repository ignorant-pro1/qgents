package qg.qgent.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;

import java.util.*;

/**
 * 按群成员权限读取近期消息、搜索聊天和转换结构化正文。
 */
@Service
@RequiredArgsConstructor
public class ChatHistoryService {
    private final MessageMapper messageMapper;
    private final GroupService groupService;
    private final ObjectMapper mapper;

    public List<ContextMessage> recentMessages(
        Long actor, Long projectId, Long groupId, Integer limit) {
        groupService.requireGroupMember(projectId, groupId, actor);
        int size = Math.min(Math.max(limit == null ? 50 : limit, 1), 200);
        List<MessageEntity> newest =
            new ArrayList<>(
                messageMapper.selectList(
                    Wrappers.<MessageEntity>lambdaQuery()
                        .eq(MessageEntity::getGroupId, groupId)
                        .orderByDesc(MessageEntity::getSequenceNo)
                        .last("limit " + size)));
        Collections.reverse(newest);
        return newest.stream().map(this::toContextMessage).toList();
    }

    /**
     * 在指定项目群聊内按关键字检索历史聊天记录。Skill 与 Memory 不属于聊天检索范围。
     */
    public List<ContextMessage> searchChatHistory(
        Long actor, Long projectId, Long groupId, String query, Integer limit) {
        groupService.requireGroupMember(projectId, groupId, actor);
        if (query == null || query.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "CHAT_SEARCH_QUERY_REQUIRED", "聊天检索关键字不能为空");
        }
        int messageLimit = Math.min(Math.max(limit == null ? 10 : limit, 1), 50);
        return messageMapper
            .searchByQuery(projectId, List.of(groupId), query.trim(), messageLimit)
            .stream()
            .map(this::toContextMessage)
            .toList();
    }

    public ContextMessage readChatMessage(
        Long actor, Long projectId, Long groupId, Long messageId) {
        groupService.requireGroupMember(projectId, groupId, actor);
        MessageEntity message = messageMapper.selectById(messageId);
        if (message == null || !groupId.equals(message.getGroupId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "消息不存在或无权访问");
        return toContextMessage(message);
    }

    private String senderType(MessageEntity m) {
        return m.getSenderType();
    }

    private String senderId(MessageEntity m) {
        if ("AGENT".equals(m.getSenderType())) {
            return m.getSenderAgentId() == null ? null : m.getSenderAgentId().toString();
        }
        return m.getAuthorUserId() == null ? null : m.getAuthorUserId().toString();
    }

    /**
     * 提取文本或代码正文；附件仅保留 ID、名称、媒体类型和声明大小供按需读取。
     */
    private ContextMessage toContextMessage(MessageEntity m) {
        Map<?, ?> content = parseContent(m);
        String type = m.getMessageType();
        String text =
            "IMAGE".equals(type) || "FILE".equals(type)
                ? ""
                : Objects.toString(content.get("CODE".equals(type) ? "code" : "text"), "");
        String attachmentId = stringField(content, "attachmentId");
        ContextMessage message =
            new ContextMessage(
                m.getSequenceNo(),
                type,
                senderType(m),
                senderId(m),
                text,
                attachmentId,
                stringField(content, "name"),
                stringField(content, "mediaType"),
                sizeField(content));
        message.setId(m.getId() == null ? null : m.getId().toString());
        message.setReplyToId(
            m.getReplyToMessageId() == null ? null : m.getReplyToMessageId().toString());
        return message;
    }

    /**
     * 附件大小来自客户端消息内容，只用于展示。
     */
    private Long sizeField(Map<?, ?> content) {
        return content.get("size") instanceof Number size ? size.longValue() : null;
    }

    /**
     * 解析 content JSON；正文损坏时带上消息 ID 抛出，避免把空内容当作正常消息。
     */
    private Map<?, ?> parseContent(MessageEntity message) {
        String contentJson = message.getContent();
        if (contentJson == null || contentJson.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(contentJson, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("消息内容解析失败, messageId=" + message.getId(), e);
        }
    }

    /**
     * 取 content 字段的字符串值；缺失返回 null。
     */
    private String stringField(Map<?, ?> content, String key) {
        Object value = content.get(key);
        return value == null ? null : value.toString();
    }
}
