package qg.qgent.harness.integration;

import org.junit.jupiter.api.Test;
import qg.qgent.harness.config.AgentExecutorProperties;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.runtime.AgentChatService;
import qg.qgent.harness.service.AgentRunCompletion;
import qg.qgent.harness.service.AgentRunService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;

class AgentRunDispatcherTest {
    @Test
    void recoversInterruptedRunsAndLeavesQueuedRunsEligibleWhenTheExecutorRejects() {
        AgentRunService runs = mock(AgentRunService.class);
        AgentRunCompletion completion = mock(AgentRunCompletion.class);
        AgentChatService chat = mock(AgentChatService.class);
        when(runs.interruptedRootIds()).thenReturn(List.of(91L));
        when(runs.queuedRootIds(anyInt())).thenReturn(List.of(92L));
        AgentRunEntity queued = new AgentRunEntity();
        queued.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(runs.claimRoot(92L)).thenReturn(queued);
        AtomicBoolean rejectFirst = new AtomicBoolean(true);
        var dispatcher = new AgentRunDispatcher(runs, completion, chat, task -> {
            if (rejectFirst.getAndSet(false)) throw new RejectedExecutionException();
            task.run();
        }, new AgentExecutorProperties());

        dispatcher.dispatch();
        verifyNoInteractions(runs);
        dispatcher.recover();
        dispatcher.dispatch();
        verify(runs, never()).claimRoot(92L);
        dispatcher.dispatch();

        verify(completion).complete(eq(91L), eq(RunStatus.FAILED), anyString(), eq(0));
        verify(chat).executeRoot(queued);
    }
}
