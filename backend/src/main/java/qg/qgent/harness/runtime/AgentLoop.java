package qg.qgent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 所有 Agent 共用的循环：工具结果回给模型，普通文本结束本次运行。
 */
@Component
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private final ChatModel model;
    private final ToolCallExecutor toolCalls;
    private final int maxRounds;
    private final Duration modelIdleTimeout;

    @Autowired
    public AgentLoop(
        ChatModel model,
        ObjectMapper json,
        @Value("${app.agent.max-rounds:30}") int maxRounds,
        @Value("${app.agent.max-parallel-tool-calls:4}") int maxParallelToolCalls,
        @Value("${app.agent.model-idle-timeout:45s}") Duration modelIdleTimeout) {
        this.model = model;
        this.toolCalls = new ToolCallExecutor(json, maxParallelToolCalls);
        if (maxRounds < 1) throw new IllegalArgumentException("max-rounds 必须大于 0");
        if (modelIdleTimeout.isNegative() || modelIdleTimeout.isZero())
            throw new IllegalArgumentException("model-idle-timeout 必须大于 0");
        this.maxRounds = maxRounds;
        this.modelIdleTimeout = modelIdleTimeout;
    }

    AgentLoop(ChatModel model, ObjectMapper json, int maxRounds, int maxParallelToolCalls) {
        this(model, json, maxRounds, maxParallelToolCalls, Duration.ofSeconds(45));
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
                ChatResponse response;
                try {
                    response = chat(runId, round, new Prompt(request, options), sink, cancelled);
                } catch (RuntimeException error) {
                    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                        return new Result(false, "Agent 运行已取消", round);
                    ModelFailure failure = modelFailure(error);
                    log.warn("Agent model failed runId={} round={} category={} type={}",
                        runId, round, failure.name(), error.getClass().getSimpleName());
                    return new Result(false, failure.message, round);
                }
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
                conversation.applyStagedWorkflow();
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
    private ChatResponse chat(
        Long runId, int round, Prompt prompt, RunStreamSink sink, BooleanSupplier cancelled) {
        MessageAggregator aggregator = new MessageAggregator();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        StringBuilder reasoning = new StringBuilder();
        AtomicLong firstChunkMs = new AtomicLong(-1);
        long started = System.nanoTime();
        Flux.defer(() -> cancelled.getAsBoolean() ? Flux.<ChatResponse>empty() : model.stream(prompt))
            .timeout(modelIdleTimeout)
            .doOnNext(chunk -> firstChunkMs.compareAndSet(-1, elapsedMillis(started)))
            .retryWhen(Retry.backoff(2, Duration.ofMillis(400))
                .maxBackoff(Duration.ofSeconds(2))
                .filter(error -> firstChunkMs.get() < 0 && !cancelled.getAsBoolean()
                    && modelFailure(error).retryable)
                .doBeforeRetry(retry -> log.info("Retrying Agent model runId={} round={} retry={} category={}",
                    runId, round, retry.totalRetries() + 1, modelFailure(retry.failure()).name()))
                .onRetryExhaustedThrow((spec, retry) -> retry.failure()))
            .onErrorResume(error -> {
                // 供应商错误只走下方的分类日志，避免聚合器记录原始响应。
                streamFailure.set(error);
                return Flux.empty();
            })
            .doOnNext(chunk -> forward(chunk, sink, cancelled, reasoning))
            .transform(flux -> aggregator.aggregate(flux, completed::set))
            .blockLast(Duration.ofMinutes(3).plus(modelIdleTimeout));
        if (streamFailure.get() != null) throw Exceptions.propagate(streamFailure.get());
        ChatResponse response = completed.get();
        var usage = response == null || response.getMetadata() == null
            ? null : response.getMetadata().getUsage();
        var options = (OpenAiChatOptions) prompt.getOptions();
        log.info("Agent model round completed runId={} round={} tools={} firstChunkMs={} elapsedMs={} promptTokens={} completionTokens={}",
            runId, round, options.getToolCallbacks().size(), firstChunkMs.get(), elapsedMillis(started),
            usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens());
        return withFullReasoning(response, reasoning);
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static ModelFailure modelFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                return switch (service.statusCode()) {
                    case 401, 403 -> ModelFailure.AUTH;
                    case 408, 504 -> ModelFailure.TIMEOUT;
                    case 429 -> ModelFailure.RATE_LIMIT;
                    case 500, 502, 503 -> ModelFailure.UNAVAILABLE;
                    default -> ModelFailure.REJECTED;
                };
            }
            if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException)
                return ModelFailure.TIMEOUT;
            if (cause instanceof OpenAIIoException || cause instanceof java.net.ConnectException)
                return ModelFailure.UNAVAILABLE;
        }
        return ModelFailure.UNKNOWN;
    }

    private enum ModelFailure {
        AUTH("模型服务鉴权失败，请检查 API Key 或模型权限", false),
        RATE_LIMIT("模型服务请求受限，请稍后重试", true),
        TIMEOUT("模型服务响应超时，请稍后重试", true),
        UNAVAILABLE("模型服务暂不可用，请稍后重试", true),
        REJECTED("模型服务拒绝了请求，请检查模型配置或缩短输入", false),
        UNKNOWN("模型调用失败，请稍后重试", false);

        private final String message;
        private final boolean retryable;

        ModelFailure(String message, boolean retryable) {
            this.message = message;
            this.retryable = retryable;
        }
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
        private String systemPrompt;
        private final List<Message> history;
        private final List<StagedImage> stagedImages = new ArrayList<>();
        private StagedWorkflow stagedWorkflow;

        private Conversation(String systemPrompt, List<Message> initialHistory) {
            this.systemPrompt = systemPrompt;
            this.history = new ArrayList<>(initialHistory);
        }

        public synchronized void addUserMessage(String text) {
            history.add(new UserMessage(text));
        }

        synchronized void stageWorkflow(String prompt, String context) {
            stagedWorkflow = new StagedWorkflow(prompt, context);
        }

        private synchronized void applyStagedWorkflow() {
            if (stagedWorkflow == null) return;
            systemPrompt = stagedWorkflow.prompt();
            history.add(new UserMessage(stagedWorkflow.context()));
            stagedWorkflow = null;
        }

        private record StagedWorkflow(String prompt, String context) {}

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
