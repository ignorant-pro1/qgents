package qg.qgent.chat.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 当前群聊的历史消息检索与全文读取；检索次数按 AgentRun 计数。
 */
@Slf4j
public class ChatHistorySearchTool {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ChatHistoryService history;
    private final Long actor;
    private final Long projectId;
    private final Long groupId;
    private final int maxSearches;
    private final AtomicInteger searchesUsed = new AtomicInteger();

    public ChatHistorySearchTool(
        ChatHistoryService history, Long actor, Long projectId, Long groupId, int maxSearches) {
        this.history = history;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
        this.maxSearches = Math.max(0, maxSearches);
    }

    /**
     * 按关键词检索当前群聊的历史消息。
     */
    @ParallelTool
    @Tool(
        name = "search_chat_history",
        description =
            "仅在当前项目群聊的历史聊天记录中按关键字检索；" + "当最近消息窗口缺少所需讨论时调用。query 必填，每个 AgentRun 的调用次数有限")
    public ToolResult searchChatHistory(
        @ToolParam(description = "从任务或讨论中提取的非空检索关键字") String query,
        @ToolParam(description = "请求查询的最大命中数，默认 10、最多 50", required = false) Integer limit) {
        if (query == null || query.isBlank()) {
            return ToolResult.error(
                "CHAT_HISTORY_QUERY_REQUIRED", "search_chat_history 需要非空 query，请使用具体关键字定位历史讨论");
        }
        if (groupId == null) {
            return ToolResult.error(
                "CHAT_HISTORY_GROUP_REQUIRED", "search_chat_history 当前运行未关联项目群聊，无法检索聊天记录");
        }
        if (searchesUsed.getAndUpdate(used -> Math.min(used + 1, maxSearches)) >= maxSearches) {
            return ToolResult.error(
                "CHAT_HISTORY_SEARCH_LIMIT",
                "search_chat_history 检索预算已用尽（已调用 "
                    + searchesUsed.get()
                    + "/"
                    + maxSearches
                    + " 次），请基于已有上下文继续");
        }
        int messageLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        final List<ContextMessage> messages;
        try {
            messages = history.searchChatHistory(actor, projectId, groupId, query, messageLimit);
        } catch (RuntimeException exception) {
            log.info(
                "chat history search failed projectId={} groupId={} category={}",
                projectId,
                groupId,
                exception.getClass().getSimpleName());
            return ToolResult.error(
                "CHAT_HISTORY_UNAVAILABLE",
                "search_chat_history 检索失败: " + safeMessage(exception));
        }
        ToolResult result = ToolResult.ok();
        result.put("budget", "used " + searchesUsed.get() + "/" + maxSearches);
        result.put("messages", ChatToolOutput.digest(messages));
        log.info(
            "chat history searched projectId={} groupId={} used={}",
            projectId,
            groupId,
            searchesUsed.get());
        return result;
    }

    @ParallelTool
    @Tool(
        name = "read_chat_message",
        description = "按消息 ID 读取当前群消息全文及 replyToId；用于展开搜索命中或读取回复的原消息，不需要沙箱")
    public ContextMessage readChatMessage(
        @ToolParam(description = "消息 ID，来自上下文、搜索结果或 replyToId") String messageId) {
        return history.readChatMessage(actor, projectId, groupId, Long.valueOf(messageId));
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null
            || throwable.getMessage() == null
            || throwable.getMessage().isBlank()) {
            return "chat history unavailable";
        }
        String firstLine =
            throwable
                .getMessage()
                .strip()
                .lines()
                .findFirst()
                .orElse("chat history unavailable");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }
}
