package qg.qgent.sandbox.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射 tool_executions 表的工具执行持久化实体。
 */
@Data
@TableName("tool_executions")
public class ToolExecutionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String ownerInstanceId;
    private String sandboxId;
    private Long agentRunId;
    private Long workspaceId;
    private String toolName;
    private String argumentsJson;
    private ExecutionStatus status;
    private Integer exitCode;
    private String resultJson;
    private String failureCode;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
