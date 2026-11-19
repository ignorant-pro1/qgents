package qg.qgent.sandbox.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 工具执行的持久化结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResponse {
    private Long id;
    private String ownerInstanceId;
    private UUID sandboxId;
    private String tool;
    private ExecutionStatus status;
    private Integer exitCode;
    private Map<String, Object> result;
    // 稳定失败码，正常结束为 null。
    private String failureCode;
    private String failureReason;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
}
