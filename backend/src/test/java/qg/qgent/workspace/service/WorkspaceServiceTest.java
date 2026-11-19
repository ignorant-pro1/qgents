package qg.qgent.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

/** 工作区记录创建和选择校验，不准备物理目录或执行环境。 */
class WorkspaceServiceTest {

    @Test
    void creatingWorkspaceOnlyStoresMetadata() {
        WorkspaceService service = new WorkspaceService(mapper);
        Long projectId = TestIds.next();
        WorkspaceEntity created = service.create(projectId);
        assertThat(created.getProjectId()).isEqualTo(projectId);
        assertThat(created.getStatus()).isEqualTo("PROVISIONING");
        assertThat(created.getIsDefault()).isFalse();
        org.mockito.Mockito.verify(mapper).insert(created);
    }

    @Test
    void defaultWorkspaceIsReusedWhenAlreadyRegistered() {
        Long projectId = TestIds.next();
        WorkspaceEntity existing = new WorkspaceEntity();
        existing.setId(TestIds.next());
        existing.setProjectId(projectId);
        existing.setIsDefault(true);
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(new WorkspaceService(mapper).defaultWorkspace(projectId)).isSameAs(existing);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .insert(any(WorkspaceEntity.class));
    }

    @Test
    void defaultWorkspaceIsCreatedOnceWhenAbsent() {
        Long projectId = TestIds.next();
        when(mapper.selectOne(any())).thenReturn(null);
        WorkspaceEntity created = new WorkspaceService(mapper).defaultWorkspace(projectId);
        assertThat(created.getIsDefault()).isTrue();
        assertThat(WorkspaceService.storageKey(created))
                .isEqualTo(projectId + "/workspaces/default");
        org.mockito.Mockito.verify(mapper).insert(created);
    }

    @Test
    void selectingWorkspaceChecksProjectAndAvailability() {
        WorkspaceEntity existing = workspace();
        Long projectId = TestIds.next();
        existing.setProjectId(projectId);
        existing.setStatus("READY");
        when(mapper.selectById(existing.getId())).thenReturn(existing);
        WorkspaceService service = new WorkspaceService(mapper);
        assertThat(service.require(projectId, existing.getId())).isSameAs(existing);
        assertThatThrownBy(() -> service.require(TestIds.next(), existing.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        existing.setStatus("ARCHIVED");
        assertThatThrownBy(() -> service.require(projectId, existing.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private final WorkspaceMapper mapper = mock(WorkspaceMapper.class);

    private WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(TestIds.next());
        workspace.setIsDefault(false);
        return workspace;
    }
}
