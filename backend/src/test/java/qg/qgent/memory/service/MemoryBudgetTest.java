package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;

/** 三种记忆共用同一套预算策略：超出上下文字符预算一律拒绝写入，不截断也不覆盖原有正文。 */
class MemoryBudgetTest {

    private static final int MAX_CHARS = 50;

    @TempDir Path root;

    private final Long projectId = TestIds.next();
    private final Long groupId = TestIds.next();
    private final Long agentId = TestIds.next();
    private final String tooLong = "x".repeat(MAX_CHARS + 1);
    private ProjectMemoryService projectMemories;
    private GroupMemoryService groupMemories;
    private AgentMemoryService agentMemories;

    @BeforeEach
    void setUp() {
        ProjectDirectoryService directories =
                new ProjectDirectoryService(root.toString(), root.toString());
        MemoryDocument document = new MemoryDocument(MAX_CHARS);
        projectMemories =
                new ProjectMemoryService(
                        mock(ProjectAccessService.class),
                        mock(ProjectMapper.class),
                        directories,
                        mock(EventService.class),
                        document);
        groupMemories =
                new GroupMemoryService(
                        mock(ProjectAccessService.class),
                        mock(GroupService.class),
                        directories,
                        document);
        agentMemories = new AgentMemoryService(directories, document);
    }

    @Test
    void overBudgetWriteIsRejectedForAllThreeMemoryKindsWithoutChangingStoredContent() {
        projectMemories.update(TestIds.next(), projectId, "项目原有约定");
        groupMemories.write(projectId, groupId, "群原有约定");
        agentMemories.save(projectId, agentId, "Agent 原有约定");

        assertRejected(() -> projectMemories.update(TestIds.next(), projectId, tooLong));
        assertRejected(() -> groupMemories.write(projectId, groupId, tooLong));
        assertRejected(() -> agentMemories.save(projectId, agentId, tooLong));

        assertThat(projectMemories.read(projectId).getContent()).isEqualTo("项目原有约定");
        assertThat(groupMemories.read(projectId, groupId).getContent()).isEqualTo("群原有约定");
        assertThat(agentMemories.view(projectId, agentId)).isEqualTo("Agent 原有约定");
    }

    private void assertRejected(Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "MEMORY_CONTEXT_LIMIT_EXCEEDED");
    }
}
