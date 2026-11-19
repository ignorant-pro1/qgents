package qg.qgent.memory.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.service.GroupMemoryExtractionService;
import qg.qgent.support.TestIds;

/** 群记忆工具：只暴露纠错提交，Agent 不能直接写群记忆。 */
class GroupMemoryToolsTest {

    private final GroupMemoryExtractionService memories = mock(GroupMemoryExtractionService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final Long groupId = TestIds.next();
    private final GroupMemoryTools tools =
            new GroupMemoryTools(memories, actor, projectId, groupId);

    @Test
    void exposesOnlyCorrectionRequestWithoutDirectWrite() {
        assertThat(ToolCallbacks.from(tools))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("request_group_memory_correction");
    }

    @Test
    void submitsCorrectionToBackgroundMaintenance() {
        Map<String, Object> result = tools.requestGroupMemoryCorrection("结论应为使用手机号登录");

        assertThat(result).containsEntry("ok", true);
        verify(memories).requestCorrection(actor, projectId, groupId, "结论应为使用手机号登录");
    }

    @Test
    void rejectsBlankCorrectionWithoutTouchingMaintenance() {
        Map<String, Object> result = tools.requestGroupMemoryCorrection("  ");

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        verifyNoInteractions(memories);
    }

    @Test
    void accessDeniedBecomesToolError() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问"))
                .when(memories)
                .requestCorrection(any(), any(), any(), any());

        Map<String, Object> result = tools.requestGroupMemoryCorrection("结论应为 B");

        assertThat(result).containsEntry("ok", false).containsEntry("errorCode", "GROUP_NOT_FOUND");
    }

    @Test
    void failureBecomesToolError() {
        doThrow(new IllegalStateException("数据库不可用"))
                .when(memories)
                .requestCorrection(any(), any(), any(), any());

        Map<String, Object> result = tools.requestGroupMemoryCorrection("结论应为 B");

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "GROUP_MEMORY_UNAVAILABLE");
    }
}
