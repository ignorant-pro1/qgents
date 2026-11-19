package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.harness.tool.ToolErrors;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.service.GroupMemoryExtractionService;

/**
 * 把用户提出的群记忆纠错交给后台维护流程；普通对话中的 Agent 不能直接写群记忆。
 */
public class GroupMemoryTools {

    private final GroupMemoryExtractionService memories;
    private final Long actor;
    private final Long projectId;
    private final Long groupId;

    public GroupMemoryTools(
        GroupMemoryExtractionService memories, Long actor, Long projectId, Long groupId) {
        this.memories = memories;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
    }

    @Tool(
        name = "request_group_memory_correction",
        description =
            "用户指出当前群记忆记错时调用，把用户说明的准确内容提交给后台提炼流程，下次提炼时修正群记忆。"
                + "群记忆由后台自动维护，你不能直接修改；提交后如实告知用户会在后台更新。")
    public ToolResult requestGroupMemoryCorrection(
        @ToolParam(description = "用户指出的错误以及准确的记忆内容") String correction) {
        if (correction == null || correction.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "correction 不能为空");
        }
        return ToolErrors.call(
            "GROUP_MEMORY_UNAVAILABLE",
            "群记忆纠错提交失败",
            () -> {
                memories.requestCorrection(actor, projectId, groupId, correction);
                ToolResult result = ToolResult.ok();
                result.put("message", "已提交群记忆纠错，后台提炼会在下一次更新时修正群记忆");
                return result;
            });
    }
}
