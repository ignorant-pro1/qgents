package qg.qgent.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.mapper.WorkspaceMapper;

/** 持久化 Workspace 写租约的 CAS 语义测试，不启动数据库。 */
class WorkspaceWriteLeaseServiceTest {

    @Test
    void acquireReturnsOpaqueTokenOnlyAfterDatabaseCasWins() {
        WorkspaceMapper mapper = mock(WorkspaceMapper.class);
        Long projectId = TestIds.next(), workspaceId = TestIds.next(), agentRunId = TestIds.next();
        when(mapper.claimWriteLease(eq(projectId), eq(workspaceId), eq(agentRunId), any()))
                .thenReturn(1);

        WorkspaceWriteLeaseService service = new WorkspaceWriteLeaseService(mapper);
        WorkspaceWriteLease lease = service.acquire(projectId, workspaceId, agentRunId);

        assertEquals(projectId, lease.getProjectId());
        assertEquals(workspaceId, lease.getWorkspaceId());
        assertEquals(agentRunId, lease.getAgentRunId());
        assertNotNull(lease.token());
    }

    @Test
    void acquireRejectsOtherActiveRunWithoutOverwritingIt() {
        WorkspaceMapper mapper = mock(WorkspaceMapper.class);
        when(mapper.claimWriteLease(any(), any(), any(), any())).thenReturn(0);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                new WorkspaceWriteLeaseService(mapper)
                                        .acquire(TestIds.next(), TestIds.next(), TestIds.next()));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("WORKSPACE_WRITE_LEASE_HELD", error.code());
    }

    @Test
    void renewalOrReleaseCanOnlyUseTheOwningToken() {
        WorkspaceMapper mapper = mock(WorkspaceMapper.class);
        Long projectId = TestIds.next(), workspaceId = TestIds.next(), agentRunId = TestIds.next();
        WorkspaceWriteLease lease =
                new WorkspaceWriteLease(projectId, workspaceId, agentRunId, "lease-token");
        when(mapper.renewWriteLease(projectId, workspaceId, agentRunId, "lease-token"))
                .thenReturn(0);
        WorkspaceWriteLeaseService service = new WorkspaceWriteLeaseService(mapper);

        ApiException error = assertThrows(ApiException.class, () -> service.renew(lease));
        assertEquals("WORKSPACE_WRITE_LEASE_LOST", error.code());

        service.release(lease);
        verify(mapper).releaseWriteLease(projectId, workspaceId, agentRunId, "lease-token");
    }
}
