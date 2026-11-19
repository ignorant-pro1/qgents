package qg.qgent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 所有 Agent 共用的循环：工具结果回给模型，普通文本结束本次运行。
 */
@Component
public class AgentLoop {
    private final ChatModel model;
    private final ToolCallExecutor toolCalls;
    private final int maxRounds;

    public AgentLoop(
        ChatModel model,
        ObjectMapper json,
        @Value("${app.agent.max-rounds:30}") int maxRounds,
        @Value("${app.agent.max-parallel-tool-calls:4}") int maxParallelToolCalls) {
        this.model = model;
        this.toolCalls = new ToolCallExecutor(json, maxParallelToolCalls);
        if (maxRounds < 1) throw new IllegalArgumentException("max-rounds 必须大于 0");
        this.maxRounds = maxRounds;
    }

    /**
     * Creates a reusable, independent model conversation for a delegated child run.
     */
    public Conversation conversation(String systemPrompt, List<Message> initialHistory) {
        return new Conversation(systemPrompt, initialHistory);
    }

    /**
     * 逐轮调用模型，将工具结果加入历史；取消、轮次耗尽或模型异常回复时停止。
     */
    public Result run(
        Long runId,
        String systemPrompt,
        List<Message> initialHistory,
        Supplier<List<ToolCallback>> availableTools,
        BooleanSupplier cancelled) {
        return run(
            runId,
            conversation(systemPrompt, initialHistory),
            availableTools,
            cancelled,
            UpdateSource.NONE,
            RunStreamSink.NONE);
    }

    /**
     * Continues a saved conversation, retaining earlier user, assistant, and tool messages.
     */
    public Result run(
        Long runId,
        Conversation conversation,
        Supplier<List<ToolCallback>> availableTools,
        BooleanSupplier cancelled) {
        return run(runId, conversation, availableTools, cancelled, UpdateSource.NONE);
    }

    /**
     * Runs a conversation that can receive asynchronous completion notices from delegated work.
     */
    public Result run(
        Long runId,
        Conversation conversation,
        Supplier<List<ToolCallback>> availableTools,
        BooleanSupplier cancelled,
        UpdateSource updates) {
        return run(runId, conversation, availableTools, cancelled, updates, RunStreamSink.NONE);
    }

