package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.GroupService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.config.GroupMemoryProperties;
import qg.qgent.memory.entity.GroupMemoryStateEntity;
import qg.qgent.memory.mapper.GroupMemoryStateMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.support.TestIds;

/** 群记忆提炼：触发条件、失败不推进进度、有限重试、并发领取、纠错与领取失效。 */
class GroupMemoryExtractionServiceTest {

    private static final int MIN_MESSAGES = 3;
    private static final int MAX_ATTEMPTS = 2;
    private static final int IDLE_MINUTES = 15;
    private static final int RETRY_DELAY_MINUTES = 5;
    private static final int MAX_MESSAGES_PER_RUN = 200;
    private static final int SCAN_PAGE_SIZE = 20;
    private static final Long AGENT_ID = 4242L;

    @TempDir Path root;

    private final GroupMemoryStateMapper states = mock(GroupMemoryStateMapper.class);
    private final MessageMapper chatMessages = mock(MessageMapper.class);
    private final MessageService chat = mock(MessageService.class);
    private final GroupService groups = mock(GroupService.class);
    private final ChatModel model = mock(ChatModel.class);
    private final Executor executor = Runnable::run;
    private final MemoryDocument document = new MemoryDocument(12000);
    private GroupMemoryService memories;
    private GroupMemoryExtractionService service;

    @BeforeEach
    void setUp() {
        memories =
                new GroupMemoryService(
                        mock(ProjectAccessService.class),
                        groups,
                        new ProjectDirectoryService(root.toString(), root.toString()),
                        document);
        when(chat.agentInstruction(any()))
                .thenAnswer(invocation -> ((MessageEntity) invocation.getArgument(0)).getContent());
        service =
                new GroupMemoryExtractionService(
                        states,
                        chatMessages,
                        chat,
                        groups,
                        memories,
                        model,
                        executor,
                        properties(),
                        document);
    }

    @Test
    void extractsWhenMessageCountAndIdleTimeBothReachThreshold() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        List<MessageEntity> messages =
                List.of(
                        message(groupId, 1, 7L, "先做邮箱登录"),
                        message(groupId, 2, 7L, "同意"),
                        message(groupId, 3, null, "结论：邮箱登录"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(groupId), any(), any())).thenReturn(1);
        when(states.finishSuccess(eq(groupId), any(), any(), any(), any())).thenReturn(1);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(messages);
        when(model.call(any(Prompt.class))).thenReturn(reply("# 群记忆\n- 结论：邮箱登录"));

