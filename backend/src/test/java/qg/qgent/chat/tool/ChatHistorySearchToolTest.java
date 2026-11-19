package qg.qgent.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.support.TestIds;

class ChatHistorySearchToolTest {

    private final ChatHistoryService contextService = mock(ChatHistoryService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final Long groupId = TestIds.next();

    @Test
    void onlyQueriesCurrentGroupAndReturnsAtMostTenTruncatedMessages() {
        List<ContextMessage> messages = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            messages.add(new ContextMessage((long) index, "TEXT", "USER", "u", "x".repeat(240)));
        }
        when(contextService.searchChatHistory(actor, projectId, groupId, "登录", 50))
                .thenReturn(messages);
        ChatHistorySearchTool tool =
                new ChatHistorySearchTool(contextService, actor, projectId, groupId, 10);

        Map<String, Object> result = tool.searchChatHistory("登录", 50);

        assertThat(ToolCallbacks.from(tool))
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("search_chat_history", "read_chat_message");
        assertThat(result).containsEntry("ok", true).containsEntry("budget", "used 1/10");
        assertThat((List<?>) result.get("messages")).hasSize(10);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) result.get("messages")).get(0);
        assertThat((String) first.get("text")).hasSize(203).endsWith("...");
        verify(contextService).searchChatHistory(actor, projectId, groupId, "登录", 50);
    }

    @Test
    void blankQueryAndExhaustedBudgetDoNotQueryService() {
        ChatHistorySearchTool tool =
                new ChatHistorySearchTool(contextService, actor, projectId, groupId, 1);
        assertThat(tool.searchChatHistory(" ", null)).containsEntry("ok", false);
        when(contextService.searchChatHistory(actor, projectId, groupId, "key", 10))
                .thenReturn(List.of());
        assertThat(tool.searchChatHistory("key", null)).containsEntry("ok", true);
        assertThat(tool.searchChatHistory("another", null)).containsEntry("ok", false);
        verify(contextService).searchChatHistory(actor, projectId, groupId, "key", 10);
    }

    @Test
    void parallelSearchesCannotExceedTheSharedRunBudget() throws Exception {
        ChatHistorySearchTool tool =
                new ChatHistorySearchTool(contextService, actor, projectId, groupId, 1);
        when(contextService.searchChatHistory(actor, projectId, groupId, "key", 10))
                .thenReturn(List.of());
        var ready = new java.util.concurrent.CountDownLatch(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
            for (int index = 0; index < 8; index++)
                calls.add(
                        pool.submit(() -> {
                            ready.countDown();
                            assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                            return tool.searchChatHistory("key", null);
                        }));
            try {
                assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            int success = 0;
            for (var call : calls)
                if (Boolean.TRUE.equals(
                        call.get(5, java.util.concurrent.TimeUnit.SECONDS).get("ok"))) success++;
            assertThat(success).isEqualTo(1);
            verify(contextService).searchChatHistory(actor, projectId, groupId, "key", 10);
            assertThat(tool.searchChatHistory("key", null)).containsEntry("ok", false);
        }
    }
}
