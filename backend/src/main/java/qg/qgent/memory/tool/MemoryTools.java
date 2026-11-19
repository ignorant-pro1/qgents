package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolErrors;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.ProjectMemoryService;

/**
 * 让当前 AgentRun 按触发者的项目权限读取项目 memory.md；写入由主 Agent 的 MemoryWriteTools 完成。
 */
public class MemoryTools {

    private final ProjectMemoryService memories;
    private final Long actor;
    private final Long projectId;

    public MemoryTools(ProjectMemoryService memories, Long actor, Long projectId) {
        this.memories = memories;
        this.actor = actor;
        this.projectId = projectId;
    }

    /**
     * 读取整篇项目记忆；写入前据此保留无关内容。
     */
    @ParallelTool
    @Tool(
        name = "read_project_memory",
        description = "读取项目长期记忆 memory.md 最新全文。" + "上下文包含的是启动时快照，准备写入或需要确认最新内容时调用。")
    public ToolResult readProjectMemory() {
        return ToolErrors.call(
            "MEMORY_UNAVAILABLE",
            "项目记忆操作失败",
            () -> {
                ProjectMemoryResponse memory = memories.get(actor, projectId);
                ToolResult result = ToolResult.ok();
                result.put("content", memory.getContent());
                result.put(
                    "updatedAt",
                    memory.getUpdatedAt() == null
                        ? null
                        : memory.getUpdatedAt().toString());
                return result;
            });
    }
}
