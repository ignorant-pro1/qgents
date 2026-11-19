package qg.qgent.memory.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.support.TestIds;

/** 项目记忆工具：Agent 只读，写入由管理员确认后主 Agent 的写工具完成。 */
class MemoryToolsTest {

    private final ProjectMemoryService memories = mock(ProjectMemoryService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final MemoryTools tools = new MemoryTools(memories, actor, projectId);

    @Test
    void exposesOnlyReadProjectMemory() {
        assertThat(ToolCallbacks.from(tools))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("read_project_memory");
    }

    @Test
    void readReturnsContentAndUpdatedAtWithoutAnyVersion() {
        when(memories.get(actor, projectId))
                .thenReturn(
                        new ProjectMemoryResponse(
                                projectId.toString(), "# 约定", LocalDateTime.now()));

        Map<String, Object> result = tools.readProjectMemory();

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("content", "# 约定")
                .containsKey("updatedAt")
                .doesNotContainKey("version");
    }

    @Test
    void readOfNeverWrittenMemoryHasNoUpdatedAt() {
        when(memories.get(actor, projectId))
                .thenReturn(new ProjectMemoryResponse(projectId.toString(), "", null));

        Map<String, Object> result = tools.readProjectMemory();

        assertThat(result).containsEntry("ok", true).containsEntry("content", "");
    }
}
