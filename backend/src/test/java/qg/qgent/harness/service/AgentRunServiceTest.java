package qg.qgent.harness.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.service.MessageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.support.TestIds;

class AgentRunServiceTest {
    private final Long actor = TestIds.next(), projectId = TestIds.next();
    private final Long groupId = TestIds.next(), messageId = TestIds.next();
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final MessageService messages = mock(MessageService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final AgentRunService service = new AgentRunService(runs, messages, access);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentRunEntity.class);
    }

    @BeforeEach
    void initTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void marksRunsLeftRunningByAPreviousProcessAsFailed() {
        when(runs.update(isNull(), any())).thenReturn(2);

        service.failInterruptedChildren();

        ArgumentCaptor<Wrapper<AgentRunEntity>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(runs).update(isNull(), wrapper.capture());
        assertThat(
                        ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                                        wrapper.getValue())
                                .getSqlSet())
                .contains("status", "result", "finished_at");
        assertThat(wrapper.getValue().getSqlSegment()).contains("status IN", "parent_run_id IS NOT NULL");
        assertThat(
                        ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                                        wrapper.getValue())
                                .getParamNameValuePairs()
                                .values())
                .contains("服务重启，运行已中断", RunStatus.FAILED, RunStatus.RUNNING);
    }

    @Test
    void senderCanCancelRunningRootsFromTheSelectedMessageAfterCommit() {
        messageFrom(actor);
        AgentRunEntity root = runningRoot();
        Runnable cancellation = mock(Runnable.class);
        service.registerCancellationSignal(root.getId(), cancellation);
        when(runs.selectList(any())).thenReturn(List.of(root));
        when(runs.update(isNull(), any())).thenReturn(1);
        assertThat(service.cancel(actor, projectId, groupId, messageId)).isEqualTo(1);
        verifyNoInteractions(cancellation);
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(cancellation).run();
        ArgumentCaptor<Wrapper<AgentRunEntity>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(runs).selectList(wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertThat(sql)
                .contains(
                        "project_id",
                        "group_id",
                        "trigger_message_id",
                        "parent_run_id IS NULL",
                        "status");
        verifyNoInteractions(access);
    }

    @Test
    void queuedRunCanBeCancelledBeforeAnyWorkerClaimsIt() {
        messageFrom(actor);
        AgentRunEntity root = runningRoot();
        root.setAgentId(TestIds.next());
        root.setStatus(RunStatus.QUEUED);
        when(runs.selectList(any())).thenReturn(List.of(root));
        when(runs.update(isNull(), any())).thenReturn(1, 0);

        assertThat(service.cancel(actor, projectId, groupId, messageId)).isEqualTo(1);
        assertThat(service.claimRoot(root.getId())).isNull();
        verify(messages).sendAgentRunReply(root.getAgentId(), groupId, messageId, root.getId(), "Agent 运行已取消");
    }

    @Test
    void queuedRunCanOnlyBeClaimedOnce() {
        AgentRunEntity root = runningRoot();
        when(runs.update(isNull(), any())).thenReturn(1, 0);
        when(runs.selectById(root.getId())).thenReturn(root);

        assertThat(service.claimRoot(root.getId())).isSameAs(root);
        assertThat(service.claimRoot(root.getId())).isNull();
    }

    @Test
    void cancellingAnotherUsersExecutionRequiresProjectAdmin() {
        messageFrom(TestIds.next());
        service.cancel(actor, projectId, groupId, messageId);
        verify(access).requireProjectAdmin(projectId, actor);
        verify(runs).selectList(any());
    }

    @Test
    void ordinaryMemberCannotCancelAnotherUsersExecution() {
        messageFrom(TestIds.next());
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access)
                .requireProjectAdmin(projectId, actor);
        assertThatThrownBy(() -> service.cancel(actor, projectId, groupId, messageId))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(runs);
    }

    @Test
    void inaccessibleMessageCannotCancelAnyRun() {
        when(messages.getMessage(actor, projectId, groupId, messageId))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "消息不存在"));
        assertThatThrownBy(() -> service.cancel(actor, projectId, groupId, messageId))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(access, runs);
    }

    @Test
    void resumeClearsThePersistedCompletionTimestamp() throws Exception {
        AgentRunEntity run = new AgentRunEntity();
        run.setFinishedAt(java.time.LocalDateTime.now());
        run.setInstruction("原任务");
        when(runs.updateById(any(AgentRunEntity.class))).thenReturn(1);

        service.resume(run, "继续实现");

        ArgumentCaptor<AgentRunEntity> saved = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).updateById(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(saved.getValue().getFinishedAt()).isNull();
        assertThat(saved.getValue().getInstruction()).contains("继续实现");
        TableField mapping =
                AgentRunEntity.class.getDeclaredField("finishedAt").getAnnotation(TableField.class);
        assertThat(mapping.updateStrategy()).isEqualTo(FieldStrategy.ALWAYS);
    }

    @Test
    void rootFinishKeepsSuccessWhenItsConditionalUpdateWins() {
        AgentRunEntity root = runningRoot();
        when(runs.update(isNull(), any())).thenReturn(1);

        service.finishRoot(root, RunStatus.SUCCEEDED, "完成", 2);

        assertThat(root.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(root.getResult()).isEqualTo("完成");
        assertThat(root.getRounds()).isEqualTo(3);
        assertThat(root.getFinishedAt()).isNotNull();
        ArgumentCaptor<Wrapper<AgentRunEntity>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(runs).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSegment()).contains("status");
        assertThat(updateValues(update.getValue())).contains("RUNNING", "SUCCEEDED");
        verify(runs, never()).selectById(root.getId());
    }

    @Test
    void aLateCancellationCannotChangeTheRootAfterItsFinishUpdateWins() {
        AgentRunEntity root = runningRoot();
        messageFrom(actor);
        AtomicReference<String> persistedStatus = new AtomicReference<>("RUNNING");
        AtomicInteger updateCount = new AtomicInteger();
        when(runs.selectList(any())).thenReturn(List.of(root));
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Wrapper<AgentRunEntity> update = invocation.getArgument(1);
                            assertThat(update.getSqlSegment()).contains("status");
                            return switch (updateCount.getAndIncrement()) {
                                case 0 -> {
                                    assertThat(persistedStatus.get()).isEqualTo("RUNNING");
                                    assertThat(updateValues(update))
                                            .contains("RUNNING", "SUCCEEDED");
                                    persistedStatus.set("SUCCEEDED");
                                    assertThat(service.cancel(actor, projectId, groupId, messageId))
                                            .isZero();
                                    yield 1;
                                }
                                case 1 -> {
                                    assertThat(persistedStatus.get()).isEqualTo("SUCCEEDED");
                                    assertThat(updateValues(update))
                                            .contains("RUNNING", "CANCELLING");
                                    yield 0;
                                }
                                default -> throw new AssertionError("Unexpected AgentRun update");
                            };
                        })
                .when(runs)
                .update(isNull(), any());

        service.finishRoot(root, RunStatus.SUCCEEDED, "完成", 2);

        assertThat(persistedStatus.get()).isEqualTo("SUCCEEDED");
        assertThat(root.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(root.getResult()).isEqualTo("完成");
        assertThat(updateCount.get()).isEqualTo(2);
    }

    @Test
    void cancellationThatWinsAtTheRootFinishWriteCannotBeOverwritten() {
        AgentRunEntity root = runningRoot();
        messageFrom(actor);
        when(runs.selectList(any())).thenReturn(java.util.List.of(root));
        AtomicReference<String> persistedStatus = new AtomicReference<>("RUNNING");
        AtomicInteger updateCount = new AtomicInteger();

        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Wrapper<AgentRunEntity> update = invocation.getArgument(1);
                            assertThat(update.getSqlSegment()).contains("status");
                            return switch (updateCount.getAndIncrement()) {
                                case 0 -> {
                                    assertThat(persistedStatus.get()).isEqualTo("RUNNING");
                                    assertThat(updateValues(update))
                                            .contains("RUNNING", "SUCCEEDED");
                                    assertThat(service.cancel(actor, projectId, groupId, messageId))
                                            .isEqualTo(1);
                                    assertThat(persistedStatus.get()).isEqualTo("CANCELLING");
                                    yield 0;
                                }
                                case 1 -> {
                                    assertThat(persistedStatus.get()).isEqualTo("RUNNING");
                                    assertThat(updateValues(update))
                                            .contains("RUNNING", "CANCELLING");
                                    persistedStatus.set("CANCELLING");
                                    yield 1;
                                }
                                case 2 -> {
                                    assertThat(persistedStatus.get()).isEqualTo("CANCELLING");
                                    assertThat(updateValues(update))
                                            .contains("CANCELLING", "CANCELLED");
                                    persistedStatus.set("CANCELLED");
                                    yield 1;
                                }
                                default -> throw new AssertionError("Unexpected AgentRun update");
                            };
                        })
                .when(runs)
                .update(isNull(), any());

        service.finishRoot(root, RunStatus.SUCCEEDED, "完成", 2);

        assertThat(persistedStatus.get()).isEqualTo("CANCELLED");
        assertThat(root.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(root.getResult()).isEqualTo("Agent 运行已取消");
        assertThat(root.getRounds()).isEqualTo(3);
        assertThat(root.getFinishedAt()).isNotNull();
        assertThat(updateCount.get()).isEqualTo(3);
    }

    private void messageFrom(Long sender) {
        MessageResponse message = new MessageResponse();
        message.setSenderId(sender.toString());
        when(messages.getMessage(actor, projectId, groupId, messageId)).thenReturn(message);
    }

    private AgentRunEntity runningRoot() {
        AgentRunEntity root = new AgentRunEntity();
        root.setId(TestIds.next());
        root.setProjectId(projectId);
        root.setGroupId(groupId);
        root.setTriggerMessageId(messageId);
        root.setRounds(1);
        root.setStatus(RunStatus.RUNNING);
        return root;
    }

    private java.util.Collection<Object> updateValues(Wrapper<AgentRunEntity> update) {
        // 枚举状态按名字参与 SQL 绑定，断言仍用字符串直接比对。
        return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) update)
                .getParamNameValuePairs().values().stream()
                        .map(value -> value instanceof Enum<?> constant ? constant.name() : value)
                        .toList();
    }
}
