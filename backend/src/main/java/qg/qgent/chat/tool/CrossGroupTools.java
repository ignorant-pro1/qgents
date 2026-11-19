package qg.qgent.chat.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.CrossGroupAccessService;
import qg.qgent.chat.service.CrossGroupAccessService.CheckedGroup;
import qg.qgent.chat.service.CrossGroupAccessService.Denial;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.service.GroupMemoryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按跨群权限读取其他群的记忆和聊天记录。
 * <p>
 * 每条读取先由 {@link CrossGroupAccessService} 判定提问用户与被调用的 Agent 是否都在来源群；
 * 不满足时只把是哪一侧不在群里告诉模型，由它在回答里如实说明，不统一拦截回答。
 */
@Slf4j
public class CrossGroupTools {

    private final CrossGroupAccessService access;
    private final GroupMemoryService memories;
    private final ChatHistoryService history;
    private final Long actor;
    private final Long projectId;
    private final Long destinationGroupId;
    private final Long agentId;

    public CrossGroupTools(
        CrossGroupAccessService access,
        GroupMemoryService memories,
        ChatHistoryService history,
        Long actor,
        Long projectId,
        Long destinationGroupId,
        Long agentId) {
        this.access = access;
        this.memories = memories;
        this.history = history;
        this.actor = actor;
        this.projectId = projectId;
        this.destinationGroupId = destinationGroupId;
        this.agentId = agentId;
    }

    /**
     * 先列出其他群与各自的读取结果，模型据此把用户说的群名换成群 ID，并知道自己为什么读不到某个群。
     */
    @ParallelTool
    @Tool(
        name = "list_other_groups",
        description =
            "列出项目内其他群（ID 与名称）以及你能否跨群读取。"
                + "用户询问其他群的进展时先调用，再按返回的群 ID 读取该群记忆或检索聊天记录；"
                + "readable 为 false 时按 reason 如实说明，不要猜测或复述该群内容。")
    public ToolResult listOtherGroups() {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (CheckedGroup checked :
            access.otherGroups(projectId, destinationGroupId, actor, agentId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", checked.id());
            item.put("title", checked.title());
            item.put("readable", checked.denial() == null);
            if (checked.denial() != null) {
                item.put("reason", checked.denial().name());
            }
            groups.add(item);
        }
        ToolResult result = ToolResult.ok();
        result.put("groups", groups);
        return result;
    }

    /**
     * 读取其他群的群记忆；提问用户或 Agent 不在该群时返回原因。
     */
    @ParallelTool
    @Tool(
        name = "read_other_group_memory",
        description = "读取其他群的群聊记忆。" + "只有提问用户和你都在该群时才返回内容；不可读时返回原因，按原因如实告诉用户，不要复述该群内容。")
    public ToolResult readOtherGroupMemory(
        @ToolParam(description = "目标群 ID，来自 list_other_groups") String groupId) {
        Long source = requireGroupId(groupId);
        ToolResult denied = denial(source);
        if (denied != null) {
            return denied;
        }
        ToolResult result = ToolResult.ok();
        result.put("group", source.toString());
        result.put("memory", memories.read(projectId, source).getContent());
        return result;
    }

    /**
     * 检索其他群的聊天记录；沿用当前群检索服务的查询能力。
     */
    @ParallelTool
    @Tool(
        name = "search_other_group_chat_history",
        description =
            "在指定群的历史聊天记录中按关键字检索；"
                + "只在该群可跨群读取时返回命中摘要，全文用 read_other_group_chat_message 展开。")
    public ToolResult searchOtherGroupChatHistory(
        @ToolParam(description = "目标群 ID，来自 list_other_groups") String groupId,
        @ToolParam(description = "从问题中提取的非空检索关键字") String query,
        @ToolParam(description = "请求查询的最大命中数，默认 10、最多 50", required = false) Integer limit) {
        Long source = requireGroupId(groupId);
        ToolResult denied = denial(source);
        if (denied != null) {
            return denied;
        }
        List<ContextMessage> messages =
            history.searchChatHistory(actor, projectId, source, query, limit);
        ToolResult result = ToolResult.ok();
        result.put("group", source.toString());
        result.put("messages", ChatToolOutput.digest(messages));
        return result;
    }

    /**
     * 读取其他群某条消息的全文；消息必须属于该群。
     */
    @ParallelTool
    @Tool(
        name = "read_other_group_chat_message",
        description =
            "按消息 ID 读取指定群的消息全文及 replyToId，返回在 message 字段；" + "只在该群可跨群读取时返回，用于展开其他群的检索命中。")
    public ToolResult readOtherGroupChatMessage(
        @ToolParam(description = "目标群 ID，来自 list_other_groups") String groupId,
        @ToolParam(description = "消息 ID，来自该群的检索结果或 replyToId") String messageId) {
        Long source = requireGroupId(groupId);
        ToolResult denied = denial(source);
        if (denied != null) {
            return denied;
        }
        ContextMessage message =
            history.readChatMessage(
                actor,
                projectId,
                source,
                parseId(
                    messageId,
                    "MESSAGE_ID_INVALID",
                    "messageId 必须是检索结果或 replyToId 中的消息 ID"));
        ToolResult result = ToolResult.ok();
        result.put("group", source.toString());
        result.put("message", message);
        return result;
    }

    /**
     * 不能读取时交给模型的结果；null 表示可以读取。
     */
    private ToolResult denial(Long source) {
        Denial denial = access.check(projectId, source, actor, agentId);
        if (denial == null) {
            return null;
        }
        log.info(
            "cross group read denied projectId={} sourceGroupId={} agentId={} denial={}",
            projectId,
            source,
            agentId,
            denial);
        return switch (denial) {
            case REQUESTER_NOT_IN_GROUP -> ToolResult.error(
                "REQUESTER_NOT_IN_GROUP",
                "提问的用户不在该群，不能把该群内容透露给他。告诉他：你不在那个群聊，我不能给你透露那里的内容。");
            case AGENT_NOT_IN_GROUP ->
                ToolResult.error("AGENT_NOT_IN_GROUP", "你不在该群，读不到它的内容。告诉他：我不在那个群，无法读取那个群的消息。");
        };
    }

    private Long requireGroupId(String groupId) {
        return parseId(groupId, "GROUP_ID_INVALID", "groupId 必须是 list_other_groups 返回的群 ID");
    }

    private Long parseId(String value, String code, String message) {
        try {
            return Long.valueOf(value == null ? "" : value.trim());
        } catch (NumberFormatException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
    }
}
