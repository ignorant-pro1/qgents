package qg.qgent.harness.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次工具调用：运行结束后仍留在库里，供聊天回看工具列表与参数、结果。
 */
@Data
@TableName("agent_run_tools")
public class AgentRunToolEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String toolCallId;
    private String tool;
    private Integer round;
    // running/ok/failed/done，与前端契约同名。
    private String state;
    private String input;
    private String output;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
