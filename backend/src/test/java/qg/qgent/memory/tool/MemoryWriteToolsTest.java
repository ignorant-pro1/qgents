package qg.qgent.memory.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.support.TestIds;

/** 项目记忆写工具：写入身份来自本次运行，管理员与默认群由服务端校验。 */
class MemoryWriteToolsTest {

    private final ProjectMemoryService memories = mock(ProjectMemoryService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final MemoryWriteTools tools = new MemoryWriteTools(memories, actor, projectId);

    @Test
    void exposesWriteAndEditBoundToTheRunIdentity() {
        assertThat(ToolCallbacks.from(tools))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("write_project_memory", "edit_project_memory");
    }

    @Test
    void editReplacesTheAnchorThroughTheRunIdentity() {
        when(memories.edit(actor, projectId, "- 密码 8 位", "- 密码 12 位"))
                .thenReturn(
                        new ProjectMemoryResponse(
                                projectId.toString(), "# 约定\n- 密码 12 位", LocalDateTime.now()));

        Map<String, Object> result = tools.editProjectMemory("- 密码 8 位", "- 密码 12 位");

        assertThat(result).containsEntry("ok", true).containsKey("updatedAt");
        verify(memories).edit(actor, projectId, "- 密码 8 位", "- 密码 12 位");
    }

    @Test
    void blankAnchorIsRefusedWithoutTouchingMemory() {
        assertThat(tools.editProjectMemory(" ", "- x"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        verifyNoInteractions(memories);
    }

    @Test
    void writesWithTheRunActorProjectAndGroup() {
        when(memories.update(actor, projectId, "# 约定"))
                .thenReturn(
                        new ProjectMemoryResponse(
                                projectId.toString(), "# 约定", LocalDateTime.now()));

        Map<String, Object> result = tools.writeProjectMemory("# 约定");

        assertThat(result).containsEntry("ok", true).containsKey("updatedAt");
        verify(memories).update(actor, projectId, "# 约定");
    }

    @Test
    void rejectsBlankContentWithoutTouchingMemory() {
        assertThat(tools.writeProjectMemory("  "))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        verifyNoInteractions(memories);
    }

    @Test
    void refusedWriteKeepsTheServerErrorCode() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(memories)
                .update(any(), any(), any());

        Map<String, Object> result = tools.writeProjectMemory("# 约定");

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "PROJECT_ADMIN_REQUIRED");
    }

    @Test
    void diskFailureBecomesToolError() {
        doThrow(new IllegalStateException("项目记忆写入失败")).when(memories).update(any(), any(), any());

        Map<String, Object> result = tools.writeProjectMemory("# 约定");

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "MEMORY_UNAVAILABLE");
    }
}
