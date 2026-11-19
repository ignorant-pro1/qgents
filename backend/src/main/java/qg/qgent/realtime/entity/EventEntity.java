package qg.qgent.realtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 项目级实时事件存储（SSE 数据源）。 以项目内单调递增的 sequenceNo 作为游标支持断线续传；事件至少保留 24 小时。 payload 必须为已脱敏内容，禁止包含 Token、密码、私钥等敏感信息。
 */
@Data
@TableName(value = "events", autoResultMap = true)
public class EventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属项目ID。
    private Long projectId;
    // 可选关联项目群聊ID。
    private Long groupId;
    // 项目内单调递增事件序号，作为 SSE 游标。
    private Long sequenceNo;
    // 事件类型，如 message.created / agent-run.updated。
    private String eventType;
    // 关联资源ID字符串，如 agentRunId。
    private String resourceId;

    // 脱敏事件载荷 JSON。
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private LocalDateTime createdAt;
}