        service.scan();

        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 群记忆\n- 结论：邮箱登录");
        verify(states).finishSuccess(eq(groupId), any(), any(), eq(3L), any());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .anySatisfy(instruction ->
                        // 群里有多个 Agent：发言按发送者 ID 标注，不能一律当成默认 Agent。
                        assertThat(instruction.getText())
                                .contains("先做邮箱登录", "用户 7：同意", "Agent " + AGENT_ID + "：结论：邮箱登录")
                                .doesNotContain("Qgent"));
    }

    @Test
    void waitsForIdleTimeBeforeExtracting() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));

        service.scan();

        verify(states, never()).claim(any(), any(), any());
        verifyNoInteractions(model);
    }

    @Test
    void waitsForEnoughNewMessages() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 5, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(chatMessages.selectAfterSequence(eq(groupId), eq(5L), eq(MIN_MESSAGES)))
                .thenReturn(
                        List.of(message(groupId, 6, 7L, "只聊了两句"), message(groupId, 7, 7L, "嗯")));

        service.scan();

        verify(states, never()).claim(any(), any(), any());
        verifyNoInteractions(model);
    }

    @Test
    void givesUpWhenAnotherRunAlreadyClaimedTheGroup() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MIN_MESSAGES)))
                .thenReturn(
                        List.of(
                                message(groupId, 1, 7L, "a"),
                                message(groupId, 2, 7L, "b"),
                                message(groupId, 3, 7L, "c")));
        when(states.claim(eq(groupId), any(), any())).thenReturn(0);

        service.scan();

        verifyNoInteractions(model);
    }

    @Test
    void discardsResultWhenClaimWasTakenOverByAnotherRun() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.write(projectId, groupId, "# 旧群记忆");
        GroupMemoryStateEntity state =
                state(projectId, groupId, 4, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        List<MessageEntity> messages =
                List.of(
                        message(groupId, 5, 7L, "a"),
                        message(groupId, 6, 7L, "b"),
                        message(groupId, 7, 7L, "c"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(groupId), any(), any())).thenReturn(0);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(4L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(4L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(messages);
        when(model.call(any(Prompt.class))).thenReturn(reply("# 新群记忆"));

        service.scan();

        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 旧群记忆");
        verify(states, never()).finishSuccess(any(), any(), any(), any(), any());
    }

    @Test
    void keepsWrittenMemoryWhenFinishNoLongerOwnsTheClaim() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        List<MessageEntity> messages =
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(groupId), any(), any())).thenReturn(1);
        when(states.finishSuccess(eq(groupId), any(), any(), any(), any())).thenReturn(0);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(messages);
        when(model.call(any(Prompt.class))).thenReturn(reply("# 群记忆"));

        service.scan();

        // 领取已失效：不报错，也不记录失败，进度由接管任务推进
        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 群记忆");
        verify(states, never()).recordFailure(any(), any(), any(), any(), any());
    }

    @Test
    void failureKeepsPreviousMemoryAndProcessedProgress() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.write(projectId, groupId, "# 旧群记忆");
        GroupMemoryStateEntity state =
                state(projectId, groupId, 4, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        List<MessageEntity> messages =
                List.of(
                        message(groupId, 5, 7L, "a"),
                        message(groupId, 6, 7L, "b"),
                        message(groupId, 7, 7L, "c"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(4L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(4L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(messages);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型调用超时"));

        service.scan();

        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 旧群记忆");
        verify(states, never()).finishSuccess(any(), any(), any(), any(), any());
        verify(states).recordFailure(eq(groupId), any(), contains("模型调用超时"), any(), any());
    }

    @Test
    void stopsRetryingAfterMaxAttemptsUntilNewMessageArrives() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        state.setAttemptCount(MAX_ATTEMPTS);
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));

        service.scan();

        verify(states, never()).claim(any(), any(), any());
        verifyNoInteractions(model);
    }

    @Test
    void waitsForRetryDelayAfterFailure() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        state.setAttemptCount(1);
        state.setNextAttemptAt(
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(RETRY_DELAY_MINUTES - 1));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));

        service.scan();

        verify(states, never()).claim(any(), any(), any());
        verifyNoInteractions(model);
    }

    @Test
    void pendingCorrectionAloneTriggersExtraction() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.write(projectId, groupId, "# 群记忆\n- 结论：做邮箱登录");
        GroupMemoryStateEntity state =
                state(projectId, groupId, 9, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        state.setCorrection("结论应为做手机号登录");
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(groupId), any(), any())).thenReturn(1);
        when(states.finishSuccess(eq(groupId), any(), any(), any(), any())).thenReturn(1);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(9L), eq(MIN_MESSAGES)))
                .thenReturn(List.of());
        when(chatMessages.selectAfterSequence(eq(groupId), eq(9L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenReturn(reply("# 群记忆\n- 结论：做手机号登录"));

        service.scan();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .anySatisfy(instruction ->
                        assertThat(instruction.getText()).contains("结论应为做手机号登录", "结论：做邮箱登录"));
        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 群记忆\n- 结论：做手机号登录");
        verify(states).finishSuccess(eq(groupId), any(), eq("结论应为做手机号登录"), eq(9L), any());
    }

    @Test
    void keepsCorrectionWhenModelOutputIgnoresIt() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.write(projectId, groupId, "# 群记忆\n- 结论：做邮箱登录");
        GroupMemoryStateEntity state =
                state(projectId, groupId, 9, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        state.setCorrection("结论应为做手机号登录");
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(9L), eq(MIN_MESSAGES)))
                .thenReturn(List.of());
        when(chatMessages.selectAfterSequence(eq(groupId), eq(9L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenReturn(reply("# 群记忆\n- 结论：做邮箱登录"));

        service.scan();

        // 纠错没被采纳时不写文件、不清纠错，按失败重试
        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 群记忆\n- 结论：做邮箱登录");
        verify(states, never()).finishSuccess(any(), any(), any(), any(), any());
        verify(states).recordFailure(eq(groupId), any(), contains("未应用群记忆纠错"), any(), any());
    }

    @Test
    void nextRunOnlySendsMessagesAboveTheSavedWatermark() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.write(projectId, groupId, "# 已提炼到第 3 条");
        GroupMemoryStateEntity state =
                state(projectId, groupId, 3, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        List<MessageEntity> newMessages =
                List.of(
                        message(groupId, 4, 7L, "a"),
                        message(groupId, 5, 7L, "b"),
                        message(groupId, 6, 7L, "c"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(groupId), any(), any())).thenReturn(1);
        when(states.finishSuccess(eq(groupId), any(), any(), any(), any())).thenReturn(1);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(3L), eq(MIN_MESSAGES)))
                .thenReturn(newMessages);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(3L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(newMessages);
        when(model.call(any(Prompt.class))).thenReturn(reply("# 已提炼到第 6 条"));

        service.scan();

        // 只从已保存的水位之后读取，不重复处理也不跳过中间消息
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .anySatisfy(instruction ->
                        assertThat(instruction.getText())
                                .contains("已提炼到第 3 条", "用户 7：a", "用户 7：c"));
        verify(states).finishSuccess(eq(groupId), any(), any(), eq(6L), any());
    }

    @Test
    void rendersSystemMessagesWithoutUserPlaceholder() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        MessageEntity system = message(groupId, 1, null, "群已创建");
        system.setSenderType("SYSTEM");
        List<MessageEntity> messages =
                List.of(system, message(groupId, 2, 7L, "b"), message(groupId, 3, 7L, "c"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(groupId), any(), any())).thenReturn(1);
        when(states.finishSuccess(eq(groupId), any(), any(), any(), any())).thenReturn(1);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(messages);
        when(model.call(any(Prompt.class))).thenReturn(reply("# 群记忆"));

        service.scan();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .anySatisfy(instruction ->
                        assertThat(instruction.getText())
                                .contains("系统：群已创建")
                                .doesNotContain("用户 null"));
    }

    @Test
    void findsWorkBeyondTheFirstPageOfAlreadyCaughtUpGroups() {
        Long projectId = TestIds.next();
        // 一整页已提炼完且安静的群按 group_id 排在前面；只读一页的实现永远看不到后面的群
        List<GroupMemoryStateEntity> caughtUp = new ArrayList<>();
        for (long groupId = 1; groupId <= SCAN_PAGE_SIZE; groupId++) {
            caughtUp.add(
                    state(
                            projectId,
                            groupId,
                            9,
                            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30)));
        }
        GroupMemoryStateEntity active =
                state(projectId, 100L, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        GroupMemoryStateEntity corrected =
                state(projectId, 101L, 9, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        corrected.setCorrection("结论应为做手机号登录");
        when(states.selectCandidates(any(), isNull(), anyInt())).thenReturn(caughtUp);
        when(states.selectCandidates(any(), eq(Long.valueOf(SCAN_PAGE_SIZE)), anyInt()))
                .thenReturn(List.of(active, corrected));
        when(states.selectById(active.getGroupId())).thenReturn(active);
        when(states.claim(eq(active.getGroupId()), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(active.getGroupId()), any(), any())).thenReturn(1);
        when(states.finishSuccess(eq(active.getGroupId()), any(), any(), any(), any()))
                .thenReturn(1);
        List<MessageEntity> messages =
                List.of(
                        message(active.getGroupId(), 1, 7L, "a"),
                        message(active.getGroupId(), 2, 7L, "b"),
                        message(active.getGroupId(), 3, 7L, "c"));
        when(chatMessages.selectAfterSequence(eq(active.getGroupId()), eq(0L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        when(chatMessages.selectAfterSequence(
                        eq(active.getGroupId()), eq(0L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(messages);
        when(model.call(any(Prompt.class))).thenReturn(reply("# 群记忆"));

        service.scan();

        // 第一页只有没活可干的群，仍要按游标继续翻页，才能提炼新活动的群和待纠错的群
        verify(states).selectCandidates(any(), eq(Long.valueOf(SCAN_PAGE_SIZE)), anyInt());
        verify(states).claim(eq(active.getGroupId()), any(), any());
        verify(states).claim(eq(corrected.getGroupId()), any(), any());
    }

    @Test
    void sendsOnlyTheCorrectionItAppliedSoLaterCorrectionsSurvive() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        memories.write(projectId, groupId, "# 群记忆");
        GroupMemoryStateEntity state =
                state(projectId, groupId, 9, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        state.setCorrection("结论应为 A");
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(groupId), any(), any())).thenReturn(1);
        when(states.finishSuccess(eq(groupId), any(), any(), any(), any())).thenReturn(1);
        when(model.call(any(Prompt.class))).thenReturn(reply("# 群记忆\n- 结论：A"));

        service.scan();

        // 只把实际读到的纠错交给完成语句做比较清除，运行期间新增的纠错不会被删掉
        verify(states).finishSuccess(eq(groupId), any(), eq("结论应为 A"), eq(9L), any());
    }

    @Test
    void scanContinuesWhenExtractionQueueIsFull() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long otherGroup = TestIds.next();
        GroupMemoryStateEntity first =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        GroupMemoryStateEntity second =
                state(projectId, otherGroup, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(19));
        List<MessageEntity> messages =
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(first, second));
        when(chatMessages.selectAfterSequence(any(), eq(0L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        Executor rejecting =
                task -> {
                    throw new RejectedExecutionException("队列已满");
                };
        GroupMemoryExtractionService fullQueue =
                new GroupMemoryExtractionService(
                        states,
                        chatMessages,
                        chat,
                        groups,
                        memories,
                        model,
                        rejecting,
                        properties(),
                        document);

        assertThatCode(fullQueue::scan).doesNotThrowAnyException();
        // 队列拒绝时没有领取，状态保持可重试
        verify(states, never()).claim(any(), any(), any());
    }

    @Test
    void stripsModelCodeFenceBeforeWritingMemory() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        List<MessageEntity> messages =
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(states.renewClaim(eq(groupId), any(), any())).thenReturn(1);
        when(states.finishSuccess(eq(groupId), any(), any(), any(), any())).thenReturn(1);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(messages);
        when(model.call(any(Prompt.class))).thenReturn(reply("```markdown\n# 群记忆\n```"));

        service.scan();

        assertThat(memories.read(projectId, groupId).getContent()).isEqualTo("# 群记忆");
    }

    @Test
    void blankModelReplyFailsWithoutAdvancingProgress() {
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        GroupMemoryStateEntity state =
                state(projectId, groupId, 0, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        List<MessageEntity> messages =
                List.of(
                        message(groupId, 1, 7L, "a"),
                        message(groupId, 2, 7L, "b"),
                        message(groupId, 3, 7L, "c"));
        when(states.selectCandidates(any(), any(), anyInt())).thenReturn(List.of(state));
        when(states.selectById(groupId)).thenReturn(state);
        when(states.claim(eq(groupId), any(), any())).thenReturn(1);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MIN_MESSAGES)))
                .thenReturn(messages);
        when(chatMessages.selectAfterSequence(eq(groupId), eq(0L), eq(MAX_MESSAGES_PER_RUN)))
                .thenReturn(messages);
        when(model.call(any(Prompt.class))).thenReturn(reply("   "));

        service.scan();

        verify(states, never()).finishSuccess(any(), any(), any(), any(), any());
        verify(states).recordFailure(eq(groupId), any(), any(), any(), any());
    }

    @Test
    void requestCorrectionRequiresGroupMembership() {
        Long actor = TestIds.next();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问"))
                .when(groups)
                .requireGroupMember(projectId, groupId, actor);

        assertThatThrownBy(() -> service.requestCorrection(actor, projectId, groupId, "结论应为 B"))
                .isInstanceOf(ApiException.class);
        verify(states, never()).requestCorrection(any(), any(), any(), any());
    }

    @Test
    void requestCorrectionRecordsTrimmedPendingCorrection() {
        Long actor = TestIds.next();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();

        service.requestCorrection(actor, projectId, groupId, "  结论应为 B  ");

        verify(states).requestCorrection(eq(projectId), eq(groupId), eq("结论应为 B"), any());
    }

    private GroupMemoryProperties properties() {
        GroupMemoryProperties properties = new GroupMemoryProperties();
        properties.setMinMessages(MIN_MESSAGES);
        properties.setMaxAttempts(MAX_ATTEMPTS);
        properties.setIdleMinutes(IDLE_MINUTES);
        properties.setRetryDelayMinutes(RETRY_DELAY_MINUTES);
        properties.setMaxMessagesPerRun(MAX_MESSAGES_PER_RUN);
        properties.setScanPageSize(SCAN_PAGE_SIZE);
        return properties;
    }

    private GroupMemoryStateEntity state(
            Long projectId, Long groupId, long watermark, LocalDateTime lastMessageAt) {
        GroupMemoryStateEntity state = new GroupMemoryStateEntity();
        state.setProjectId(projectId);
        state.setGroupId(groupId);
        state.setLastProcessedSequence(watermark);
        state.setLastMessageAt(lastMessageAt);
        state.setStatus("IDLE");
        state.setAttemptCount(0);
        return state;
    }

    private MessageEntity message(Long groupId, long sequence, Long authorUserId, String text) {
        MessageEntity message = new MessageEntity();
        message.setGroupId(groupId);
        message.setSequenceNo(sequence);
        message.setAuthorUserId(authorUserId);
        message.setSenderType(authorUserId == null ? "AGENT" : "USER");
        message.setSenderAgentId(authorUserId == null ? AGENT_ID : null);
        message.setMessageType("TEXT");
        message.setContent(text);
        return message;
    }

    private ChatResponse reply(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
