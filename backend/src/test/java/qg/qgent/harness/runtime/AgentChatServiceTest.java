package qg.qgent.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.agent.service.AgentService;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.CrossGroupAccessService;
import qg.qgent.chat.service.GroupService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.github.tool.GitHubCapability;
import qg.qgent.harness.briefing.BriefingService;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.memory.service.GroupMemoryExtractionService;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.sandbox.service.SandboxFiles;
import qg.qgent.sandbox.service.SandboxSession;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.skill.dto.ContextSkill;
import qg.qgent.skill.service.SkillService;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.service.WorkspaceRepositoryService;
import qg.qgent.workspace.service.WorkspaceService;

class AgentChatServiceTest {
    private final ExecutorService childExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService rootExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutors() {
        childExecutor.shutdownNow();
        rootExecutor.shutdownNow();
    }

    @Test
    void rootPromptCarriesAttachmentReferencesWithoutLoadingContentOrMedia() {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        when(model.call(any(Prompt.class))).thenReturn(reply("已收到"));
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        when(fixture.history().recentMessages(actor, project, group, 30))
                .thenReturn(
                        List.of(
                                new ContextMessage(
                                        1L,
                                        "IMAGE",
                                        "USER",
                                        "1",
                                        "",
                                        "9",
                                        "photo.png",
                                        "image/png",
                                        2048L)));

        fixture.service().replyToMention(fixture.event(), fixture.agentId(), "看看图片");

        org.mockito.ArgumentCaptor<Prompt> prompts =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompts.capture());
        UserMessage first = (UserMessage) prompts.getValue().getInstructions().get(1);
        assertThat(first.getMedia()).isEmpty();
        assertThat(first.getText())
                .contains("[1] USER 1：[图片 photo.png，附件 ID 9，类型 image/png，2048 字节]")
                .doesNotContain("attachmentId");
        verify(fixture.attachments(), never()).readImage(any(), any(), any());
        verify(fixture.attachments(), never())
                .readTextChunk(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void runAndReplyBelongToTheMentionedAgent() {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        Long agent = TestIds.next();
        ChatModel model = model();
        when(model.call(any(Prompt.class))).thenReturn(reply("已收到"));
        var fixture = fixture(model, actor, project, group, message, childExecutor);

        fixture.service().replyToMention(fixture.event(), agent, "你好");

        verify(fixture.runs()).startRoot(agent, fixture.event(), "你好");
        verify(fixture.messages()).sendAsAgent(eq(agent), eq(group), any());
    }

    @Test
    void promptAndToolsCarryTheAgentsOwnPrivateMemory() {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        when(model.call(any(Prompt.class))).thenReturn(reply("已记住"));
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        when(fixture.agentMemories().view(project, fixture.agentId())).thenReturn("# 你负责后端接口");

        fixture.service().replyToMention(fixture.event(), fixture.agentId(), "记住分工");

        org.mockito.ArgumentCaptor<Prompt> prompts =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompts.capture());
        Prompt prompt = prompts.getValue();
        assertThat(prompt.getInstructions())
                .anySatisfy(item -> assertThat(item.getText()).contains("# 你负责后端接口"));
        assertThat(((OpenAiChatOptions) prompt.getOptions()).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains(
                        "read_agent_memory",
                        "write_agent_memory",
                        "read_project_memory",
                        "write_project_memory");
    }

    @Test
    void promptCarriesTheAgentsOwnNameDescriptionAndPrompt() {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        when(model.call(any(Prompt.class))).thenReturn(reply("收到"));
        var fixture = fixture(model, actor, project, group, message, childExecutor);

        fixture.service().replyToMention(fixture.event(), fixture.agentId(), "开始评审");

        org.mockito.ArgumentCaptor<Prompt> prompts =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompts.capture());
        assertThat(prompts.getValue().getInstructions().getFirst().getText())
                .contains("你是 评审助手", "负责代码评审", "你只评审 Pull Request");
    }

    @Test
    void agentOutsideTheProjectOrNotApprovedDoesNotRun() {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        when(model.call(any(Prompt.class))).thenReturn(reply("不应被调用"));
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        when(fixture.agents().requireActiveProjectAgent(project, fixture.agentId()))
                .thenThrow(
                        new ApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "AGENT_NOT_ACTIVE",
                                "Agent 未启用或不属于本项目"));

        assertThatThrownBy(() ->
                        fixture.service().replyToMention(fixture.event(), fixture.agentId(), "你好"))
                .isInstanceOf(ApiException.class);

