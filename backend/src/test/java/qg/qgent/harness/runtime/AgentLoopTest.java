package qg.qgent.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.MimeType;
import qg.qgent.support.TestIds;
import reactor.core.publisher.Flux;

class AgentLoopTest {
    private final ChatModel model = mock(ChatModel.class);

    private AgentLoop loop(int rounds) {
        when(model.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("test-model").build());
        // 默认把既有的单次 call 桩适配成单 chunk 流；需要多 chunk 的用例再单独覆盖 stream。
        when(model.stream(any(Prompt.class)))
                .thenAnswer(invocation -> Flux.just(model.call((Prompt) invocation.getArgument(0))));
        return new AgentLoop(model, new ObjectMapper(), rounds, 4);
    }

    @Test
    void textReplyFinishesWithoutExecutingTools() {
        AgentLoop loop = loop(3);
        when(model.call(any(Prompt.class))).thenReturn(reply("你好"));
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new UserMessage("你好")),
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        () -> false);
        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("你好");
        assertThat(tools.calls).isZero();
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void retriesTransientModelFailureBeforeAnyChunk() {
        AgentLoop loop = loop(1);
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new ConnectException()), Flux.just(reply("done")));

        var result = loop.run(TestIds.next(), "agent", List.of(new UserMessage("task")),
                List::of, () -> false);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("done");
        assertThat(result.rounds()).isEqualTo(1);
        verify(model, times(2)).stream(any(Prompt.class));
    }

    @Test
    void doesNotRetryOrRunToolsAfterAStreamHasStarted() {
        AgentLoop loop = loop(2);
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.just(toolCall("count")).concatWith(Flux.error(new ConnectException())));
        Counter tools = new Counter();

        var result = loop.run(TestIds.next(), "agent", List.of(new UserMessage("task")),
                () -> Arrays.asList(ToolCallbacks.from(tools)), () -> false);

        assertThat(result.success()).isFalse();
        assertThat(tools.calls).isZero();
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test
    void executesToolThenPassesResultBackToModel() {
        AgentLoop loop = loop(3);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("count"), reply("完成"));
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new UserMessage("执行")),
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        () -> false);
        assertThat(result.success()).isTrue();
        assertThat(tools.calls).isEqualTo(1);
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(requests.capture());
        assertThat(requests.getAllValues().get(1).getInstructions())
                .anySatisfy(message -> {
                    if (message instanceof ToolResponseMessage response) {
                        assertThat(response.getResponses().getFirst().id()).isEqualTo("call-1");
                        assertThat(response.getResponses().getFirst().responseData()).contains("1");
                    } else {
                        throw new AssertionError("需要工具结果消息");
                    }
                });
        assertThat(requests.getAllValues().get(1).getInstructions().getLast())
                .isInstanceOf(ToolResponseMessage.class);
        assertThat(
                        ((OpenAiChatOptions) requests.getAllValues().getFirst().getOptions())
                                .getToolChoice())
                .isEqualTo("auto");
    }

    @Test
    void continuingChildConversationKeepsEarlierToolResults() {
        AgentLoop loop = loop(4);
        when(model.call(any(Prompt.class)))
                .thenReturn(toolCall("count"), reply("第一轮完成"), reply("补充后完成"));
        Counter tools = new Counter();
        AgentLoop.Conversation conversation =
                loop.conversation("临时子 Agent", List.of(new UserMessage("检查代码")));

        assertThat(
                        loop.run(
                                        TestIds.next(),
                                        conversation,
                                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                                        () -> false)
                                .success())
                .isTrue();
        conversation.addUserMessage("再说明刚才工具返回的数字");
        AgentLoop.Result resumed =
                loop.run(
                        TestIds.next(),
                        conversation,
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        () -> false);

        assertThat(resumed.success()).isTrue();
        assertThat(resumed.text()).isEqualTo("补充后完成");
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(requests.capture());
        assertThat(requests.getAllValues().getLast().getInstructions())
                .anySatisfy(message -> assertThat(message.getText()).contains("再说明刚才工具返回的数字"))
                .anySatisfy(message -> {
                    if (message instanceof ToolResponseMessage response) {
                        assertThat(response.getResponses().getFirst().responseData()).contains("1");
                    } else {
                        throw new AssertionError("续接应保留此前工具结果");
                    }
                });
    }

    @Test
    void completionNoticeDrainedAtFinalBoundaryIsSentBackToModel() {
        AgentLoop loop = loop(4);
        when(model.call(any(Prompt.class))).thenReturn(reply("仍有工作"), reply("已汇总"));
        AtomicInteger drains = new AtomicInteger();
        AgentLoop.UpdateSource updates =
                new AgentLoop.UpdateSource() {
                    public List<org.springframework.ai.chat.messages.Message> drain() {
                        return drains.getAndIncrement() == 1
                                ? List.of(new UserMessage("子 Agent 已完成"))
                                : List.of();
                    }

                    public List<org.springframework.ai.chat.messages.Message> awaitNext() {
                        return List.of();
                    }
                };
        AgentLoop.Conversation conversation =
                loop.conversation("Qgent", List.of(new UserMessage("工作")));

        AgentLoop.Result result =
                loop.run(TestIds.next(), conversation, List::of, () -> false, updates);

        assertThat(result.text()).isEqualTo("已汇总");
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(requests.capture());
        assertThat(requests.getAllValues().getLast().getInstructions())
                .anySatisfy(message -> assertThat(message.getText()).contains("子 Agent 已完成"));
    }

    @Test
    void repeatedToolCallsStopAtConfiguredLimit() {
        AgentLoop loop = loop(2);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("count"));
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new UserMessage("执行")),
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        () -> false);
        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("最大调用轮次");
        assertThat(tools.calls).isEqualTo(2);
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void unknownToolReturnsErrorAndLetsModelRecover() {
        AgentLoop loop = loop(3);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("missing"), reply("已修正"));
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new UserMessage("执行")),
                        List::of,
                        () -> false);
        assertThat(result.success()).isTrue();
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(requests.capture());
        ToolResponseMessage response =
                (ToolResponseMessage) requests.getAllValues().get(1).getInstructions().getLast();
        assertThat(response.getResponses().getFirst().responseData()).contains("TOOL_NOT_FOUND");
    }

    @Test
    void annotatedParallelResultsArePassedBackWithOriginalCallIds() {
        AgentLoop loop = loop(3);
        AssistantMessage batch =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "read-a", "function", "read", "{}"),
                                        new AssistantMessage.ToolCall(
                                                "read-b", "function", "read", "{}")))
                        .build();
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(batch))), reply("完成"));
        var callbacks = qg.qgent.harness.tool.ParallelToolCallback.from(new ReadTools());
        var result =
                loop.run(
                        TestIds.next(),
                        "Agent",
                        List.of(new UserMessage("读取")),
                        () -> Arrays.asList(callbacks),
                        () -> false);
        assertThat(result.success()).isTrue();
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(requests.capture());
        ToolResponseMessage response =
                (ToolResponseMessage) requests.getAllValues().get(1).getInstructions().getLast();
        assertThat(response.getResponses())
                .extracting(r -> r.id())
                .containsExactly("read-a", "read-b");
        assertThat(response.getResponses())
                .allSatisfy(r -> assertThat(r.responseData()).contains("content"));
    }

    public static class ReadTools {
        @qg.qgent.harness.tool.ParallelTool
        @Tool(name = "read", description = "读取")
        public String read() {
            return "content";
        }
    }

    @Test
    void stagedImagesAreAppendedAfterFullToolResultsInCallOrder() {
        AgentLoop loop = loop(3);
        AgentLoop.Conversation conversation =
                loop.conversation("Agent", List.of(new UserMessage("查看图片")));
        ImageTools tools = new ImageTools(conversation, () -> {});
        AssistantMessage batch =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1",
                                                "function",
                                                "show_image",
                                                "{\"attachmentId\":\"7\"}"),
                                        new AssistantMessage.ToolCall(
                                                "call-2",
                                                "function",
                                                "show_image",
                                                "{\"attachmentId\":\"8\"}")))
                        .build();
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(batch))), reply("已看到"));

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        conversation,
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        () -> false);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(requests.capture());
        List<org.springframework.ai.chat.messages.Message> instructions =
                requests.getAllValues().get(1).getInstructions();
        assertThat(instructions.get(instructions.size() - 2))
                .isInstanceOf(ToolResponseMessage.class);
        UserMessage images = (UserMessage) instructions.getLast();
        assertThat(images.getText()).contains("附件 7 7.png", "附件 8 8.png");
        assertThat(images.getMedia())
                .extracting(media -> media.getData().toString())
                .containsExactly(dataUri("7"), dataUri("8"));
    }

    @Test
    void lastRoundDoesNotAppendStagedImagesAndClearsThem() {
        AgentLoop loop = loop(1);
        AgentLoop.Conversation conversation =
                loop.conversation("Agent", List.of(new UserMessage("查看图片")));
        ImageTools tools = new ImageTools(conversation, () -> {});
        when(model.call(any(Prompt.class)))
                .thenReturn(toolCall("show_image", "{\"attachmentId\":\"7\"}"));

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        conversation,
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        () -> false);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("最大调用轮次");
        when(model.call(any(Prompt.class))).thenReturn(reply("完成"));
        loop.run(
                TestIds.next(),
                conversation,
                () -> Arrays.asList(ToolCallbacks.from(tools)),
                () -> false);
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(requests.capture());
        assertThat(requests.getAllValues().get(1).getInstructions())
                .noneMatch(message ->
                        message instanceof UserMessage user && !user.getMedia().isEmpty());
    }

    @Test
    void cancellationAfterImageToolDropsStagedImages() {
        AgentLoop loop = loop(3);
        AgentLoop.Conversation conversation =
                loop.conversation("Agent", List.of(new UserMessage("查看图片")));
        AtomicBoolean cancelled = new AtomicBoolean();
        ImageTools tools = new ImageTools(conversation, () -> cancelled.set(true));
        when(model.call(any(Prompt.class)))
                .thenReturn(toolCall("show_image", "{\"attachmentId\":\"7\"}"));

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        conversation,
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        cancelled::get);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("取消");
        verify(model, times(1)).call(any(Prompt.class));
        cancelled.set(false);
        when(model.call(any(Prompt.class))).thenReturn(reply("完成"));
        loop.run(
                TestIds.next(),
                conversation,
                () -> Arrays.asList(ToolCallbacks.from(tools)),
                () -> false);
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(requests.capture());
        assertThat(requests.getAllValues().get(1).getInstructions())
                .noneMatch(message ->
                        message instanceof UserMessage user && !user.getMedia().isEmpty());
    }

    @Test
    void stagedImagesDoNotLeakBetweenConversations() {
        AgentLoop loop = loop(4);
        AgentLoop.Conversation first = loop.conversation("Agent", List.of(new UserMessage("第一张")));
        AgentLoop.Conversation second = loop.conversation("Agent", List.of(new UserMessage("第二张")));
        ImageTools firstTools = new ImageTools(first, () -> {});
        ImageTools secondTools = new ImageTools(second, () -> {});
        when(model.call(any(Prompt.class)))
                .thenReturn(
                        toolCall("show_image", "{\"attachmentId\":\"1\"}"), reply("一"),
                        toolCall("show_image", "{\"attachmentId\":\"2\"}"), reply("二"));

        loop.run(
                TestIds.next(),
                first,
                () -> Arrays.asList(ToolCallbacks.from(firstTools)),
                () -> false);
        loop.run(
                TestIds.next(),
                second,
                () -> Arrays.asList(ToolCallbacks.from(secondTools)),
                () -> false);

        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(4)).call(requests.capture());
        assertThat(
                        ((UserMessage) requests.getAllValues().get(1).getInstructions().getLast())
                                .getText())
                .contains("附件 1")
                .doesNotContain("附件 2");
        assertThat(
                        ((UserMessage) requests.getAllValues().get(3).getInstructions().getLast())
                                .getText())
                .contains("附件 2")
                .doesNotContain("附件 1");
    }

    public static class ImageTools {
        private final AgentLoop.Conversation conversation;
        private final Runnable afterRead;

        ImageTools(AgentLoop.Conversation conversation, Runnable afterRead) {
            this.conversation = conversation;
            this.afterRead = afterRead;
        }

        @Tool(name = "show_image", description = "显示图片")
        public String show(@ToolParam(description = "附件 ID") String attachmentId) {
            conversation.stageImage(
                    attachmentId,
                    attachmentId + ".png",
                    new Media(MimeType.valueOf("image/png"), URI.create(dataUri(attachmentId))));
            afterRead.run();
            return "{\"ok\":true}";
        }
    }

    private static String dataUri(String attachmentId) {
        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(attachmentId.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void forwardsModelTextChunksInOrderAndKeepsAggregatedAnswer() {
        AgentLoop loop = loop(3);
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk("你", "STOP"), chunk("好", "STOP"), chunk("！", "STOP")));
        RecordingSink sink = new RecordingSink();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new UserMessage("打招呼"))),
                        List::of,
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("你好！");
        assertThat(sink.deltas).containsExactly("你", "好", "！");
        assertThat(sink.rounds).isEqualTo(1);
    }

    @Test
    void aggregatesStreamedToolCallAndReportsToolLifecycle() {
        AgentLoop loop = loop(3);
        AssistantMessage toolCall =
                AssistantMessage.builder()
                        .content("先查一下")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-9", "function", "count", "{}")))
                        .build();
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        List.of(new Generation(toolCall, finish("TOOL_CALLS"))))),
                        Flux.just(chunk("完成", "STOP")));
        Counter tools = new Counter();
        RecordingSink sink = new RecordingSink();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new UserMessage("执行"))),
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("完成");
        assertThat(sink.events)
                .contains("tool:started:call-9:count", "tool:finished:call-9:count:null");
        assertThat(sink.rounds).isEqualTo(2);
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).stream(requests.capture());
        ToolResponseMessage response =
                (ToolResponseMessage) requests.getAllValues().get(1).getInstructions().getLast();
        assertThat(response.getResponses().getFirst().id()).isEqualTo("call-9");
    }

    @Test
    void lengthFinishReasonFailsInsteadOfReturningPartialText() {
        AgentLoop loop = loop(3);
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk("写到一半", "STOP"), chunk("被截断", "LENGTH")));
        RecordingSink sink = new RecordingSink();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new UserMessage("长文"))),
                        List::of,
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("截断");
    }

    @Test
    void cancellationDuringStreamStopsForwardingLaterChunks() {
        AgentLoop loop = loop(3);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger seen = new AtomicInteger();
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(chunk("一", "STOP"), chunk("二", "STOP"), chunk("三", "STOP"))
                                .doOnNext(
                                        ignored -> {
                                            if (seen.incrementAndGet() == 2) {
                                                cancelled.set(true);
                                            }
                                        }));
        RecordingSink sink = new RecordingSink();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new UserMessage("停下"))),
                        List::of,
                        cancelled::get,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("取消");
        assertThat(sink.deltas).containsExactly("一");
    }

    @Test
    void concatenatesReasoningMetadataForTheNextRound() {
        AgentLoop loop = loop(3);
        AssistantMessage withToolCall =
                AssistantMessage.builder()
                        .content("")
                        .properties(Map.of("reasoningContent", "考"))
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1", "function", "count", "{}")))
                        .build();
        when(model.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                thought("思"),
                                new ChatResponse(
                                        List.of(new Generation(withToolCall, finish("TOOL_CALLS"))))),
                        Flux.just(chunk("完成", "STOP")));
        RecordingSink sink = new RecordingSink();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new UserMessage("想想"))),
                        () -> Arrays.asList(ToolCallbacks.from(new Counter())),
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isTrue();
        assertThat(sink.deltas).doesNotContain("思", "考");
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).stream(requests.capture());
        AssistantMessage assistant =
                requests.getAllValues().get(1).getInstructions().stream()
                        .filter(AssistantMessage.class::isInstance)
                        .map(AssistantMessage.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertThat(assistant.getMetadata().get("reasoningContent")).isEqualTo("思考");
        assertThat(assistant.getToolCalls())
                .extracting(AssistantMessage.ToolCall::id)
                .containsExactly("call-1");
    }

    private ChatResponse chunk(String text, String finishReason) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text), finish(finishReason))));
    }

    private ChatResponse thought(String reasoning) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder()
                                        .content("")
                                        .properties(Map.of("reasoningContent", reasoning))
                                        .build(),
                                finish("STOP"))));
    }

    private ChatGenerationMetadata finish(String finishReason) {
        return ChatGenerationMetadata.builder().finishReason(finishReason).build();
    }

    static class RecordingSink implements RunStreamSink {
        final List<String> deltas = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int rounds;

        public void roundStarted() {
            rounds++;
            events.add("round:" + rounds);
        }

        public void delta(String text) {
            deltas.add(text);
        }

        public void toolStarted(String toolCallId, String toolName, String input) {
            events.add("tool:started:" + toolCallId + ":" + toolName);
        }

        public void toolFinished(String toolCallId, String toolName, Boolean ok, String output) {
            events.add("tool:finished:" + toolCallId + ":" + toolName + ":" + ok);
        }

        public void finished() {
            events.add("finished");
        }
    }

    private ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void cancellationWhileWaitingForModelPreventsToolExecution() {
        AgentLoop loop = loop(3);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    cancelled.set(true);
                    return toolCall("count");
                });
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new UserMessage("执行")),
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        cancelled::get);
        assertThat(result.success()).isFalse();
        assertThat(tools.calls).isZero();
        verify(model).call(any(Prompt.class));
    }

    @Test
    void cancellationBetweenToolsStopsTheRemainingBatch() {
        AgentLoop loop = loop(3);
        AssistantMessage batch =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1", "function", "count", "{}"),
                                        new AssistantMessage.ToolCall(
                                                "call-2", "function", "count", "{}")))
                        .build();
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(batch))));
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new UserMessage("执行")),
                        () -> Arrays.asList(ToolCallbacks.from(tools)),
                        () -> tools.calls > 0);
        assertThat(result.success()).isFalse();
        assertThat(tools.calls).isEqualTo(1);
        verify(model).call(any(Prompt.class));
    }

    private ChatResponse toolCall(String name) {
        return toolCall(name, "{}");
    }

    private ChatResponse toolCall(String name, String arguments) {
        AssistantMessage message =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1", "function", name, arguments)))
                        .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    public static class Counter {
        int calls;

        @Tool(name = "count", description = "计数")
        public int count() {
            return ++calls;
        }
    }
}
