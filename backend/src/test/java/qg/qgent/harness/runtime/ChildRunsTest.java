package qg.qgent.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.support.TestIds;

class ChildRunsTest {
    @Test
    void bindingTheLazyDefaultWorkspacePreservesTheAgentDirectory() {
        ChildRuns.Execution execution =
                new ChildRuns.Execution(new AgentRunEntity(), null, null, ".");
        Long workspace = TestIds.next();
        execution.workspace.setDirectory("src/runtime");

        execution.workspace.bindDefault(workspace);

        assertThat(execution.workspace.workspaceId()).isEqualTo(workspace);
        assertThat(execution.workspace.directory()).isEqualTo("src/runtime");
    }

    @Test
    void explicitlySelectingAnotherWorkspaceResetsTheAgentDirectory() {
        ChildRuns.Execution execution =
                new ChildRuns.Execution(new AgentRunEntity(), null, TestIds.next(), "src/runtime");

        execution.workspace.select(TestIds.next());

        assertThat(execution.workspace.directory()).isEqualTo(".");
    }

    @Test
    void resumingAStoppedChildAllowsItsCommandsToBeCancelledAgain() {
        Long runId = TestIds.next();
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        ChildRuns children =
                new ChildRuns(
                        TestIds.next(),
                        1,
                        Runnable::run,
                        mock(AgentRunService.class),
                        new RunLifecycle(
                                mock(AgentRunService.class),
                                mock(SandboxSessionManager.class),
                                new WorkContext(null, null, null, runId)),
                        () -> false,
                        mock(AgentLoop.class),
                        child -> List.of(),
                        child -> RunStreamSink.NONE);
        ChildRuns.Execution child = new ChildRuns.Execution(run, null, null, ".");
        assertThat(children.reserve(child)).isTrue();
        child.schedule();
        assertThat(children.requestStop(child)).isTrue();
        assertThat(children.markCommandsCancelled(runId)).isTrue();
        assertThat(children.markCommandsCancelled(runId)).isFalse();
        assertThat(child.beginFinishing()).isTrue();
        run.setStatus(RunStatus.CANCELLED);
        assertThat(children.publishFinish(child)).isTrue();

        assertThat(children.reserveContinuation(child)).isTrue();

        assertThat(children.markCommandsCancelled(runId)).isTrue();
    }
}
