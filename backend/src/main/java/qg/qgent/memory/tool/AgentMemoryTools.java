package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.service.AgentMemoryService;

/**
 * 让当前 AgentRun 维护它自己的项目内私有记忆。
 * <p>
 * 项目、Agent 与群都由运行绑定，工具参数无法指定其他 Agent、项目或群；记忆本身在各群共享。
 */
public class AgentMemoryTools {

    private final AgentMemoryService memories;
    private final Long projectId;
    private final Long agentId;

    public AgentMemoryTools(AgentMemoryService memories, Long projectId, Long agentId) {
        this.memories = memories;
        this.projectId = projectId;
        this.agentId = agentId;
    }

    /**
     * 读取自己的私有记忆全文；写回前先读取，避免覆盖掉未涉及的内容。
     */
    @ParallelTool
    @Tool(name = "read_agent_memory", description = "读取你自己的私有记忆全文。同一 Agent 在各个群共享这一篇。")
    public ToolResult readAgentMemory() {
        ToolResult result = ToolResult.ok();
        result.put("content", memories.view(projectId, agentId));
        return result;
    }

    /**
     * 按片段增删改自己的私有记忆；原文必须唯一出现，否则不写入。
     */
    @Tool(
        name = "edit_agent_memory",
        description =
            "局部增删改你自己的私有记忆：传入要替换的原文片段和替换后的内容，原文必须与 read_agent_memory 的结果完全一致且只出现一次；"
                + "删除时把替换内容传空字符串。整篇重写才用 write_agent_memory。"
                + "内容范围同 write_agent_memory：只记录用户对你的长期要求、分工和偏好。")
    public ToolResult editAgentMemory(
        @ToolParam(description = "私有记忆里要被替换的原文，必须与当前正文完全一致且只出现一次") String anchor,
        @ToolParam(description = "替换后的内容；删除时传空字符串") String replacement) {
        if (anchor == null || anchor.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "anchor 不能为空");
        }
        if (replacement == null) {
            return ToolResult.error("INVALID_ARGUMENT", "replacement 不能为 null，删除时传空字符串");
        }
        memories.edit(projectId, agentId, anchor, replacement);
        ToolResult result = ToolResult.ok();
        result.put("content", memories.view(projectId, agentId));
        return result;
    }

    /**
     * 覆盖自己的私有记忆；只记录分工和用户对该 Agent 的长期要求。
     */
    @Tool(
        name = "write_agent_memory",
        description =
            "覆盖你自己的私有记忆全文。同一 Agent 在各个群共享这一篇。"
                + "只记录用户对你的长期要求、分工和偏好；不要记录某个群内的具体内容、他人隐私或临时任务进展。"
                + "用户已明确要求时直接执行，否则先说明拟增删改的内容并取得同意；写入成功后再说已更新。")
    public ToolResult writeAgentMemory(
        @ToolParam(description = "私有记忆全文；保留未涉及的原内容") String content) {
        if (content == null) {
            return ToolResult.error("INVALID_ARGUMENT", "content 不能为空");
        }
        memories.save(projectId, agentId, content);
        ToolResult result = ToolResult.ok();
        result.put("content", content);
        return result;
    }
}
