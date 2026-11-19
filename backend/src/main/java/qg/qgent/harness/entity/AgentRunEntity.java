package qg.qgent.harness.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次聊天执行；子执行通过 parentRunId 关联，关联触发消息、实际 Agent 身份和工作区。
 */
@Data
@TableName("agent_runs")
public class AgentRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long groupId;
    private Long agentId;
    private Long triggerMessageId;
    private Long parentRunId;
    private Long workspaceId;
    private Long createdBy;
    private RunStatus status;
    private String instruction;
    private String result;
    private Integer rounds;
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;
}
