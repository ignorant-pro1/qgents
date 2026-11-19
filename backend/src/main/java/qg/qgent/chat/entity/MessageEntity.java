package qg.qgent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目群聊有序消息实体，对应表 messages。
 * <p>
 * sequence_no 在群内单调递增（服务端在持有群行锁时计算），客户端可通过 client_message_id 幂等重试。
 */
@Data
@TableName("messages")
public class MessageEntity {

    // 消息 ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属项目群聊 ID。
    private Long groupId;

    // 群内单调递增消息序号（BIGINT），(group_id, sequence_no) 唯一。
    private Long sequenceNo;

    // 发送用户 ID；Agent/系统消息时为空。
    private Long authorUserId;

    // 消息发送方 USER/AGENT/SYSTEM；AGENT 表示项目内的具体 Agent。
    private String senderType;

    // 发送 Agent ID；AGENT 消息必填，其余为空。
    private Long senderAgentId;

    // 客户端幂等 ID（群内唯一），断线重试命中时返回原消息；可为空表示不启用客户端幂等。
    private String clientMessageId;

    // 消息类型枚举：TEXT/CODE/IMAGE/FILE/SYSTEM。
    private String messageType;

    // 结构化内容 JSON 文本（JSON 列），如 {"text":"..."}，按类型校验。
    private String content;

    // 提及对象 JSON 文本（JSON 列），元素含 type(USER/AGENT) 与 id；可为空。
    private String mentions;

    // 回复或引用的原消息 ID；可为空。
    private Long replyToMessageId;

    // 发送时间（UTC，数据库默认值）。
    private LocalDateTime createdAt;
}
