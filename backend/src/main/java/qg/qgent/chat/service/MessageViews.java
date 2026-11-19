package qg.qgent.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 群消息实体到响应的组装。
 * <p>
 * 发送、分页、单条定位共用同一份组装：发送者名称与附件预览都按整批取一次，
 * 正文和提及在这里解析成结构化内容。
 */
@Component
@RequiredArgsConstructor
public class MessageViews {
    private final UserMapper users;
    private final AgentMapper agents;
    private final AttachmentService attachments;
    private final ObjectMapper json;

    /**
     * 按入参顺序组装；projectId 为空时跳过附件预览。
     */
    public List<MessageResponse> assemble(List<MessageEntity> rows, Long projectId) {
        Map<Long, String> names = userNames(rows);
        Map<Long, String> agentNames = agentNames(rows);
        List<MessageResponse> responses =
            rows.stream()
                .map(row -> response(row, senderName(row, names, agentNames)))
                .toList();
        enrichPreviews(responses, projectId);
        return responses;
    }

    private String senderName(
        MessageEntity row, Map<Long, String> names, Map<Long, String> agentNames) {
        if (row.getSenderAgentId() != null) {
            return agentNames.get(row.getSenderAgentId());
        }
        return row.getAuthorUserId() == null ? null : names.get(row.getAuthorUserId());
    }

    private void enrichPreviews(List<MessageResponse> responses, Long projectId) {
        if (projectId == null) {
            return;
        }
        Set<String> attachmentIds =
            responses.stream()
                .filter(response ->
                    "IMAGE".equals(response.getType())
                        || "FILE".equals(response.getType()))
                .map(response -> stringField(response.getContent(), "attachmentId"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (attachmentIds.isEmpty()) {
            return;
        }
        Map<String, AttachmentService.MessageAttachmentPreview> previews =
            attachments.messagePreviews(projectId, attachmentIds);
        for (MessageResponse response : responses) {
            String attachmentId = stringField(response.getContent(), "attachmentId");
            AttachmentService.MessageAttachmentPreview preview =
                attachmentId == null ? null : previews.get(attachmentId);
            if (preview == null) {
                continue;
            }
            response.getContent().put("previewable", preview.kind().previewable());
            response.getContent().put("previewType", preview.kind().name());
        }
    }

    private Map<Long, String> agentNames(List<MessageEntity> rows) {
        Set<Long> agentIds =
            rows.stream()
                .map(MessageEntity::getSenderAgentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        return agents.selectBatchIds(agentIds).stream()
            .collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getName));
    }

    private Map<Long, String> userNames(List<MessageEntity> rows) {
        Set<Long> userIds =
            rows.stream()
                .map(MessageEntity::getAuthorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.selectBatchIds(userIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, MessageViews::displayName));
    }

    private static String displayName(UserEntity user) {
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
            ? "已注销用户"
            : user.getDisplayName();
    }

    private MessageResponse response(MessageEntity message, String senderName) {
        Map<String, Object> content =
            readJson(message.getContent(), new TypeReference<Map<String, Object>>() {
            });
        List<Mention> mentions =
            message.getMentions() == null || message.getMentions().isBlank()
                ? List.of()
                : readJson(message.getMentions(), new TypeReference<List<Mention>>() {
            });
        String senderType;
        String senderId;
        if ("AGENT".equals(message.getSenderType())) {
            senderType = "AGENT";
            senderId = message.getSenderAgentId().toString();
        } else if (message.getAuthorUserId() != null) {
            senderType = "USER";
            senderId = message.getAuthorUserId().toString();
            senderName = senderName == null ? "已注销用户" : senderName;
        } else {
            senderType = "SYSTEM";
            senderId = null;
            senderName = null;
        }
        return new MessageResponse(
            message.getId().toString(),
            message.getGroupId().toString(),
            message.getSequenceNo(),
            message.getMessageType(),
            content,
            senderId,
            senderType,
            senderName,
            message.getReplyToMessageId() == null
                ? null
                : message.getReplyToMessageId().toString(),
            mentions,
            message.getClientMessageId(),
            iso(message.getCreatedAt()));
    }

    /**
     * 时间统一序列化为 UTC 并带 Z 后缀（ISO8601），避免前端按本地时区误解析。
     */
    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    private static String stringField(Map<String, Object> content, String key) {
        Object value = content == null ? null : content.get(key);
        return value instanceof String text ? text : null;
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("消息内容解析失败", e);
        }
    }
}