    /**
     * 运行一段对话，并把可展示的正文和工具阶段同步给实时层。
     */
    public Result run(
        Long runId,
        Conversation conversation,
        Supplier<List<ToolCallback>> availableTools,
        BooleanSupplier cancelled,
        UpdateSource updates,
        RunStreamSink sink) {
        try {
            for (int round = 1; round <= maxRounds; round++) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                    return new Result(false, "Agent 运行已取消", round - 1);
                }
                conversation.appendAll(updates.drain());
                List<ToolCallback> tools = availableTools.get();
                Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
                for (ToolCallback tool : tools) {
                    String name = tool.getToolDefinition().name();
                    if (callbacks.putIfAbsent(name, tool) != null) {
                        throw new IllegalArgumentException("重复工具名：" + name);
                    }
                }
                if (!(model.getOptions() instanceof OpenAiChatOptions defaults)) {
                    throw new IllegalStateException("当前模型需要 OpenAiChatOptions");
                }
                OpenAiChatOptions options =
                    defaults.mutate()
                        .toolCallbacks(tools)
                        .toolChoice(tools.isEmpty() ? "none" : "auto")
                        .maxRetries(0)
                        .build();
                List<Message> request = new ArrayList<>();
                request.add(new SystemMessage(conversation.systemPrompt()));
                request.addAll(conversation.history());
                sink.roundStarted();
                ChatResponse response = chat(new Prompt(request, options), sink, cancelled);
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                    return new Result(false, "Agent 运行已取消", round);
                }
                if (response == null || response.getResult() == null) {
                    return new Result(false, "模型没有返回有效回复", round);
                }
                if (response.getResult().getMetadata() != null
                    && "length"
                    .equalsIgnoreCase(
                        response.getResult().getMetadata().getFinishReason())) {
                    return new Result(false, "模型回复被截断，请缩小问题范围后重试", round);
                }
                AssistantMessage assistant = response.getResult().getOutput();
                if (assistant == null) return new Result(false, "模型没有返回有效回复", round);
                if (!assistant.hasToolCalls()) {
                    String text = assistant.getText();
                    if (text == null || text.isBlank()) return new Result(false, "模型返回了空回复", round);
                    conversation.append(assistant);
                    List<Message> arrivedBeforeWait = updates.drain();
                    conversation.appendAll(arrivedBeforeWait);
                    List<Message> completedWork = updates.awaitNext();
                    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                        return new Result(false, "Agent 运行已取消", round);
                    }
                    if (!arrivedBeforeWait.isEmpty() || !completedWork.isEmpty()) {
                        conversation.appendAll(completedWork);
                        continue;
                    }
                    return new Result(true, text, round);
                }
                // 保留原始消息及供应商元数据，下一轮完整回传 tool_calls/reasoning_content。
                conversation.append(assistant);
                ToolCallExecutor.Result results =
                    toolCalls.execute(
                        runId, assistant.getToolCalls(), callbacks, cancelled, sink);
                if (results.cancelled()) return new Result(false, "Agent 运行已取消", round);
                conversation.append(
                    ToolResponseMessage.builder().responses(results.responses()).build());
                // 图片必须在完整工具结果之后追加，且只在确实还有下一轮模型调用时发送。
                if (round < maxRounds) conversation.appendAll(conversation.takeStagedImages());
            }
            return new Result(false, "Agent 已达到最大调用轮次（" + maxRounds + "），请缩小问题范围后重试", maxRounds);
        } finally {
            conversation.clearStagedImages();
        }
    }

    /**
     * 逐步消费模型流：正文增量交给实时层，结束时用聚合结果保留完整 tool calls 与供应商元数据。
     * reasoning 增量按 chunk 拼接，避免聚合器只保留最后一段。
     */
    private ChatResponse chat(Prompt prompt, RunStreamSink sink, BooleanSupplier cancelled) {
        MessageAggregator aggregator = new MessageAggregator();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        StringBuilder reasoning = new StringBuilder();
        model.stream(prompt)
            .doOnNext(chunk -> forward(chunk, sink, cancelled, reasoning))
            .transform(flux -> aggregator.aggregate(flux, completed::set))
            .blockLast();
        return withFullReasoning(completed.get(), reasoning);
    }

    private void forward(
        ChatResponse chunk,
        RunStreamSink sink,
        BooleanSupplier cancelled,
        StringBuilder reasoning) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
        AssistantMessage message = chunk.getResult().getOutput();
        if (message.getMetadata().get("reasoningContent") instanceof String thoughts) {
            reasoning.append(thoughts);
        }
        // 思考内容不进正文流；取消后立即停止推送。
        if (cancelled.getAsBoolean()) return;
        if (Boolean.parseBoolean(
            String.valueOf(message.getMetadata().getOrDefault("isThought", "false")))) return;
        String text = message.getText();
        if (text != null && !text.isEmpty()) sink.delta(text);
    }

    private ChatResponse withFullReasoning(ChatResponse response, StringBuilder reasoning) {
        if (response == null || response.getResult() == null || reasoning.isEmpty()) return response;
        AssistantMessage message = response.getResult().getOutput();
        Map<String, Object> properties = new HashMap<>(message.getMetadata());
        properties.put("reasoningContent", reasoning.toString());
        AssistantMessage aggregated =
            AssistantMessage.builder()
                .content(message.getText())
                .properties(properties)
                .toolCalls(message.getToolCalls())
                .build();
        return new ChatResponse(
            List.of(new Generation(aggregated, response.getResult().getMetadata())),
            response.getMetadata());
    }

    public record Result(boolean success, String text, int rounds) {
    }

    /**
     * Supplies child completion messages to the main loop without polling storage.
     */
    public interface UpdateSource {
        UpdateSource NONE =
            new UpdateSource() {
                public List<Message> drain() {
                    return List.of();
                }

                public List<Message> awaitNext() {
                    return List.of();
                }
            };

        List<Message> drain();

        List<Message> awaitNext();
    }

    /**
     * A conversation can be resumed during the current root run without copying another agent's history.
     */
    public static final class Conversation {
        private final String systemPrompt;
        private final List<Message> history;
        private final List<StagedImage> stagedImages = new ArrayList<>();

        private Conversation(String systemPrompt, List<Message> initialHistory) {
            this.systemPrompt = systemPrompt;
            this.history = new ArrayList<>(initialHistory);
        }

        public synchronized void addUserMessage(String text) {
            history.add(new UserMessage(text));
        }

        /**
         * 图片暂存在各自会话中，由 loop 在工具结果之后追加。
         */
        public synchronized void stageImage(String attachmentId, String fileName, Media media) {
            stagedImages.add(new StagedImage(attachmentId, fileName, media));
        }

        private synchronized List<Message> takeStagedImages() {
            if (stagedImages.isEmpty()) return List.of();
            StringBuilder text = new StringBuilder("以下图片由 read_attachment 读取，按工具调用顺序附在本消息中：\n");
            List<Media> media = new ArrayList<>();
            for (StagedImage image : stagedImages) {
                text.append("- 附件 ").append(image.attachmentId());
                if (image.fileName() != null && !image.fileName().isBlank()) {
                    text.append(' ').append(image.fileName());
                }
                text.append('\n');
                media.add(image.media());
            }
            stagedImages.clear();
            return List.of(UserMessage.builder().text(text.toString()).media(media).build());
        }

        private synchronized void clearStagedImages() {
            stagedImages.clear();
        }

        private record StagedImage(String attachmentId, String fileName, Media media) {
        }

        private String systemPrompt() {
            return systemPrompt;
        }

        private synchronized List<Message> history() {
            return List.copyOf(history);
        }

        private synchronized void append(Message message) {
            history.add(message);
        }

        private synchronized void appendAll(List<Message> messages) {
            history.addAll(messages);
        }
    }
}