        verify(fixture.runs(), never()).startRoot(any(), any(), any());
        verify(fixture.messages(), never()).sendAsAgent(any(), any(), any());
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void failedRunReportsItsFailureAndStopsItsCommands() {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        // 空白回复让本次运行失败：失败也要按失败文案回复，不能留下静默的空白消息。
        when(model.call(any(Prompt.class))).thenReturn(reply(""));
        var fixture = fixture(model, actor, project, group, message, childExecutor);

        fixture.service().replyToMention(fixture.event(), fixture.agentId(), "帮我申请一个评审助手");

        verify(fixture.messages())
                .sendAsAgent(
                        eq(fixture.agentId()),
                        eq(group),
                        argThat(request -> "模型返回了空回复".equals(request.getContent().get("text"))));
        // 失败与取消一样要等自己名下的命令停下；成功运行不停（见 successfulRunDoesNotStopItsCommands）。
        verify(fixture.sessions()).stopExecutionsByAgentRun(fixture.root().getId());
        assertThat(fixture.root().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void startsChildInBackgroundAndFeedsItsCompletionBackBeforeReplying() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger();
        AtomicReference<String> childInput = new AtomicReference<>();
        AtomicReference<List<String>> childToolNames = new AtomicReference<>();
        CountDownLatch childStarted = new CountDownLatch(1),
                releaseChild = new CountDownLatch(1),
                rootFinishedText = new CountDownLatch(1);
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        childToolNames.set(
                                ((OpenAiChatOptions) prompt.getOptions())
                                        .getToolCallbacks().stream()
                                                .map(callback ->
                                                        callback.getToolDefinition().name())
                                                .toList());
                        childInput.set(prompt.getInstructions().get(1).getText());
                        childStarted.countDown();
                        await(releaseChild);
                        return reply("子任务结果");
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 -> {
                            assertThat(prompt.getInstructions())
                                    .anySatisfy(item ->
                                            assertThat(item.getText())
                                                    .contains("published-skill", "适用于测试任务"));
                            assertThat(((OpenAiChatOptions) prompt.getOptions()).getToolCallbacks())
                                    .extracting(callback -> callback.getToolDefinition().name())
                                    .contains(
                                            "read_attachment",
                                            "read_project_memory",
                                            "write_project_memory");
                            yield toolCall("start_subagent", "{\"instruction\":\"独立分析接口\"}");
                        }
                        case 2 -> {
                            rootFinishedText.countDown();
                            yield reply("我先继续处理");
                        }
                        default -> {
                            assertThat(prompt.getInstructions())
                                    .anySatisfy(item ->
                                            assertThat(item.getText())
                                                    .contains("子 Agent")
                                                    .contains("子任务结果"));
                            yield reply("已汇总子任务结果");
                        }
                    };
                });

        var fixture = fixture(model, actor, project, group, message, childExecutor);
        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "请完成工作"));

        assertTrue(childStarted.await(5, TimeUnit.SECONDS));
        assertTrue(rootFinishedText.await(5, TimeUnit.SECONDS));
        assertFalse(
                future.isDone(),
                "root loop must await/receive active child completion before returning");
        releaseChild.countDown();
        future.get(5, TimeUnit.SECONDS);

        assertThat(rootCalls.get()).isEqualTo(3);
        assertThat(childInput.get())
                .contains(
                        "published-skill", "适用于测试任务", "activate_skill", "# 项目约定", "# 群约定", "# 你的分工")
                .doesNotContain("root-only 群名", "近期群消息");
        assertThat(childToolNames.get())
                .doesNotContain(
                        "start_subagent",
                        "wait_subagent",
                        "get_subagent_result",
                        "message_subagent",
                        "continue_subagent",
                        "stop_subagent",
                        "cancel_agent",
                        "write_project_memory")
                .contains("read_attachment");
        verify(fixture.messages())
                .sendAsAgent(
                        eq(fixture.agentId()),
                        eq(group),
                        argThat(request -> "已汇总子任务结果".equals(request.getContent().get("text"))));
    }

    @Test
    void otherGroupsAreReadThroughToolsBoundToTheCurrentRun() {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        when(model.call(any(Prompt.class))).thenReturn(reply("已了解"));
        var fixture = fixture(model, actor, project, group, message, childExecutor);

        fixture.service().replyToMention(fixture.event(), fixture.agentId(), "后端群最近怎么样？");

        org.mockito.ArgumentCaptor<Prompt> prompts =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompts.capture());
        assertThat(((OpenAiChatOptions) prompts.getValue().getOptions()).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains(
                        "list_other_groups",
                        "read_other_group_memory",
                        "search_other_group_chat_history",
                        "read_other_group_chat_message");
    }

    @Test
    void answersWithAnotherGroupsHistoryWhenTheAskingUserAndAgentAreBothMembers() {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        Long source = TestIds.next();
        ChatModel model = model();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> toolResult = new AtomicReference<>();
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (calls.incrementAndGet() == 1) {
                        return toolCall(
                                "search_other_group_chat_history",
                                "{\"groupId\":\"" + source + "\",\"query\":\"登录\"}");
                    }
                    toolResult.set(
                            ((ToolResponseMessage) prompt.getInstructions().getLast())
                                    .getResponses()
                                    .getFirst()
                                    .responseData());
                    return reply("后端群昨天完成登录联调");
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        when(fixture.groups().groupAgentIds(source)).thenReturn(List.of(fixture.agentId()));
        when(fixture.history().searchChatHistory(actor, project, source, "登录", null))
                .thenReturn(
                        List.of(
                                new ContextMessage(
                                        1L, "TEXT", "USER", actor.toString(), "昨天完成登录联调")));

        fixture.service().replyToMention(fixture.event(), fixture.agentId(), "后端群最近怎么样？");

        assertThat(toolResult.get()).contains("昨天完成登录联调");
        verify(fixture.messages())
                .sendAsAgent(
                        eq(fixture.agentId()),
                        eq(group),
                        argThat(request -> "后端群昨天完成登录联调".equals(request.getContent().get("text"))));
    }

    @Test
    void resumesTheSameChildConversationWithItsEarlierMessages() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger(), childCalls = new AtomicInteger();
        AtomicReference<Long> childRunId = new AtomicReference<>();
        AtomicReference<List<Message>> continuedChildHistory = new AtomicReference<>();
        CountDownLatch rootContinued = new CountDownLatch(1);
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        int call = childCalls.incrementAndGet();
                        if (call == 1) {
                            await(rootContinued);
                            return reply("首次分析结果");
                        }
                        continuedChildHistory.set(prompt.getInstructions());
                        return reply("补充分析结果");
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 -> toolCall("start_subagent", "{\"instruction\":\"分析模块边界\"}");
                        case 2 -> {
                            rootContinued.countDown();
                            yield reply("继续主任务");
                        }
                        case 3 ->
                                toolCall(
                                        "continue_subagent",
                                        "{\"runId\":\""
                                                + childRunId.get()
                                                + "\",\"instruction\":\"再说明具体边界\"}");
                        case 4 -> reply("等待补充结果");
                        default -> reply("最终结果已整理");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity child =
                                    run(invocation.getArgument(0), invocation.getArgument(1));
                            child.setWorkspaceId(invocation.getArgument(2));
                            childRunId.set(child.getId());
                            return child;
                        })
                .when(fixture.runs())
                .startChild(any(), any(), any());

        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "安排并整合"));
        future.get(5, TimeUnit.SECONDS);

        assertThat(childCalls.get()).as("root calls: %s", rootCalls.get()).isEqualTo(2);
        assertThat(continuedChildHistory.get())
                .isNotNull()
                .anySatisfy(item -> assertThat(item.getText()).isEqualTo("首次分析结果"))
                .anySatisfy(item -> assertThat(item.getText()).contains("再说明具体边界"));
        assertThat(rootCalls.get()).isEqualTo(5);
    }

    @Test
    void continuationWaitsUntilChildCompletionAndReservationArePublished() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger(), childCalls = new AtomicInteger();
        AtomicReference<Long> childRunId = new AtomicReference<>();
        AtomicReference<String> continuationToolResult = new AtomicReference<>();
        CountDownLatch childFinishStarted = new CountDownLatch(1),
                releaseChildFinish = new CountDownLatch(1);
        CountDownLatch rootReachedThirdCall = new CountDownLatch(1);
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        if (childCalls.incrementAndGet() == 1)
                            throw new IllegalStateException("temporary child failure");
                        return reply("续接后的子任务结果");
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 -> toolCall("start_subagent", "{\"instruction\":\"先分析\"}");
                        case 2 -> {
                            assertTrue(childFinishStarted.await(5, TimeUnit.SECONDS));
                            yield toolCall(
                                    "continue_subagent",
                                    "{\"runId\":\""
                                            + childRunId.get()
                                            + "\",\"instruction\":\"补充分析\"}");
                        }
                        case 3 -> {
                            continuationToolResult.set(
                                    ((ToolResponseMessage) prompt.getInstructions().getLast())
                                            .getResponses()
                                            .getFirst()
                                            .responseData());
                            rootReachedThirdCall.countDown();
                            yield reply("继续等待子任务");
                        }
                        default -> reply("已整理续接结果");
                    };
                });

        var fixture = fixture(model, actor, project, group, message, childExecutor);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity child =
                                    run(invocation.getArgument(0), invocation.getArgument(1));
                            child.setWorkspaceId(invocation.getArgument(2));
                            childRunId.set(child.getId());
                            return child;
                        })
                .when(fixture.runs())
                .startChild(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity run = invocation.getArgument(0);
                            if (run.getParentRunId() != null
                                    && invocation.getArgument(1, RunStatus.class)
                                            == RunStatus.FAILED) {
                                childFinishStarted.countDown();
                                await(releaseChildFinish);
                            }
                            run.setStatus(invocation.getArgument(1, RunStatus.class));
                            run.setResult(invocation.getArgument(2));
                            return null;
                        })
                .when(fixture.runs())
                .finish(
                        any(AgentRunEntity.class),
                        any(RunStatus.class),
                        any(String.class),
                        org.mockito.ArgumentMatchers.anyInt());

        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "安排并整合"));
        assertTrue(childFinishStarted.await(5, TimeUnit.SECONDS));
        assertTrue(rootReachedThirdCall.await(5, TimeUnit.SECONDS));
        assertThat(childRunId.get()).isNotNull();
        assertThat(continuationToolResult.get()).contains("正在结算");
        assertFalse(
                future.isDone(),
                "child remains active until the command and terminal state are settled");

        releaseChildFinish.countDown();
        future.get(5, TimeUnit.SECONDS);

        assertThat(childCalls.get()).isEqualTo(1);
        assertThat(rootCalls.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void rootAndConcurrentChildrenShareOneLazyDefaultWorkspace() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger();
        var workspacesByRun = new ConcurrentHashMap<Long, Long>();
        CountDownLatch codeCalls = new CountDownLatch(3), releaseCodeCalls = new CountDownLatch(1);
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        boolean hasToolResult =
                                prompt.getInstructions().getLast() instanceof ToolResponseMessage;
                        return hasToolResult ? reply("子任务完成") : toolCall("glob_files", "{}");
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 ->
                                toolCalls(
                                        new AssistantMessage.ToolCall(
                                                "call-1",
                                                "function",
                                                "start_subagent",
                                                "{\"instruction\":\"分析一\"}"),
                                        new AssistantMessage.ToolCall(
                                                "call-2",
                                                "function",
                                                "start_subagent",
                                                "{\"instruction\":\"分析二\"}"));
                        case 2 -> toolCall("glob_files", "{}");
                        default -> reply("已汇总两个子任务");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        WorkspaceEntity workspace = workspace(project);
        AtomicInteger createCount = new AtomicInteger();
        when(fixture.workspaces().defaultWorkspace(project))
                .thenAnswer(ignored -> {
                    createCount.incrementAndGet();
                    return workspace;
                });
        when(fixture.workspaces().require(project, workspace.getId())).thenReturn(workspace);
        when(fixture.sessions().acquire(any(), eq(project), eq(workspace.getId())))
                .thenAnswer(invocation ->
                        new SandboxSession(
                                invocation.getArgument(0),
                                workspace.getId(),
                                java.util.UUID.randomUUID(),
                                WorkspaceService.storageKey(workspace)));
        when(fixture.codeAccess()
                        .listFiles(
                                any(),
                                any(String.class),
                                any(String.class),
                                org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    Long runId = SandboxExecutionContext.currentAgentRunId();
                    workspacesByRun.put(runId, invocation.getArgument(0));
                    codeCalls.countDown();
                    await(releaseCodeCalls);
                    return Map.of("ok", true, "files", List.of(), "truncated", false);
                });

        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "并行分析代码"));
        try {
            assertTrue(
                    codeCalls.await(5, TimeUnit.SECONDS),
                    "root and both children should reach the first code tool");
            assertThat(createCount.get()).isEqualTo(1);
            assertThat(workspacesByRun)
                    .hasSize(3)
                    .allSatisfy((runId, workspaceId) ->
                            assertThat(workspaceId).isEqualTo(workspace.getId()));
        } finally {
            releaseCodeCalls.countDown();
        }
        future.get(5, TimeUnit.SECONDS);

        assertThat(createCount.get()).isEqualTo(1);
        verify(fixture.sessions()).acquire(fixture.root().getId(), project, workspace.getId());
        verify(fixture.sessions()).release(workspace.getId(), fixture.root().getId());
    }

    @Test
    void rootAndChildKeepIndependentWorkspaceAndWorkingDirectorySelections() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        Long rootWorkspaceId = TestIds.next(), childWorkspaceId = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger();
        var locations = new ConcurrentHashMap<Long, CodeLocation>();
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        return switch (prompt.getInstructions().size()) {
                            case 2 ->
                                    toolCall(
                                            "set_working_directory",
                                            "{\"directory\":\"child-next\"}");
                            case 4 -> toolCall("glob_files", "{}");
                            default -> reply("子工作区分析结束");
                        };
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 ->
                                toolCalls(
                                        new AssistantMessage.ToolCall(
                                                "call-1",
                                                "function",
                                                "use_workspace",
                                                "{\"workspaceId\":\"" + rootWorkspaceId + "\"}"),
                                        new AssistantMessage.ToolCall(
                                                "call-2",
                                                "function",
                                                "set_working_directory",
                                                "{\"directory\":\"parent-start\"}"),
                                        new AssistantMessage.ToolCall(
                                                "call-3",
                                                "function",
                                                "start_subagent",
                                                "{\"instruction\":\"使用独立工作区\",\"workspaceId\":\""
                                                        + childWorkspaceId
                                                        + "\",\"directory\":\"child-start\"}"));
                        case 2 ->
                                toolCalls(
                                        new AssistantMessage.ToolCall(
                                                "call-4",
                                                "function",
                                                "set_working_directory",
                                                "{\"directory\":\"parent-next\"}"),
                                        new AssistantMessage.ToolCall(
                                                "call-5", "function", "glob_files", "{}"));
                        default -> reply("两个工作区已分别完成");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        WorkspaceEntity rootWorkspace = workspace(project);
        rootWorkspace.setId(rootWorkspaceId);
        WorkspaceEntity childWorkspace = workspace(project);
        childWorkspace.setId(childWorkspaceId);
        when(fixture.workspaces().require(project, rootWorkspaceId)).thenReturn(rootWorkspace);
        when(fixture.workspaces().require(project, childWorkspaceId)).thenReturn(childWorkspace);
        when(fixture.sessions().acquire(any(), eq(project), any()))
                .thenAnswer(invocation ->
                        new SandboxSession(
                                invocation.getArgument(0),
                                invocation.getArgument(2),
                                java.util.UUID.randomUUID(),
                                "workspaces/" + java.util.UUID.randomUUID()));
        when(fixture.codeAccess()
                        .listFiles(
                                any(),
                                any(String.class),
                                any(String.class),
                                org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    Long runId = SandboxExecutionContext.currentAgentRunId();
                    locations.put(
                            runId,
                            new CodeLocation(invocation.getArgument(0), invocation.getArgument(1)));
                    return Map.of("ok", true, "files", List.of(), "truncated", false);
                });

        fixture.service().replyToMention(fixture.event(), fixture.agentId(), "分别处理两个工作区");

        assertThat(locations).hasSize(2);
        assertThat(locations.get(fixture.root().getId()))
                .isEqualTo(new CodeLocation(rootWorkspaceId, "parent-next"));
        verify(fixture.runs()).startChild(any(), eq("使用独立工作区"), eq(childWorkspaceId));
        verify(fixture.sessions()).acquire(fixture.root().getId(), project, rootWorkspaceId);
        verify(fixture.sessions()).acquire(fixture.root().getId(), project, childWorkspaceId);
        verify(fixture.sessions()).release(rootWorkspaceId, fixture.root().getId());
        verify(fixture.sessions()).release(childWorkspaceId, fixture.root().getId());
        verify(fixture.workspaces(), never()).create(any());
        assertThat(locations.values()).contains(new CodeLocation(childWorkspaceId, "child-next"));
    }

    @Test
    void childFinishingDoesNotReleaseTheWorkspaceItsRootOwns() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        WorkspaceRun run =
                rootRunWaitingForChildThatUsesDefaultWorkspace(actor, project, group, message);

        assertThat(run.childCalls().get()).isEqualTo(2);
        verify(run.fixture().sessions())
                .acquire(run.fixture().root().getId(), project, run.workspace().getId());
        verify(run.fixture().sessions(), org.mockito.Mockito.times(1))
                .release(run.workspace().getId(), run.fixture().root().getId());
    }

    @Test
    void successfulRunDoesNotStopItsCommands() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        WorkspaceRun run =
                rootRunWaitingForChildThatUsesDefaultWorkspace(actor, project, group, message);

        assertThat(run.fixture().root().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        verify(run.fixture().sessions())
                .release(run.workspace().getId(), run.fixture().root().getId());
        verify(run.fixture().sessions(), never()).stopExecutionsByAgentRun(any());
    }

    @Test
    void activeChildLimitRejectsAdditionalChildrenWhileFirstRuns() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        CountDownLatch childStarted = new CountDownLatch(1), releaseChild = new CountDownLatch(1);
        AtomicInteger rootCalls = new AtomicInteger();
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        childStarted.countDown();
                        try {
                            releaseChild.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return reply("唯一子 Agent 已完成");
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 ->
                                toolCalls(
                                        new AssistantMessage.ToolCall(
                                                "call-1",
                                                "function",
                                                "start_subagent",
                                                "{\"instruction\":\"先做\"}"),
                                        new AssistantMessage.ToolCall(
                                                "call-2",
                                                "function",
                                                "start_subagent",
                                                "{\"instruction\":\"再做\"}"));
                        default -> reply("继续等待唯一子 Agent");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor, 1);

        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(
                                        fixture.event(), fixture.agentId(), "限制同时子 Agent 数"));
        try {
            assertTrue(childStarted.await(5, TimeUnit.SECONDS));
            assertFalse(future.isDone(), "root waits for its accepted child before completing");
            verify(fixture.runs(), org.mockito.Mockito.times(1)).startChild(any(), any(), any());
        } finally {
            releaseChild.countDown();
        }
        future.get(5, TimeUnit.SECONDS);
        verify(fixture.runs(), org.mockito.Mockito.times(1)).startChild(any(), any(), any());
    }

    @Test
    void stoppingOneChildReturnsWhileItFinishesAndLeavesSiblingRunning() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger();
        CopyOnWriteArrayList<Long> childIds = new CopyOnWriteArrayList<>();
        CountDownLatch bothChildrenStarted = new CountDownLatch(2),
                firstChildStopAwaiting = new CountDownLatch(1);
        CountDownLatch releaseFirstStop = new CountDownLatch(1),
                releaseSecond = new CountDownLatch(1);
        CountDownLatch rootContinuedAfterStop = new CountDownLatch(1);
        CopyOnWriteArrayList<List<String>> childToolNames = new CopyOnWriteArrayList<>();
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        childToolNames.add(
                                ((OpenAiChatOptions) prompt.getOptions())
                                        .getToolCallbacks().stream()
                                                .map(callback ->
                                                        callback.getToolDefinition().name())
                                                .toList());
                        String input = prompt.getInstructions().get(1).getText();
                        CountDownLatch release =
                                input.contains("停止目标") ? releaseFirstStop : releaseSecond;
                        bothChildrenStarted.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return reply("子 Agent 已响应停止");
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 ->
                                toolCalls(
                                        new AssistantMessage.ToolCall(
                                                "call-1",
                                                "function",
                                                "start_subagent",
                                                "{\"instruction\":\"停止目标\"}"),
                                        new AssistantMessage.ToolCall(
                                                "call-2",
                                                "function",
                                                "start_subagent",
                                                "{\"instruction\":\"保留目标\"}"));
                        case 2 -> {
                            assertTrue(bothChildrenStarted.await(5, TimeUnit.SECONDS));
                            yield toolCall(
                                    "stop_subagent", "{\"runId\":\"" + childIds.getFirst() + "\"}");
                        }
                        case 3 -> {
                            ToolResponseMessage response =
                                    (ToolResponseMessage) prompt.getInstructions().getLast();
                            assertThat(response.getResponses().getFirst().responseData())
                                    .contains("\"stopping\":true");
                            rootContinuedAfterStop.countDown();
                            yield reply("另一个子 Agent 仍在运行");
                        }
                        default -> reply("两个子任务均已结算");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity child =
                                    run(invocation.getArgument(0), invocation.getArgument(1));
                            childIds.add(child.getId());
                            return child;
                        })
                .when(fixture.runs())
                .startChild(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Long runId = invocation.getArgument(0);
                            if (runId.equals(childIds.getFirst())) {
                                firstChildStopAwaiting.countDown();
                                awaitUninterruptibly(releaseFirstStop);
                            }
                            return null;
                        })
                .when(fixture.sessions())
                .stopExecutionsByAgentRun(any());

        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "停止一个子任务"));
        try {
            assertTrue(firstChildStopAwaiting.await(5, TimeUnit.SECONDS));
            assertTrue(rootContinuedAfterStop.await(5, TimeUnit.SECONDS));
            assertFalse(
                    future.isDone(),
                    "the main run must wait for the stopped child and its still-active sibling");
            verify(fixture.sessions()).cancelExecutionsByAgentRun(childIds.getFirst());
            verify(fixture.sessions(), never()).cancelExecutionsByAgentRun(childIds.get(1));
            verify(fixture.runs())
                    .requestChildCancellation(fixture.root().getId(), childIds.getFirst());
            verify(fixture.runs(), never())
                    .requestChildCancellation(fixture.root().getId(), childIds.get(1));
        } finally {
            releaseFirstStop.countDown();
            releaseSecond.countDown();
        }
        future.get(30, TimeUnit.SECONDS);
        assertThat(childIds).hasSize(2);
        assertThat(childToolNames)
                .hasSize(2)
                .allSatisfy(names ->
                        assertThat(names)
                                .doesNotContain(
                                        "start_subagent",
                                        "wait_subagent",
                                        "get_subagent_result",
                                        "message_subagent",
                                        "continue_subagent",
                                        "stop_subagent",
                                        "cancel_agent"));
    }

    @Test
    void rootCancellationRequestsChildStopAndReturnsBeforeWorkersFinish() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger();
        AtomicReference<Runnable> cancellationSignal = new AtomicReference<>();
        AtomicReference<Long> childRunId = new AtomicReference<>();
        CountDownLatch childStarted = new CountDownLatch(1),
                commandAwaitStarted = new CountDownLatch(1);
        CountDownLatch releaseCommandAwait = new CountDownLatch(1);
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        childStarted.countDown();
                        try {
                            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return reply("停止中的子 Agent");
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 -> toolCall("start_subagent", "{\"instruction\":\"等待取消\"}");
                        default -> reply("根运行等待子 Agent");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity child =
                                    run(invocation.getArgument(0), invocation.getArgument(1));
                            childRunId.set(child.getId());
                            return child;
                        })
                .when(fixture.runs())
                .startChild(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            cancellationSignal.set(invocation.getArgument(1));
                            return null;
                        })
                .when(fixture.runs())
                .registerCancellationSignal(any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            cancellationSignal.get().run();
                            return 1;
                        })
                .when(fixture.runs())
                .cancel(any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            commandAwaitStarted.countDown();
                            awaitUninterruptibly(releaseCommandAwait);
                            return null;
                        })
                .when(fixture.sessions())
                .stopExecutionsByAgentRun(any());

        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "准备可取消运行"));
        try {
            assertTrue(childStarted.await(5, TimeUnit.SECONDS));
            var cancelRequest = Executors.newSingleThreadExecutor();
            try {
                var cancelResult =
                        cancelRequest.submit(
                                () -> fixture.runs().cancel(actor, project, group, message));
                assertThat(cancelResult.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                cancelRequest.shutdownNow();
            }
            assertTrue(commandAwaitStarted.await(5, TimeUnit.SECONDS));
            assertFalse(
                    future.isDone(),
                    "root completion waits until child command workers leave the sandbox");
            verify(fixture.sessions()).cancelExecutionsByAgentRun(fixture.root().getId());
            verify(fixture.sessions()).cancelExecutionsByAgentRun(childRunId.get());
            verify(fixture.runs())
                    .requestChildCancellation(fixture.root().getId(), childRunId.get());
        } finally {
            releaseCommandAwait.countDown();
        }
        future.get(5, TimeUnit.SECONDS);
        assertThat(fixture.root().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void stopDuringSuccessfulChildFinalizationCancelsAndAwaitsItsCommands() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger();
        AtomicReference<Long> childRunId = new AtomicReference<>();
        AtomicReference<AgentRunEntity> childEntity = new AtomicReference<>();
        CountDownLatch childFinishStarted = new CountDownLatch(1),
                releaseChildFinish = new CountDownLatch(1);
        CountDownLatch commandAwaitStarted = new CountDownLatch(1),
                releaseCommandAwait = new CountDownLatch(1);
        CountDownLatch stopResponseReturned = new CountDownLatch(1);
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) return reply("子 Agent 已完成模型工作");
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 -> toolCall("start_subagent", "{\"instruction\":\"完成后停止\"}");
                        case 2 -> {
                            assertTrue(childFinishStarted.await(5, TimeUnit.SECONDS));
                            yield toolCall(
                                    "stop_subagent", "{\"runId\":\"" + childRunId.get() + "\"}");
                        }
                        case 3 -> {
                            ToolResponseMessage response =
                                    (ToolResponseMessage) prompt.getInstructions().getLast();
                            assertThat(response.getResponses().getFirst().responseData())
                                    .contains("\"stopping\":true");
                            stopResponseReturned.countDown();
                            yield reply("等待停止中的命令结束");
                        }
                        default -> reply("停止后的结果已整理");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity child =
                                    run(invocation.getArgument(0), invocation.getArgument(1));
                            childRunId.set(child.getId());
                            childEntity.set(child);
                            return child;
                        })
                .when(fixture.runs())
                .startChild(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity run = invocation.getArgument(0);
                            RunStatus status = invocation.getArgument(1, RunStatus.class);
                            if (run.getParentRunId() != null && status == RunStatus.SUCCEEDED) {
                                childFinishStarted.countDown();
                                awaitUninterruptibly(releaseChildFinish);
                            }
                            run.setStatus(status);
                            run.setResult(invocation.getArgument(2));
                            int rounds = invocation.getArgument(3);
                            run.setRounds((run.getRounds() == null ? 0 : run.getRounds()) + rounds);
                            return null;
                        })
                .when(fixture.runs())
                .finish(
                        any(AgentRunEntity.class),
                        any(RunStatus.class),
                        any(String.class),
                        org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            commandAwaitStarted.countDown();
                            awaitUninterruptibly(releaseCommandAwait);
                            return null;
                        })
                .when(fixture.sessions())
                .stopExecutionsByAgentRun(any());

        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "停止结算中的子任务"));
        try {
            assertTrue(stopResponseReturned.await(5, TimeUnit.SECONDS));
            releaseChildFinish.countDown();
            assertTrue(commandAwaitStarted.await(5, TimeUnit.SECONDS));
            assertFalse(
                    future.isDone(),
                    "child reservation stays active until its cancelled commands have exited");
            verify(fixture.sessions()).cancelExecutionsByAgentRun(childRunId.get());
        } finally {
            releaseChildFinish.countDown();
            releaseCommandAwait.countDown();
        }
        future.get(5, TimeUnit.SECONDS);
        assertThat(childEntity.get().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(childEntity.get().getRounds()).isEqualTo(1);
    }

    @Test
    void rootCancellationAlsoCancelsCommandsOwnedByFinishedChildren() throws Exception {
        Long actor = TestIds.next(),
                project = TestIds.next(),
                group = TestIds.next(),
                message = TestIds.next();
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger();
        AtomicReference<Runnable> cancellationSignal = new AtomicReference<>();
        AtomicReference<Long> childRunId = new AtomicReference<>();
        CountDownLatch rootModelBlocked = new CountDownLatch(1);
        CountDownLatch releaseRootModel = new CountDownLatch(1);
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) return reply("已完成的子任务");
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 -> toolCall("start_subagent", "{\"instruction\":\"先完成子任务\"}");
                        case 2 -> {
                            yield toolCall(
                                    "wait_subagent",
                                    "{\"runId\":\"" + childRunId.get() + "\",\"waitSeconds\":5}");
                        }
                        case 3 -> {
                            rootModelBlocked.countDown();
                            try {
                                releaseRootModel.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            yield reply("主循环被取消");
                        }
                        default -> reply("已完成");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity child =
                                    run(invocation.getArgument(0), invocation.getArgument(1));
                            childRunId.set(child.getId());
                            return child;
                        })
                .when(fixture.runs())
                .startChild(any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            cancellationSignal.set(invocation.getArgument(1));
                            return null;
                        })
                .when(fixture.runs())
                .registerCancellationSignal(any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity run = invocation.getArgument(0);
                            run.setStatus(invocation.getArgument(1, RunStatus.class));
                            run.setResult(invocation.getArgument(2));
                            return null;
                        })
                .when(fixture.runs())
                .finish(
                        any(AgentRunEntity.class),
                        any(RunStatus.class),
                        any(String.class),
                        org.mockito.ArgumentMatchers.anyInt());

        var future =
                rootExecutor.submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "完成子任务后取消"));
        try {
            assertTrue(rootModelBlocked.await(5, TimeUnit.SECONDS));
            cancellationSignal.get().run();
            verify(fixture.sessions()).cancelExecutionsByAgentRun(childRunId.get());
        } finally {
            releaseRootModel.countDown();
        }
        future.get(5, TimeUnit.SECONDS);
        verify(fixture.runs(), never())
                .requestChildCancellation(fixture.root().getId(), childRunId.get());
    }

    /** 根运行派发一个使用默认 Workspace 的子运行，等它结束再回复；两者都不失败。 */
    private WorkspaceRun rootRunWaitingForChildThatUsesDefaultWorkspace(
            Long actor, Long project, Long group, Long message) throws Exception {
        ChatModel model = model();
        AtomicInteger rootCalls = new AtomicInteger(), childCalls = new AtomicInteger();
        AtomicReference<Long> childRunId = new AtomicReference<>();
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    if (childPrompt(prompt)) {
                        return childCalls.incrementAndGet() == 1
                                ? toolCall("glob_files", "{}")
                                : reply("子任务完成");
                    }
                    return switch (rootCalls.incrementAndGet()) {
                        case 1 -> toolCall("start_subagent", "{\"instruction\":\"分析代码\"}");
                        case 2 -> toolCall("glob_files", "{}");
                        case 3 ->
                                toolCall(
                                        "wait_subagent",
                                        "{\"runId\":\""
                                                + childRunId.get()
                                                + "\",\"waitSeconds\":5}");
                        default -> reply("子任务已完成");
                    };
                });
        var fixture = fixture(model, actor, project, group, message, childExecutor);
        WorkspaceEntity workspace = workspace(project);
        when(fixture.workspaces().defaultWorkspace(project)).thenReturn(workspace);
        when(fixture.workspaces().require(project, workspace.getId())).thenReturn(workspace);
        when(fixture.sessions().acquire(any(), eq(project), eq(workspace.getId())))
                .thenAnswer(invocation ->
                        new SandboxSession(
                                invocation.getArgument(0),
                                workspace.getId(),
                                java.util.UUID.randomUUID(),
                                WorkspaceService.storageKey(workspace)));
        when(fixture.codeAccess()
                        .listFiles(
                                any(),
                                any(String.class),
                                any(String.class),
                                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(qg.qgent.harness.tool.ToolResult.ok());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity child =
                                    run(invocation.getArgument(0), invocation.getArgument(1));
                            childRunId.set(child.getId());
                            return child;
                        })
                .when(fixture.runs())
                .startChild(any(), any(), any());

        rootExecutor
                .submit(() ->
                        fixture.service()
                                .replyToMention(fixture.event(), fixture.agentId(), "并行分析代码"))
                .get(5, TimeUnit.SECONDS);
        return new WorkspaceRun(fixture, workspace, childCalls);
    }

    private Fixture fixture(
            ChatModel model,
            Long actor,
            Long project,
            Long group,
            Long message,
            ExecutorService childExecutor) {
        return fixture(model, actor, project, group, message, childExecutor, 4);
    }

    private Fixture fixture(
            ChatModel model,
            Long actor,
            Long project,
            Long group,
            Long message,
            ExecutorService childExecutor,
            int maxSubagents) {
        AgentLoop loop = new AgentLoop(model, new ObjectMapper(), 12, 4);
        AgentRunService runs = mock(AgentRunService.class);
        AgentStreamService streams = mock(AgentStreamService.class);
        when(streams.sink(any())).thenReturn(RunStreamSink.NONE);
        MessageService messages = mock(MessageService.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        SandboxFiles codeAccess = mock(SandboxFiles.class);
        SandboxSessionManager sessions = mock(SandboxSessionManager.class);
        SkillService skills = mock(SkillService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        AttachmentService attachments = mock(AttachmentService.class);
        GroupService groups = mock(GroupService.class);
        Long agentId = TestIds.next();
        when(skills.publishedCatalog(actor, project))
                .thenReturn(
                        List.of(new ContextSkill(TestIds.next(), "published-skill", "适用于测试任务")));
        ChatGroupEntity groupEntity = new ChatGroupEntity();
        groupEntity.setId(group);
        groupEntity.setProjectId(project);
        groupEntity.setName("root-only 群名");
        when(groups.requireGroupMember(project, group, actor)).thenReturn(groupEntity);
        when(history.recentMessages(actor, project, group, 30)).thenReturn(List.of());
        AgentRunEntity root = new AgentRunEntity();
        root.setId(TestIds.next());
        root.setProjectId(project);
        root.setAgentId(agentId);
        root.setGroupId(group);
        root.setTriggerMessageId(message);
        root.setCreatedBy(actor);
        root.setStatus(RunStatus.RUNNING);
        when(runs.startRoot(any(), any(), any())).thenReturn(root);
        when(runs.startChild(any(), any(), any()))
                .thenAnswer(
                        invocation -> run(invocation.getArgument(0), invocation.getArgument(1)));
        when(runs.requestChildCancellation(any(), any())).thenReturn(true);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity run = invocation.getArgument(0);
                            run.setStatus(invocation.getArgument(1, RunStatus.class));
                            run.setResult(invocation.getArgument(2));
                            return null;
                        })
                .when(runs)
                .finish(
                        any(AgentRunEntity.class),
                        any(RunStatus.class),
                        any(String.class),
                        org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            AgentRunEntity run = invocation.getArgument(0);
                            run.setStatus(invocation.getArgument(1, RunStatus.class));
                            run.setResult(invocation.getArgument(2));
                            int rounds = invocation.getArgument(3);
                            run.setRounds((run.getRounds() == null ? 0 : run.getRounds()) + rounds);
                            return null;
                        })
                .when(runs)
                .finishRoot(
                        any(AgentRunEntity.class),
                        any(RunStatus.class),
                        any(String.class),
                        org.mockito.ArgumentMatchers.anyInt());
        var projects = mock(ProjectMapper.class);
        var github =
                new GitHubCapability(
                        mock(SandboxCommands.class), mock(GitHubRepositoryService.class));
        var memories = mock(ProjectMemoryService.class);
        when(memories.get(any(), any()))
                .thenReturn(
                        new ProjectMemoryResponse("1", "# 项目约定", java.time.LocalDateTime.now()));
        var groupMemory = mock(GroupMemoryService.class);
        when(groupMemory.read(any(), any()))
                .thenReturn(new GroupMemoryResponse("1", "2", "# 群约定", null));
        Long replyMessageId = TestIds.next();
        when(messages.sendAsAgent(anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    var reply = new qg.qgent.chat.dto.MessageResponse();
                    reply.setId(replyMessageId.toString());
                    reply.setGroupId(group.toString());
                    return reply;
                });
        var agentMemories = mock(AgentMemoryService.class);
        when(agentMemories.view(any(), any())).thenReturn("# 你的分工");
        BriefingService briefing =
                new BriefingService(groups, history, skills, memories, groupMemory, agentMemories);
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setProjectId(project);
        agent.setName("评审助手");
        agent.setDescription("负责代码评审");
        agent.setSystemPrompt("你只评审 Pull Request");
        agent.setStatus(AgentEntity.STATUS_ACTIVE);
        AgentService agents = mock(AgentService.class);
        when(agents.requireActiveProjectAgent(project, agentId)).thenReturn(agent);
        var agentApplications = mock(AgentApplicationService.class);
        var service =
                new AgentChatService(
                        github,
                        mock(SandboxCommands.class),
                        workspaces,
                        mock(WorkspaceRepositoryService.class),
                        loop,
                        briefing,
                        skills,
                        history,
                        messages,
                        runs,
                        streams,
                        sessions,
                        codeAccess,
                        attachments,
                        memories,
                        groupMemory,
                        mock(GroupMemoryExtractionService.class),
                        agentMemories,
                        new CrossGroupAccessService(groups),
                        agents,
                        agentApplications,
                        childExecutor,
                        maxSubagents);
        MessageSentEvent event = new MessageSentEvent(actor, project, group, message, List.of());
        return new Fixture(
                service,
                event,
                agentId,
                messages,
                runs,
                root,
                workspaces,
                codeAccess,
                sessions,
                attachments,
                replyMessageId,
                groups,
                history,
                agentMemories,
                agents,
                agentApplications);
    }

    private AgentRunEntity run(AgentRunEntity parent, String instruction) {
        AgentRunEntity child = new AgentRunEntity();
        child.setId(TestIds.next());
        child.setProjectId(parent.getProjectId());
        child.setGroupId(parent.getGroupId());
        child.setTriggerMessageId(parent.getTriggerMessageId());
        child.setParentRunId(parent.getId());
        child.setCreatedBy(parent.getCreatedBy());
        child.setInstruction(instruction);
        child.setStatus(RunStatus.RUNNING);
        child.setRounds(0);
        return child;
    }

    private ChatModel model() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("test-model").build());
        // 既有用例按 call 桩模型回复；循环改用 stream 后在此适配为单 chunk，多 chunk 用例单独覆盖。
        when(model.stream(any(Prompt.class)))
                .thenAnswer(invocation -> Flux.just(model.call((Prompt) invocation.getArgument(0))));
        return model;
    }

    private boolean childPrompt(Prompt prompt) {
        return prompt.getInstructions().getFirst().getText().contains("临时子 Agent");
    }

    private ChatResponse reply(String content) {
        return new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content(content).build())));
    }

    private ChatResponse toolCall(String name, String arguments) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder()
                                        .content("")
                                        .toolCalls(
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "call-1",
                                                                "function",
                                                                name,
                                                                arguments)))
                                        .build())));
    }

    private ChatResponse toolCalls(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder()
                                        .content("")
                                        .toolCalls(List.of(calls))
                                        .build())));
    }

    private WorkspaceEntity workspace(Long projectId) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(TestIds.next());
        workspace.setProjectId(projectId);
        workspace.setIsDefault(true);
        workspace.setStatus("PROVISIONING");
        return workspace;
    }

    private record CodeLocation(Long workspaceId, String path) {}

    private record WorkspaceRun(
            Fixture fixture, WorkspaceEntity workspace, AtomicInteger childCalls) {}

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new AssertionError("latch was not released");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("latch was not released");
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            } finally {
                if (interrupted) Thread.interrupted();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private record Fixture(
            AgentChatService service,
            MessageSentEvent event,
            Long agentId,
            MessageService messages,
            AgentRunService runs,
            AgentRunEntity root,
            WorkspaceService workspaces,
            SandboxFiles codeAccess,
            SandboxSessionManager sessions,
            AttachmentService attachments,
            Long replyMessageId,
            GroupService groups,
            ChatHistoryService history,
            AgentMemoryService agentMemories,
            AgentService agents,
            AgentApplicationService agentApplications) {}
}
