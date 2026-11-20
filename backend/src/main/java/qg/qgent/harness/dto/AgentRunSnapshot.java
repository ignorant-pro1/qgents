package qg.qgent.harness.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 一次 Agent 运行的可展示快照：内存中的流式进度 + 数据库终态。
 */
@Data
@AllArgsConstructor
public class AgentRunSnapshot {

    @Schema(description = "运行 ID")
    private String runId;

    @Schema(description = "父运行 ID；根运行为空")
    private String parentRunId;

    @Schema(description = "根运行 ID")
    private String rootRunId;

    private String projectId;
    private String groupId;
    private String agentId;
    private String triggerMessageId;

    @Schema(description = "QUEUED/RUNNING/CANCELLING/CANCELLED/SUCCEEDED/FAILED")
    private String status;

    @Schema(description = "内存中当前模型轮次；不在本进程内存时为 0")
    private int round;

    @Schema(description = "数据库已完成的模型调用数，仅收尾时累加")
    private int rounds;

    @Schema(description = "已推送的最后一个 delta 序号")
    private long seq;

    @Schema(description = "运行状态版本号；每次 updated/tool/delta 单调 +1")
    private long revision;

    @Schema(description = "已 flush 的可展示正文分段")
    private List<Segment> segments;

    @Schema(description = "运行内的工具调用，按开始顺序，含已结束的工具")
    private List<Tool> tools;

    @Schema(description = "数据库中的最终结果文本")
    private String result;

    private String createdAt;
    private String finishedAt;

    public record Segment(int round, String text) {
    }

    public record Tool(
        String toolCallId, String tool, int round, String state, String input, String output) {
    }
}
